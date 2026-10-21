package com.shtbs.interview.agent.memory;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Function;

@Repository
public class MemoryRepository {
    public record Row(long id,UUID executionId,String role,String content) {}
    public record Summary(String text,long lastMessageId,int version) {}
    public record State(long messageVersion,Summary summary) {
        public long boundary() { return summary==null?0:summary.lastMessageId(); }
    }
    public record Owner(long userId,UUID conversationId) {}
    public record Batch(State state,List<Row> rows) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads,writes;

    public MemoryRepository(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;writes=new TransactionTemplate(manager);reads=new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setReadOnly(true);
    }
    public <T> T snapshot(long user,UUID conversation,Function<State,T> reader) {
        return reads.execute(ignored->reader.apply(state(user,conversation)));
    }
    private State state(long user,UUID conversation) {
        return jdbc.query("""
                SELECT c.message_version,s.summary,s.last_message_id,s.version FROM agent_conversation c
                LEFT JOIN agent_memory_summary s ON s.conversation_id=c.id AND s.user_id=c.user_id
                WHERE c.id=? AND c.user_id=?
                """,(rs,n)->new State(rs.getLong(1),rs.getString(2)==null?null:
                new Summary(rs.getString(2),rs.getLong(3),rs.getInt(4))),conversation,user).stream()
                .findFirst().orElseThrow(()->new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));
    }
    public List<Row> messages(long user,UUID conversation,long after,int limit,boolean oldestFirst) {
        var rows=jdbc.query("""
                SELECT m.id,m.execution_id,m.role,m.content FROM agent_message m
                JOIN agent_conversation c ON c.id=m.conversation_id
                WHERE c.id=? AND c.user_id=? AND m.id>?
                ORDER BY m.id
                """+(oldestFirst?" ASC":" DESC")+" LIMIT ?",
                (rs,n)->new Row(rs.getLong(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),
                conversation,user,after,limit);
        if (!oldestFirst) { Collections.reverse(rows); }return List.copyOf(rows);
    }
    public Batch batch(long user,UUID conversation,MemorySettings settings) {
        return snapshot(user,conversation,state->{
            var rows=messages(user,conversation,state.boundary(),settings.batchMessages()+settings.recentMessages(),true);
            if (rows.size()<settings.triggerMessages()) { return new Batch(state,List.of()); }
            return new Batch(state,List.copyOf(rows.subList(0,rows.size()-settings.recentMessages())));
        });
    }
    public MemoryFacts.Result fact(long user,UUID conversation,MemoryFacts.Field field) {
        var rows=factRows(user,conversation,Long.MAX_VALUE);
        return MemoryFacts.resolve(field,rows,rows.size()>1000);
    }
    public List<Row> factRows(long user,UUID conversation,long through) {
        return snapshot(user,conversation,state->{
            return jdbc.query("""
                    SELECT m.id,m.execution_id,m.role,m.content FROM agent_message m
                    JOIN agent_conversation c ON c.id=m.conversation_id
                    WHERE c.id=? AND c.user_id=? AND m.role='USER' AND m.id<=?
                    ORDER BY m.id DESC LIMIT 1001
                    """,(rs,n)->new Row(rs.getLong(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4)),conversation,user,through);
        });
    }
    public boolean save(long user,UUID conversation,Summary previous,String text,long boundary) {
        if (text==null || text.isBlank() || boundary<1 || (previous!=null && boundary<=previous.lastMessageId())) {
            throw new IllegalArgumentException("Summary must advance its boundary");
        }
        return Boolean.TRUE.equals(writes.execute(ignored->{
            // Fence deletion only during the short save transaction, never during a model call.
            if (jdbc.query("SELECT id FROM agent_conversation WHERE id=? AND user_id=? FOR KEY SHARE",
                    (rs,n)->rs.getObject(1),conversation,user).isEmpty()) { return false; }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM agent_message WHERE conversation_id=? AND id=? AND role='ASSISTANT')
                    """,Boolean.class,conversation,boundary))) { return false; }
            if (previous==null) {
                return jdbc.update("""
                        INSERT INTO agent_memory_summary(conversation_id,user_id,summary,last_message_id)
                        VALUES(?,?,?,?) ON CONFLICT(conversation_id) DO NOTHING
                        """,conversation,user,text,boundary)==1;
            }
            return jdbc.update("""
                    UPDATE agent_memory_summary SET summary=?,last_message_id=?,version=version+1,updated_at=clock_timestamp()
                    WHERE conversation_id=? AND user_id=? AND version=? AND last_message_id=?
                    """,text,boundary,conversation,user,previous.version(),previous.lastMessageId())==1;
        }));
    }
    public List<Owner> pending(int trigger) {
        return jdbc.query("""
                SELECT c.user_id,c.id FROM agent_conversation c
                LEFT JOIN agent_memory_summary s ON s.conversation_id=c.id
                WHERE c.memory_retry_after<=clock_timestamp() AND (SELECT COUNT(*) FROM (SELECT m.id FROM agent_message m
                    WHERE m.conversation_id=c.id AND m.id>COALESCE(s.last_message_id,0) LIMIT ?) pending)>=?
                ORDER BY c.memory_retry_after,c.id LIMIT 32
                """,(rs,n)->new Owner(rs.getLong(1),rs.getObject(2,UUID.class)),trigger,trigger);
    }
    public void queueCleanup(long user,UUID conversation) {
        jdbc.update("INSERT INTO agent_memory_cache_cleanup(user_id,conversation_id) VALUES(?,?) ON CONFLICT DO NOTHING",user,conversation);
    }
    public void defer(long user,UUID conversation) {
        jdbc.update("UPDATE agent_conversation SET memory_retry_after=clock_timestamp()+INTERVAL '60 seconds' WHERE id=? AND user_id=?",conversation,user);
    }
    public List<Owner> cleanups() {
        return jdbc.query("SELECT user_id,conversation_id FROM agent_memory_cache_cleanup ORDER BY created_at LIMIT 100",
                (rs,n)->new Owner(rs.getLong(1),rs.getObject(2,UUID.class)));
    }
    public void cleaned(long user,UUID conversation) {
        jdbc.update("DELETE FROM agent_memory_cache_cleanup WHERE user_id=? AND conversation_id=?",user,conversation);
    }
}
