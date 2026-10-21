package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.agent.memory.*;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class AgentMemory {
    public record Cached(long version,long previousId,List<MemoryRepository.Row> rows) {}
    private static final Logger log=LoggerFactory.getLogger(AgentMemory.class);
    private final StringRedisTemplate redis;
    private final MemoryRepository repository;
    private final ObjectMapper json;
    private final ContextBuilder contexts;
    private final MemorySettings settings;
    private final ConversationSummarizer summarizer;
    private final Semaphore reads;
    @Autowired
    public AgentMemory(StringRedisTemplate redis,MemoryRepository repository,ObjectMapper json,ContextBuilder contexts,
                       MemorySettings settings,ConversationSummarizer summarizer) {
        this.redis=redis;this.repository=repository;this.json=json;this.contexts=contexts;
        this.settings=settings;this.summarizer=summarizer;reads=new Semaphore(settings.readConcurrency());
    }
    // Standalone integration tests do not schedule background model calls.
    public AgentMemory(StringRedisTemplate redis,JdbcTemplate jdbc,ObjectMapper json) {
        this(redis,new MemoryRepository(jdbc,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))),
                json,new ContextBuilder(json),MemorySettings.defaults(),null);
    }
    public List<Message> load(long user,UUID conversation) {
        if (!reads.tryAcquire()) { throw new BusinessException(ErrorCode.AGENT_UNAVAILABLE); }
        try {
            return repository.snapshot(user,conversation,state->{
                List<MemoryRepository.Row> rows=null;
                try {
                    String raw=redis.opsForValue().get(key(user,conversation));
                    if (raw!=null) {
                        Cached cached=json.readValue(raw,Cached.class);
                        if (valid(cached,state)) { rows=cached.rows().stream().filter(r->r.id()>state.boundary()).toList(); }
                    }
                } catch (Exception ex) { log.debug("Memory cache miss or unavailable for {}",conversation); }
                if (rows==null) {
                    rows=repository.messages(user,conversation,state.boundary(),settings.readMessages(),false);
                    cache(user,conversation,state.messageVersion(),state.boundary(),rows);
                }
                return contexts.history(state.summary(),rows);
            });
        } finally { reads.release(); }
    }
    public MemoryFacts.Result fact(long user,UUID conversation,MemoryFacts.Field field) {
        if (!reads.tryAcquire()) { throw new BusinessException(ErrorCode.AGENT_UNAVAILABLE); }
        try { return repository.fact(user,conversation,field); }
        finally { reads.release(); }
    }
    private boolean valid(Cached c,MemoryRepository.State state) {
        if (c==null || c.version()!=state.messageVersion() || c.previousId()>state.boundary()
                || c.rows()==null || c.rows().size()>settings.recentMessages() || c.rows().size()%2!=0) { return false; }
        long previous=c.previousId();
        for (int i=0;i<c.rows().size();i+=2) {
            var u=c.rows().get(i);var a=c.rows().get(i+1);
            if (u.id()<=previous || u.content()==null || a.content()==null || !ContextBuilder.pair(u,a)) { return false; }
            previous=a.id();
        }
        return !c.rows().isEmpty() || c.version()==0;
    }
    private void cache(long user,UUID conversation,long version,long boundary,List<MemoryRepository.Row> rows) {
        int from=Math.max(0,rows.size()-settings.recentMessages());
        long previous=from==0?boundary:rows.get(from-1).id();
        try {
            redis.opsForValue().set(key(user,conversation),json.writeValueAsString(new Cached(version,previous,List.copyOf(rows.subList(from,rows.size())))),
                    Duration.ofSeconds(ThreadLocalRandom.current().nextLong(5400,9001)));
        } catch (Exception ex) { log.debug("Memory cache fill failed for {}",conversation); }
    }
    public void refresh(long user,UUID conversation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { refreshCommitted(user,conversation); }
                    catch (RuntimeException ex) { log.warn("Memory refresh deferred after commit for {}",conversation); }
                }
            });
        } else { refreshCommitted(user,conversation); }
    }
    private void refreshCommitted(long user,UUID conversation) {
        try {
            if (!reads.tryAcquire()) { return; }
            try {
                repository.snapshot(user,conversation,state->{
                    var rows=repository.messages(user,conversation,0,settings.recentMessages()+2,false);
                    cache(user,conversation,state.messageVersion(),0,rows);return true;
                });
            } finally { reads.release(); }
        } finally { if (summarizer!=null) { summarizer.request(user,conversation); } }
    }
    public void clear(long user,UUID conversation) {
        repository.queueCleanup(user,conversation);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { clearCache(user,conversation,false); }
            });
        } else { clearCache(user,conversation,true); }
    }
    private void clearCache(long user,UUID conversation,boolean acknowledge) {
        try {
            redis.delete(key(user,conversation));
            // afterCommit still has bound JDBC resources; acknowledge on the scheduler's fresh connection.
            if (acknowledge) { repository.cleaned(user,conversation); }
        } catch (RuntimeException ex) { log.warn("Memory cache cleanup deferred for {}",conversation); }
    }
    @Scheduled(initialDelayString="${agent.memory.recovery-delay-ms:30000}",fixedDelayString="${agent.memory.recovery-delay-ms:30000}")
    public void cleanup() {
        try { for (var owner:repository.cleanups()) { clearCache(owner.userId(),owner.conversationId(),true); } }
        catch (RuntimeException ex) { log.warn("Memory cache cleanup unavailable; next poll will retry"); }
    }
    private String key(long user,UUID conversation) { return "agent:memory:"+user+":"+conversation; }
}
