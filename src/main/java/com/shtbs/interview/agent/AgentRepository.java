package com.shtbs.interview.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.shtbs.interview.agent.evidence.*;

@Repository
public class AgentRepository {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AgentRepository.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    public String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }
    }

    public AgentModels.Profile profile(long userId) {
        return jdbc.query("SELECT target, level, daily_minutes FROM user_learning_profile WHERE user_id = ?",
                (rs, row) -> new AgentModels.Profile(rs.getString(1), rs.getString(2), rs.getInt(3)), userId)
                .stream().findFirst().orElse(new AgentModels.Profile("Java后端", "学生", 60));
    }

    public void profile(long userId, AgentModels.Profile profile) {
        jdbc.update("""
                INSERT INTO user_learning_profile(user_id,target,level,daily_minutes) VALUES (?,?,?,?)
                ON CONFLICT(user_id) DO UPDATE SET target=EXCLUDED.target,level=EXCLUDED.level,
                    daily_minutes=EXCLUDED.daily_minutes,updated_at=CURRENT_TIMESTAMP
                """, userId, profile.target(), profile.level(), profile.dailyMinutes());
    }

    public List<Map<String, Object>> history(long userId, int limit) {
        return jdbc.queryForList("""
                SELECT s.id AS "sessionId",s.category,s.difficulty,s.end_time AS "endTime",
                       e.score,LEFT(e.strengths,1000) AS strengths,LEFT(e.weaknesses,1000) AS weaknesses,
                       LEFT(e.suggestions,1000) AS suggestions
                FROM interview_session s LEFT JOIN interview_evaluation e ON e.session_id=s.id
                WHERE s.user_id=? AND s.status='COMPLETED'
                ORDER BY s.end_time DESC NULLS LAST,s.id DESC LIMIT ?
                """, userId, Math.max(1, Math.min(5, limit)));
    }

    public List<Map<String, Object>> weaknesses(long userId) {
        return jdbc.queryForList("""
                WITH recent AS (
                    SELECT id FROM interview_session WHERE user_id=? AND status='COMPLETED'
                    ORDER BY end_time DESC NULLS LAST,id DESC LIMIT 5
                ), evidence AS (
                    SELECT s.id AS "sessionId",s.category,t.id AS "turnId",t.round_number AS "round",
                        LEFT(t.question,300) AS question,t.score,t.correct,t.skipped,
                        (SELECT jsonb_agg(LEFT(value,300))::text FROM jsonb_array_elements_text(t.missing_points)
                            WITH ORDINALITY AS point(value,position) WHERE position<=5) AS "missingPointsJson",
                        LEFT(t.missing_points::text,500) AS "missingPointsExcerpt",
                        LEFT(e.weaknesses,300) AS "sessionWeaknessesExcerpt",
                        ROW_NUMBER() OVER(PARTITION BY s.id ORDER BY t.round_number NULLS LAST) AS position
                    FROM recent JOIN interview_session s ON s.id=recent.id
                    LEFT JOIN interview_turn t ON t.session_id=s.id AND t.status='COMPLETED'
                        AND (t.skipped OR NOT t.correct OR t.score<60)
                    LEFT JOIN interview_evaluation e ON e.session_id=s.id
                ) SELECT "sessionId",category,"turnId","round",question,score,correct,skipped,
                    "missingPointsJson","missingPointsExcerpt","sessionWeaknessesExcerpt"
                FROM evidence WHERE position<=3 ORDER BY "sessionId" DESC,"round" LIMIT 15
                """, userId);
    }

    public List<Map<String, Object>> questions(String keyword, int limit) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.queryForList("""
                SELECT id AS "questionId",category,title,difficulty,LEFT(content,2000) AS content
                FROM question WHERE title ILIKE ? ESCAPE '!' OR category ILIKE ? ESCAPE '!'
                    OR content ILIKE ? ESCAPE '!'
                ORDER BY id LIMIT ?
                """, pattern, pattern, pattern, Math.max(1, Math.min(10, limit)));
    }

    public UUID conversation(long userId, UUID requested, String kind) {
        if (requested != null) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM agent_conversation WHERE id=? AND user_id=? AND kind=?)
                    """, Boolean.class, requested, userId, kind))) {
                throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
            }
            return requested;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_conversation(id,user_id,kind) VALUES (?,?,?)", id, userId, kind);
        return id;
    }

    public void claim(long userId, UUID conversation, UUID token) {
        if (jdbc.update("""
                UPDATE agent_conversation SET busy_token=?,busy_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes'
                WHERE id=? AND user_id=? AND (busy_token IS NULL OR busy_until<CURRENT_TIMESTAMP)
                """, token, conversation, userId) != 1) {
            throw new BusinessException(ErrorCode.REQUEST_CONFLICT);
        }
    }

    public void release(UUID conversation, UUID token) {
        jdbc.update("UPDATE agent_conversation SET busy_token=NULL,busy_until=NULL WHERE id=? AND busy_token=?",
                conversation, token);
    }

    public void requireLease(UUID conversation, UUID token) {
        List<UUID> rows = jdbc.query("""
                SELECT id FROM agent_conversation WHERE id=? AND busy_token=?
                    AND busy_until>CURRENT_TIMESTAMP FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), conversation, token);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
    }

    public void execution(UUID id, long userId, UUID conversation, Long session, String kind, int version) {
        execution(id,userId,conversation,session,kind,version,60000);
    }

    public void execution(UUID id,long userId,UUID conversation,Long session,String kind,int version,long timeoutMs) {
        execution(id,userId,conversation,session,kind,version,java.time.Instant.now().plusMillis(timeoutMs));
    }

    public void execution(UUID id,long userId,UUID conversation,Long session,String kind,int version,java.time.Instant expiresAt) {
        jdbc.update("""
                INSERT INTO agent_execution(id,user_id,conversation_id,session_id,kind,state,prompt_version,expires_at)
                VALUES (?,?,?,?,?,'INIT',?,?)
                """, id, userId, conversation, session, kind, version,java.sql.Timestamp.from(expiresAt));
    }

    public void state(UUID id, String state, int models, int tools, Integer error) {
        if (!java.util.Set.of("THINKING","TOOL_CALL","GENERATING","VALIDATED").contains(state)) {
            throw new IllegalArgumentException("Use the business transaction or failure API for terminal states");
        }
        int changed=jdbc.update("""
                UPDATE agent_execution SET state=?,model_calls=?,tool_calls=?,error_code=?,
                    finished_at=NULL
                WHERE id=? AND state IN ('INIT','THINKING','TOOL_CALL','GENERATING') AND committed_at IS NULL
                  AND expires_at>clock_timestamp()
                """, state, models, tools, error, id);
        if (changed!=1) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
    }

    public void beginCommit(UUID execution) {
        if (jdbc.update("""
                UPDATE agent_execution SET state='COMMITTING'
                WHERE id=? AND state='VALIDATED' AND committed_at IS NULL AND expires_at>clock_timestamp()
                """,execution)!=1) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
    }

    // Call first inside the business transaction: the row lock fences recovery until commit/rollback.
    public void lockCommit(UUID execution) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Business completion requires a transaction");
        }
        if (jdbc.query("""
                SELECT id FROM agent_execution WHERE id=? AND state='COMMITTING'
                    AND committed_at IS NULL AND expires_at>clock_timestamp() FOR UPDATE
                """,(rs,row)->rs.getObject(1,UUID.class),execution).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_TIMEOUT);
        }
    }

    public void completeExecution(UUID execution) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Business completion requires a transaction");
        }
        if (jdbc.update("""
                UPDATE agent_execution SET state='FINISHED',committed_at=clock_timestamp(),finished_at=clock_timestamp(),error_code=NULL
                WHERE id=? AND state='COMMITTING' AND committed_at IS NULL AND expires_at>clock_timestamp()
                """,execution)!=1) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
    }

    public void failExecution(UUID execution,RuntimeException failure) {
        if (execution==null) { return; }
        int code=failure instanceof BusinessException b?b.getCode():6001;
        String state=code==6008?"TIMED_OUT":"FAILED";
        try {
            jdbc.update("""
                    UPDATE agent_execution SET state=?,error_code=?,finished_at=clock_timestamp()
                    WHERE id=? AND state NOT IN ('FINISHED','FAILED','TIMED_OUT') AND committed_at IS NULL
                    """,state,code,execution);
            failRunningTools(execution,state);
        } catch (RuntimeException ex) { log.warn("Cannot persist failure for execution {}; recovery will retry",execution); }
    }

    public int recoverExpired() {
        return jdbc.queryForObject("""
                WITH candidates AS (
                    SELECT e.id FROM agent_execution e
                    WHERE e.state NOT IN ('FINISHED','FAILED','TIMED_OUT') AND e.committed_at IS NULL
                      AND e.expires_at<clock_timestamp() AND e.created_at<clock_timestamp()-INTERVAL '3 minutes'
                      AND NOT EXISTS(SELECT 1 FROM agent_conversation c WHERE c.id=e.conversation_id
                          AND c.busy_token IS NOT NULL AND c.busy_until>clock_timestamp())
                      AND NOT EXISTS(SELECT 1 FROM interview_session s WHERE s.id=e.session_id
                          AND s.busy_request IS NOT NULL AND s.busy_until>clock_timestamp())
                      AND NOT EXISTS(SELECT 1 FROM agent_message m WHERE m.execution_id=e.id)
                      AND NOT EXISTS(SELECT 1 FROM study_plan p WHERE p.execution_id=e.id)
                      AND NOT EXISTS(SELECT 1 FROM interview_turn t WHERE t.status='COMPLETED' AND t.result->>'executionId'=e.id::text)
                      AND NOT EXISTS(SELECT 1 FROM interview_evaluation v WHERE v.execution_id=e.id)
                    ORDER BY e.expires_at LIMIT 100 FOR UPDATE OF e SKIP LOCKED
                ), expired AS (
                    UPDATE agent_execution e SET state='TIMED_OUT',error_code=6008,finished_at=clock_timestamp()
                    FROM candidates c WHERE e.id=c.id RETURNING e.id
                ), tools AS (
                    UPDATE agent_tool_call t SET status='TIMED_OUT',
                        elapsed_ms=(EXTRACT(EPOCH FROM (clock_timestamp()-t.created_at))*1000)::bigint
                    FROM expired e WHERE t.execution_id=e.id AND t.status='RUNNING' RETURNING t.id
                ) SELECT COUNT(*)::int FROM expired
                """,Integer.class);
    }

    public void tool(UUID execution, String id, String name, String input, String output,
                     String status, long elapsed) {
        jdbc.update("""
                INSERT INTO agent_tool_call(execution_id,tool_call_id,tool_name,input,output,status,elapsed_ms)
                VALUES (?,?,?,?,?,?,?)
                """, execution, clip(id,200), clip(name,100), clip(input,4096), clip(output,8192), status, elapsed);
    }

    public long startTool(UUID execution,String id,String name,String input) {
        return jdbc.queryForObject("""
                INSERT INTO agent_tool_call(execution_id,tool_call_id,tool_name,input,status)
                SELECT id,?,?,?,'RUNNING' FROM agent_execution
                WHERE id=? AND state='TOOL_CALL' AND expires_at>clock_timestamp() RETURNING id
                """,Long.class,id,name,clip(input,4096),execution);
    }

    public void finishTool(long row,String output,String status,long elapsed) {
        jdbc.update("""
                UPDATE agent_tool_call t SET output=?,status=?,elapsed_ms=? WHERE t.id=? AND t.status='RUNNING'
                  AND (?<>'SUCCEEDED' OR EXISTS(SELECT 1 FROM agent_execution e WHERE e.id=t.execution_id
                      AND e.state='TOOL_CALL' AND e.expires_at>clock_timestamp()))
                """,
                clip(output,8192),status,elapsed,row,status);
    }

    public void failRunningTools(UUID execution,String status) {
        jdbc.update("""
                UPDATE agent_tool_call SET status=?,elapsed_ms=(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-created_at))*1000)::bigint
                WHERE execution_id=? AND status='RUNNING'
                """,status,execution);
    }

    public void finishGroundedTool(UUID execution,long userId,long row,String output,
                                   List<EvidenceRecord> records,Runnable check) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(ignored->{
            check.run();
            if (jdbc.query("""
                    SELECT id FROM agent_execution WHERE id=? AND user_id=? AND state='TOOL_CALL'
                        AND expires_at>clock_timestamp() FOR UPDATE
                    """,(rs,n)->rs.getObject(1,UUID.class),execution,userId).isEmpty()) {
                throw new BusinessException(ErrorCode.AGENT_TIMEOUT);
            }
            for (var record:records) {
                jdbc.update("""
                        INSERT INTO agent_evidence(execution_id,evidence_id,evidence_type,source_id,tool_call_id,record)
                        VALUES(?,?,?,?,?,?::jsonb)
                        """,execution,record.evidenceId(),record.type().name(),record.sourceId(),record.toolCallId(),json(record));
            }
            check.run();
            if (jdbc.update("""
                    UPDATE agent_tool_call SET status='SUCCEEDED',output=?,elapsed_ms=
                        (EXTRACT(EPOCH FROM (clock_timestamp()-created_at))*1000)::bigint
                    WHERE id=? AND execution_id=? AND status='RUNNING'
                        AND EXISTS(SELECT 1 FROM agent_execution WHERE id=? AND expires_at>clock_timestamp())
                    """,clip(output,8192),row,execution,execution)!=1) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
            check.run();
        });
    }

    public AgentTools.KnowledgeHit knowledgeHit(long userId,List<Long> bases,String chunkId,boolean lock) {
        if (lock && !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Citation revalidation requires a transaction");
        }
        UUID id;
        try { id=UUID.fromString(chunkId); } catch (RuntimeException ex) { return null; }
        List<Map<String,Object>> raw=jdbc.queryForList("SELECT content,metadata::text AS metadata FROM vector_store WHERE id=?",id);
        if (raw.isEmpty()) { return null; }
        try {
            var metadata=json.readTree((String)raw.getFirst().get("metadata"));
            String owner=metadata.path("ingest_owner").asText();
            if ("knowledge-sync-v1".equals(owner)) {
                var rows=jdbc.queryForList("SELECT content,metadata::text AS metadata FROM vector_store WHERE id=? AND metadata->>'ingest_owner'='knowledge-sync-v1'"+(lock?" FOR SHARE":""),id);
                if (rows.isEmpty()) { return null; }
                var current=json.readTree((String)rows.getFirst().get("metadata"));
                String content=(String)rows.getFirst().get("content"),source=current.path("source").asText("");
                if (content==null || source.isBlank()) { return null; }
                return new AgentTools.KnowledgeHit(source,null,null,chunkId,clip(content,2000),"PUBLIC",null,EvidenceRegistry.hash(content));
            }
            if (!"knowledge-document-v1".equals(owner) || !metadata.hasNonNull("documentId")) { return null; }
            long documentId=metadata.get("documentId").asLong();
            if (lock) {
                if (jdbc.query("SELECT id FROM knowledge_document WHERE id=? AND owner_user_id=? FOR SHARE",
                        (rs,n)->rs.getLong(1),documentId,userId).isEmpty()) { return null; }
            }
            var rows=jdbc.queryForList("""
                    SELECT d.id,d.knowledge_base_id,d.active_version,s.content,v.original_name
                    FROM vector_store s JOIN knowledge_document d ON d.id::text=s.metadata->>'documentId'
                    JOIN knowledge_base b ON b.id=d.knowledge_base_id
                    JOIN knowledge_document_version v ON v.document_id=d.id AND v.version=d.active_version
                    WHERE s.id=? AND d.id=? AND d.owner_user_id=? AND b.owner_user_id=? AND d.status='READY'
                        AND s.metadata->>'ingest_owner'='knowledge-document-v1' AND s.metadata->>'published'='true'
                        AND s.metadata->>'documentVersion'=d.active_version::text
                        AND s.metadata->>'userId'=d.owner_user_id::text
                        AND s.metadata->>'knowledgeBaseId'=b.id::text
                    """+(lock?" FOR SHARE OF s":""),id,documentId,userId,userId);
            if (rows.isEmpty()) { return null; }
            var found=rows.getFirst();long base=((Number)found.get("knowledge_base_id")).longValue();
            if (!bases.contains(base)) { return null; }
            String content=(String)found.get("content");
            return new AgentTools.KnowledgeHit((String)found.get("original_name"),documentId,
                    ((Number)found.get("active_version")).intValue(),chunkId,clip(content,2000),"PRIVATE",base,EvidenceRegistry.hash(content));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT); }
    }

    public void revalidateEvidence(EvidenceRegistry registry,java.util.Set<String> references) {
        for (var record:registry.records().stream().filter(r->references.contains(r.evidenceId()))
                .sorted(java.util.Comparator.comparing((EvidenceRecord r)->r.type().name())
                        .thenComparing(r->r.attributes().path("documentId").asText(""))
                        .thenComparing(EvidenceRecord::sourceId)).toList()) {
            var attributes=record.attributes();
            if (record.type()==EvidenceType.KNOWLEDGE) {
                var current=knowledgeHit(registry.userId(),registry.bases(),attributes.path("chunkId").asText(),true);
                if (current==null || !current.contentHash().equals(attributes.path("contentHash").asText()) ||
                        !current.source().equals(attributes.path("source").asText()) ||
                        !current.scope().equals(attributes.path("scope").asText()) ||
                        !java.util.Objects.equals(current.documentId(),attributes.hasNonNull("documentId")?attributes.get("documentId").longValue():null) ||
                        !java.util.Objects.equals(current.version(),attributes.hasNonNull("version")?attributes.get("version").intValue():null) ||
                        !java.util.Objects.equals(current.knowledgeBaseId(),attributes.hasNonNull("knowledgeBaseId")?attributes.get("knowledgeBaseId").longValue():null)) {
                    throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT);
                }
            } else if (record.type()==EvidenceType.QUESTION) {
                var rows=jdbc.queryForList("SELECT title,LEFT(content,2000) AS content FROM question WHERE id=? FOR SHARE",Long.parseLong(record.sourceId()));
                if (rows.isEmpty() || !(rows.getFirst().get("title")+"\n"+java.util.Objects.toString(rows.getFirst().get("content"),"" )).equals(record.content())) {
                    throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT);
                }
            } else {
                if (jdbc.query("SELECT id FROM interview_session WHERE id=? AND user_id=? AND status='COMPLETED' FOR SHARE",
                        (rs,n)->rs.getLong(1),attributes.path("sessionId").asLong(),registry.userId()).isEmpty()) {
                    throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT);
                }
                if (attributes.hasNonNull("turnId")) {
                    var rows=jdbc.queryForList("""
                            SELECT score,correct,skipped,round_number,LEFT(question,300) AS question,
                                (SELECT jsonb_agg(LEFT(value,300))::text FROM jsonb_array_elements_text(missing_points)
                                    WITH ORDINALITY AS point(value,position) WHERE position<=5) AS missing
                            FROM interview_turn WHERE id=? AND session_id=? AND status='COMPLETED' FOR SHARE
                            """,
                            attributes.path("turnId").asLong(),attributes.path("sessionId").asLong());
                    if (rows.isEmpty()) { throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT); }
                    for (String field:List.of("score","correct","skipped")) {
                        if (!java.util.Objects.equals(java.util.Objects.toString(rows.getFirst().get(field),null),
                                attributes.hasNonNull(field)?attributes.get(field).asText():null)) { throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT); }
                    }
                    if (!java.util.Objects.equals(java.util.Objects.toString(rows.getFirst().get("round_number"),null),attributes.path("round").asText()) ||
                            !(rows.getFirst().get("question")+"\n"+java.util.Objects.toString(rows.getFirst().get("missing"),"")).equals(record.content())) {
                        throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT);
                    }
                } else if (record.type()==EvidenceType.WEAKNESS) {
                    var summaries=jdbc.query("SELECT LEFT(weaknesses,300) FROM interview_evaluation WHERE session_id=? FOR SHARE",
                            (rs,n)->rs.getString(1),attributes.path("sessionId").asLong());
                    if (summaries.isEmpty() || !java.util.Objects.equals(summaries.getFirst(),record.content())) {
                        throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT);
                    }
                }
            }
        }
    }

    public void message(UUID conversation, UUID execution, String role, String text) {
        if (text==null || text.length()>24000) { throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED); }
        jdbc.update("INSERT INTO agent_message(conversation_id,execution_id,role,content) VALUES (?,?,?,?)",
                conversation, execution, role, text);
    }

    public long savePlan(long userId, UUID conversation, UUID execution, AgentModels.Answer answer) {
        return jdbc.queryForObject("""
                INSERT INTO study_plan(user_id,conversation_id,execution_id,content) VALUES (?,?,?,?::jsonb)
                RETURNING id
                """, Long.class, userId, conversation, execution, json(answer));
    }

    public static String clip(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0,max);
    }
}
