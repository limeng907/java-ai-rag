package com.shtbs.interview.service.Impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.agent.*;
import com.shtbs.interview.ai.AnswerAnalysis;
import com.shtbs.interview.ai.InterviewEvaluationResult;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.dto.InterviewAnswerDTO;
import com.shtbs.interview.dto.InterviewStartDTO;
import com.shtbs.interview.enums.InterviewStatus;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.knowledge.KnowledgeBaseManagementService;
import com.shtbs.interview.service.InterviewService;
import com.shtbs.interview.vo.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class InterviewServiceImpl implements InterviewService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InterviewServiceImpl.class);
    private record Session(long id,long userId,String category,String difficulty,String status,
                           int rounds,int skips,int revision,boolean busy,List<Long> bases) {}
    private record Reservation(Session session,UUID lease,InterviewAnswerVO cached) {}
    private final JdbcTemplate jdbc;
    private final AgentRuntime agent;
    private final AgentRepository repository;
    private final KnowledgeBaseManagementService bases;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public InterviewServiceImpl(JdbcTemplate jdbc,AgentRuntime agent,AgentRepository repository,
                                KnowledgeBaseManagementService bases,ObjectMapper json,
                                PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.agent=agent;this.repository=repository;this.bases=bases;this.json=json;
        this.transactions=new TransactionTemplate(manager);
    }

    @Override
    public InterviewStartVO startInterview(Long userId,InterviewStartDTO dto) {
        List<Long> selected=dto.getKnowledgeBaseIds()==null?List.of():dto.getKnowledgeBaseIds().stream().distinct().toList();
        selected.forEach(id->bases.requireOwned(userId,id));
        var run=agent.run(userId,null,null,"INTERVIEW","开始一场面试。方向："+dto.getCategory()+
                "，难度："+dto.getDifficulty()+"。生成第一题，nextQuestion为题目；尚无回答，score=0，correct=false，complete=false，needFollowUp=false，missingPoints=[]。",
                selected,List.of(),AnswerAnalysis.class);
        try {
            repository.beginCommit(run.executionId());
            return transactions.execute(ignored -> {
                repository.lockCommit(run.executionId());
                long id=jdbc.queryForObject("""
                        INSERT INTO interview_session(user_id,category,difficulty,status,knowledge_base_ids)
                        VALUES (?,?,?,'IN_PROGRESS',?::jsonb) RETURNING id
                        """,Long.class,userId,dto.getCategory(),dto.getDifficulty().name(),repository.json(selected));
                insertMessage(id,"INTERVIEWER",run.value().getNextQuestion());
                jdbc.update("UPDATE agent_execution SET session_id=? WHERE id=?",id,run.executionId());
                repository.completeExecution(run.executionId());
                InterviewStartVO response=new InterviewStartVO();response.setSessionId(id);response.setQuestion(run.value().getNextQuestion());response.setExecutionId(run.executionId());return response;
            });
        } catch (RuntimeException ex) {
            repository.failExecution(run.executionId(),ex);throw ex;
        }
    }

    @Override
    public InterviewAnswerVO answer(Long sessionId,InterviewAnswerDTO dto) {
        if (!dto.isAnswerValid()) { throw new BusinessException(ErrorCode.PARAM_ERROR); }
        long userId=UserContext.getUserId();
        UUID request=dto.getRequestId()==null?UUID.randomUUID():dto.getRequestId();
        Reservation reserved=transactions.execute(ignored->reserve(userId,sessionId,request,dto));
        if (reserved.cached()!=null) { return reserved.cached(); }
        Session session=reserved.session();
        InterviewPolicy.Next next=InterviewPolicy.afterAnswer(session.rounds(),session.skips(),dto.isSkip());
        UUID executionId=null;
        try {
            AnswerAnalysis analysis;
            if (dto.isSkip() && next.status()==InterviewStatus.REVIEW) {
                analysis=skipped(null);
            } else {
                var run=agent.run(userId,null,sessionId,"INTERVIEW","方向："+session.category()+"；难度："+session.difficulty()+
                        "。当前第"+next.rounds()+"轮。面试历史：\n"+history(sessionId)+"\n候选人本次回答：\n"+
                        (dto.isSkip()?"[跳过，未回答]":dto.getAnswer()),session.bases(),List.of(),AnswerAnalysis.class);
                analysis=dto.isSkip()?skipped(run.value().getNextQuestion()):run.value();
                executionId=run.executionId();
            }
            InterviewAnswerVO result=new InterviewAnswerVO();result.setSessionId(sessionId);result.setRequestId(request);
            result.setExecutionId(executionId);
            result.setCompletedRounds(next.rounds());result.setStatus(next.status());
            result.setNextQuestion(next.status()==InterviewStatus.IN_PROGRESS?analysis.getNextQuestion():null);
            UUID completedExecution=executionId;
            if (completedExecution!=null) { repository.beginCommit(completedExecution); }
            transactions.executeWithoutResult(ignored -> {
                if (completedExecution!=null) { repository.lockCommit(completedExecution); }
                int changed=jdbc.update("""
                        UPDATE interview_session SET turn_count=?,skip_streak=?,status=?,revision=revision+1,
                            busy_request=NULL,busy_until=NULL
                        WHERE id=? AND user_id=? AND status='IN_PROGRESS' AND revision=? AND busy_request=?
                            AND busy_until>CURRENT_TIMESTAMP
                        """,next.rounds(),next.skipStreak(),next.status().name(),sessionId,userId,session.revision(),reserved.lease());
                if (changed!=1) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
                insertMessage(sessionId,"CANDIDATE",dto.isSkip()?"[未回答]":dto.getAnswer());
                if (result.getNextQuestion()!=null) { insertMessage(sessionId,"INTERVIEWER",result.getNextQuestion()); }
                if (jdbc.update("""
                        UPDATE interview_turn SET status='COMPLETED',score=?,correct=?,complete=?,need_follow_up=?,
                            missing_points=?::jsonb,result=?::jsonb,completed_at=CURRENT_TIMESTAMP
                        WHERE session_id=? AND request_id=? AND status='PROCESSING'
                        """,analysis.getScore(),analysis.getCorrect(),analysis.getComplete(),analysis.getNeedFollowUp(),
                        repository.json(analysis.getMissingPoints()),repository.json(result),sessionId,request)!=1) {
                    throw new BusinessException(ErrorCode.REQUEST_CONFLICT);
                }
                if (completedExecution!=null) { repository.completeExecution(completedExecution); }
            });
            return result;
        } catch (RuntimeException ex) {
            repository.failExecution(executionId,ex);
            try {
                transactions.executeWithoutResult(ignored -> {
                    int changed=jdbc.update("UPDATE interview_session SET busy_request=NULL,busy_until=NULL WHERE id=? AND busy_request=?",
                            sessionId,reserved.lease());
                    if (changed==1) { jdbc.update("UPDATE interview_turn SET status='FAILED' WHERE session_id=? AND request_id=? AND status='PROCESSING'",sessionId,request); }
                });
            } catch (RuntimeException cleanupFailure) { log.warn("Interview reservation cleanup failed for {}; lease will expire",sessionId); }
            throw ex;
        }
    }

    private Reservation reserve(long userId,long sessionId,UUID request,InterviewAnswerDTO dto) {
        Session session=owned(userId,sessionId,true);
        List<Map<String,Object>> prior=jdbc.queryForList("SELECT status,answer,skipped,round_number,result::text AS result FROM interview_turn WHERE session_id=? AND request_id=?",sessionId,request);
        String answer=dto.isSkip()?"":dto.getAnswer();
        if (!prior.isEmpty()) {
            Map<String,Object> previous=prior.getFirst();
            if (!answer.equals(previous.get("answer")) || !Boolean.valueOf(dto.isSkip()).equals(previous.get("skipped"))) {
                throw new BusinessException(ErrorCode.REQUEST_CONFLICT);
            }
            if ("COMPLETED".equals(previous.get("status"))) {
                try { return new Reservation(session,null,json.readValue((String)previous.get("result"),InterviewAnswerVO.class)); }
                catch (Exception ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }
            }
            if (((Number)previous.get("round_number")).intValue()!=session.rounds()+1) {
                throw new BusinessException(ErrorCode.REQUEST_CONFLICT);
            }
        }
        if (!"IN_PROGRESS".equals(session.status()) || session.rounds()>=7) { throw new BusinessException(ErrorCode.INTERVIEW_NOT_IN_PROGRESS); }
        if (dto.getExpectedRound()!=null && dto.getExpectedRound()!=session.rounds()+1) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
        UUID lease=UUID.randomUUID();
        if (jdbc.update("""
                UPDATE interview_session SET busy_request=?,busy_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes'
                WHERE id=? AND (busy_request IS NULL OR busy_until<CURRENT_TIMESTAMP)
                """,lease,sessionId)!=1) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
        jdbc.update("UPDATE interview_turn SET status='FAILED' WHERE session_id=? AND status='PROCESSING'",sessionId);
        String question=jdbc.query("SELECT content FROM interview_message WHERE session_id=? AND role='INTERVIEWER' ORDER BY id DESC LIMIT 1",
                (rs,row)->rs.getString(1),sessionId).stream().findFirst()
                .orElseThrow(()->new BusinessException(ErrorCode.INTERVIEW_NOT_IN_PROGRESS));
        jdbc.update("""
                INSERT INTO interview_turn(session_id,request_id,round_number,answer,skipped,question,status)
                VALUES (?,?,?,?,?,?,'PROCESSING') ON CONFLICT(session_id,request_id)
                DO UPDATE SET status='PROCESSING',round_number=EXCLUDED.round_number
                """,sessionId,request,session.rounds()+1,answer,dto.isSkip(),question);
        return new Reservation(session,lease,null);
    }

    @Override
    public InterviewFinishVO finishInterview(Long sessionId) {
        long userId=UserContext.getUserId();UUID token=UUID.randomUUID();
        Session session=transactions.execute(ignored -> {
            Session current=owned(userId,sessionId,true);
            if ("COMPLETED".equals(current.status())) { return current; }
            if (current.busy()) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
            if (!("IN_PROGRESS".equals(current.status()) || "REVIEW".equals(current.status())) || current.rounds()==0) {
                throw new BusinessException(ErrorCode.INTERVIEW_NOT_IN_PROGRESS);
            }
            if (jdbc.update("""
                    UPDATE interview_session SET busy_request=?,busy_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes'
                    WHERE id=? AND (busy_request IS NULL OR busy_until<CURRENT_TIMESTAMP)
                    """,token,sessionId)!=1) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
            return current;
        });
        if ("COMPLETED".equals(session.status())) { return evaluation(sessionId); }
        UUID executionId=null;
        try {
            boolean answered=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM interview_message WHERE session_id=? AND role='CANDIDATE' AND content<>'[未回答]')",Boolean.class,sessionId));
            var run=answered?agent.run(userId,null,sessionId,"EVALUATION",
                    "方向："+session.category()+"；难度："+session.difficulty()+"。面试记录：\n"+history(sessionId),
                    session.bases(),List.of(),InterviewEvaluationResult.class):null;
            executionId=run==null?null:run.executionId();
            InterviewEvaluationResult result=run==null?unansweredEvaluation():run.value();
            UUID completedExecution=executionId;
            if (completedExecution!=null) { repository.beginCommit(completedExecution); }
            transactions.executeWithoutResult(ignored -> {
                if (completedExecution!=null) { repository.lockCommit(completedExecution); }
                if (jdbc.update("""
                        UPDATE interview_session SET status='COMPLETED',end_time=CURRENT_TIMESTAMP,
                            revision=revision+1,busy_request=NULL,busy_until=NULL
                        WHERE id=? AND user_id=? AND revision=? AND busy_request=? AND busy_until>CURRENT_TIMESTAMP
                            AND status IN ('IN_PROGRESS','REVIEW')
                        """,sessionId,userId,session.revision(),token)!=1) { throw new BusinessException(ErrorCode.REQUEST_CONFLICT); }
                jdbc.update("""
                        INSERT INTO interview_evaluation(session_id,score,strengths,weaknesses,suggestions,execution_id)
                        VALUES (?,?,?,?,?,?)
                        """,sessionId,result.getScore(),repository.json(result.getStrengths()),repository.json(result.getWeaknesses()),repository.json(result.getSuggestions()),completedExecution);
                if (completedExecution!=null) { repository.completeExecution(completedExecution); }
            });
            return evaluation(sessionId);
        } catch (RuntimeException ex) {
            repository.failExecution(executionId,ex);throw ex;
        } finally {
            try { jdbc.update("UPDATE interview_session SET busy_request=NULL,busy_until=NULL WHERE id=? AND busy_request=?",sessionId,token); }
            catch (RuntimeException cleanupFailure) { log.warn("Interview lease release failed for {}; it will expire",sessionId); }
        }
    }

    private Session owned(long userId,long id,boolean lock) {
        List<Session> rows=jdbc.query("SELECT *, (busy_request IS NOT NULL AND busy_until>CURRENT_TIMESTAMP) AS busy FROM interview_session WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),
                (rs,row)->new Session(rs.getLong("id"),rs.getLong("user_id"),rs.getString("category"),rs.getString("difficulty"),
                        rs.getString("status"),rs.getInt("turn_count"),rs.getInt("skip_streak"),rs.getInt("revision"),rs.getBoolean("busy"),
                        readBases(rs.getString("knowledge_base_ids"))),id,userId);
        if (rows.isEmpty()) { throw new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND); }
        return rows.getFirst();
    }
    private List<Long> readBases(String text) {
        try { return json.readValue(text,new TypeReference<List<Long>>(){}); }
        catch (Exception ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }
    }
    private void insertMessage(long session,String role,String content) {
        jdbc.update("INSERT INTO interview_message(session_id,role,content) VALUES (?,?,?)",session,role,content);
    }
    private String history(long session) {
        List<String> messages=jdbc.query("SELECT role,content FROM interview_message WHERE session_id=? ORDER BY id DESC LIMIT 16",
                (rs,row)->rs.getString(1)+"："+rs.getString(2),session);
        Collections.reverse(messages);return String.join("\n",messages);
    }
    private AnswerAnalysis skipped(String question) {
        AnswerAnalysis a=new AnswerAnalysis();a.setScore(0);a.setCorrect(false);a.setComplete(false);a.setNeedFollowUp(false);
        a.setMissingPoints(List.of("未作答，缺少能力评价依据"));a.setNextQuestion(question);return a;
    }
    private InterviewEvaluationResult unansweredEvaluation() {
        InterviewEvaluationResult result=new InterviewEvaluationResult();result.setScore(0);result.setStrengths(List.of());
        result.setWeaknesses(List.of("未完成有效作答，无法据此判断具体技术薄弱点"));
        result.setSuggestions(List.of("复习面试题后重新开始面试；用完整回答提供技术、表达和知识完整性的评价依据"));return result;
    }
    private InterviewFinishVO evaluation(long session) {
        return jdbc.query("SELECT * FROM interview_evaluation WHERE session_id=?",(rs,row)->{
            InterviewFinishVO vo=new InterviewFinishVO();vo.setSessionId(session);vo.setScore(rs.getInt("score"));
            UUID execution=rs.getObject("execution_id",UUID.class);
            vo.setExecutionId(execution!=null?execution:jdbc.query("SELECT id FROM agent_execution WHERE session_id=? AND kind='EVALUATION' AND state='FINISHED' ORDER BY created_at DESC LIMIT 1",
                    (rowExecution,rowIndex)->rowExecution.getObject(1,UUID.class),session).stream().findFirst().orElse(null));
            try {
                vo.setStrengths(json.readValue(rs.getString("strengths"),new TypeReference<List<String>>(){}));
                vo.setWeaknesses(json.readValue(rs.getString("weaknesses"),new TypeReference<List<String>>(){}));
                vo.setSuggestions(json.readValue(rs.getString("suggestions"),new TypeReference<List<String>>(){}));
            } catch (Exception ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }
            return vo;
        },session).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.SYSTEM_ERROR));
    }

    public List<Map<String,Object>> list(long userId,int page,int size) {
        pagination(page,size);
        return jdbc.queryForList("SELECT id,category,difficulty,status,turn_count,start_time,end_time FROM interview_session WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                userId,size,((long)page-1)*size);
    }
    public List<Map<String,Object>> review(long userId,long session) {
        Session current=owned(userId,session,false);
        if (!("REVIEW".equals(current.status()) || "COMPLETED".equals(current.status()))) { throw new BusinessException(ErrorCode.FORBIDDEN); }
        return jdbc.queryForList("SELECT round_number,question,answer,skipped,score,correct,complete,missing_points::text AS missing_points FROM interview_turn WHERE session_id=? AND status='COMPLETED' ORDER BY round_number",session);
    }
    public List<Map<String,Object>> wrong(long userId,int page,int size) {
        pagination(page,size);
        return jdbc.queryForList("""
                SELECT s.id AS session_id,s.category,t.round_number,t.question,t.answer,t.skipped,t.score,t.missing_points::text AS missing_points
                FROM interview_turn t JOIN interview_session s ON s.id=t.session_id
                WHERE s.user_id=? AND s.status IN ('REVIEW','COMPLETED') AND t.status='COMPLETED'
                    AND (t.skipped OR NOT t.correct OR t.score<60)
                ORDER BY t.id DESC LIMIT ? OFFSET ?
                """,userId,size,((long)page-1)*size);
    }
    private void pagination(int page,int size) {
        if (page<1 || page>1000000 || size<1 || size>100) { throw new BusinessException(ErrorCode.PARAM_ERROR); }
    }
}
