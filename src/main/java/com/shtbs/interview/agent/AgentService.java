package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.knowledge.KnowledgeBaseManagementService;
import com.shtbs.interview.agent.memory.MemoryFacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class AgentService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AgentService.class);
    private final AgentRuntime runtime;
    private final AgentRepository repository;
    private final AgentMemory memory;
    private final KnowledgeBaseManagementService bases;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public AgentService(AgentRuntime runtime,AgentRepository repository,AgentMemory memory,
                        KnowledgeBaseManagementService bases,ObjectMapper json,JdbcTemplate jdbc,
                        PlatformTransactionManager manager) {
        this.runtime=runtime;this.repository=repository;this.memory=memory;this.bases=bases;
        this.json=json;this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);
    }

    public AgentModels.Response handle(long userId,AgentModels.Request request,boolean study) {
        if (study && request.memoryField()!=null) { throw new com.shtbs.interview.exception.BusinessException(com.shtbs.interview.common.ErrorCode.PARAM_ERROR); }
        List<Long> selected=request.knowledgeBaseIds()==null?List.of():request.knowledgeBaseIds().stream().distinct().toList();
        for (long base:selected) { bases.requireOwned(userId,base); }
        String kind=study?"STUDY":"CHAT";
        UUID conversation=repository.conversation(userId,request.conversationId(),kind);
        UUID token=UUID.randomUUID();
        repository.claim(userId,conversation,token);
        UUID execution=null;
        try {
            MemoryFacts.Field field=study?null:request.memoryField()!=null?request.memoryField():MemoryFacts.question(request.message());
            MemoryFacts.Result fact=field==null?null:memory.fact(userId,conversation,field);
            String acknowledgement=study || field!=null?null:MemoryFacts.acknowledgement(request.message());
            AgentRuntime.Run<AgentModels.Answer> run=fact!=null?runtime.recall(userId,conversation,selected,fact):
                    acknowledgement!=null?runtime.direct(userId,conversation,selected,acknowledgement):
                    runtime.run(userId,conversation,null,kind,request.message(),selected,memory.load(userId,conversation),AgentModels.Answer.class);
            execution=run.executionId();
            repository.beginCommit(execution);
            Long plan=transactions.execute(ignored -> {
                repository.lockCommit(run.executionId());
                repository.requireLease(conversation,token);
                repository.revalidateEvidence(run.evidence(),run.evidence().referenced(run.value()));
                repository.message(conversation,run.executionId(),"USER",request.message());
                repository.message(conversation,run.executionId(),"ASSISTANT",repository.json(run.value()));
                Long saved=study?repository.savePlan(userId,conversation,run.executionId(),run.value()):null;
                repository.completeExecution(run.executionId());
                return saved;
            });
            try { memory.refresh(userId,conversation); }
            catch (RuntimeException cacheFailure) { log.warn("Memory refresh failed after committing {}",execution); }
            return new AgentModels.Response(conversation,run.executionId(),plan,run.value(),fact);
        } catch (RuntimeException ex) {
            repository.failExecution(execution,ex);
            throw ex;
        } finally {
            try { repository.release(conversation,token); }
            catch (RuntimeException cleanupFailure) { log.warn("Lease release failed for {}; it will expire",conversation); }
        }
    }

    public List<Map<String,Object>> conversations(long userId,int page,int size) {
        pagination(page,size);
        return jdbc.queryForList("SELECT id,kind,created_at FROM agent_conversation WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                userId,size,((long)page-1)*size);
    }

    public List<Map<String,Object>> messages(long userId,UUID conversation,long afterId) {
        owned(userId,conversation);
        return jdbc.queryForList("SELECT id,role,content,created_at FROM agent_message WHERE conversation_id=? AND id>? ORDER BY id LIMIT 100",
                conversation,afterId);
    }

    public Map<String,Object> execution(long userId,UUID execution) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM agent_execution WHERE id=? AND user_id=?",execution,userId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND); }
        Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
        result.put("tools",jdbc.queryForList("SELECT tool_call_id,tool_name,input,output,status,elapsed_ms FROM agent_tool_call WHERE execution_id=? ORDER BY id",execution));
        result.put("evidence",jdbc.queryForList("SELECT evidence_id,evidence_type,source_id,tool_call_id,record::text AS snapshot FROM agent_evidence WHERE execution_id=? ORDER BY created_at,evidence_id",execution));
        return result;
    }

    public List<Map<String,Object>> executions(long userId,Long sessionId,int page,int size) {
        pagination(page,size);
        return jdbc.queryForList("""
                SELECT id,conversation_id,session_id,kind,state,prompt_version,model_calls,tool_calls,error_code,created_at,finished_at
                FROM agent_execution WHERE user_id=? AND (?::bigint IS NULL OR session_id=?)
                ORDER BY created_at DESC,id LIMIT ? OFFSET ?
                """,userId,sessionId,sessionId,size,((long)page-1)*size);
    }

    public List<Map<String,Object>> plans(long userId,int page,int size) {
        pagination(page,size);
        return jdbc.queryForList("SELECT id,conversation_id,execution_id,created_at FROM study_plan WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                userId,size,((long)page-1)*size);
    }

    public JsonNode plan(long userId,long id) {
        List<String> rows=jdbc.query("SELECT content::text FROM study_plan WHERE id=? AND user_id=?",(rs,row)->rs.getString(1),id,userId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND); }
        try { return json.readTree(rows.getFirst()); }
        catch (Exception ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }
    }

    @Transactional
    public void delete(long userId,UUID conversation) {
        owned(userId,conversation);
        List<UUID> locked=jdbc.query("""
                SELECT id FROM agent_conversation WHERE id=? AND user_id=?
                  AND (busy_token IS NULL OR busy_until<CURRENT_TIMESTAMP) FOR UPDATE
                """,(rs,row)->rs.getObject(1,UUID.class),conversation,userId);
        if (locked.isEmpty()) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
        jdbc.update("DELETE FROM study_plan WHERE conversation_id=?",conversation);
        jdbc.update("DELETE FROM agent_message WHERE conversation_id=?",conversation);
        jdbc.update("DELETE FROM agent_tool_call WHERE execution_id IN (SELECT id FROM agent_execution WHERE conversation_id=?)",conversation);
        jdbc.update("DELETE FROM agent_execution WHERE conversation_id=?",conversation);
        jdbc.update("DELETE FROM agent_conversation WHERE id=?",conversation);
        memory.clear(userId,conversation);
    }

    private void owned(long userId,UUID conversation) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_conversation WHERE id=? AND user_id=?)",
                Boolean.class,conversation,userId))) { throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND); }
    }
    private void pagination(int page,int size) {
        if (page<1 || page>1000000 || size<1 || size>100) { throw new BusinessException(ErrorCode.PARAM_ERROR); }
    }
}
