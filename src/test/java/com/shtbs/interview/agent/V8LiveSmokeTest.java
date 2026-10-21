package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="V8_LIVE_SMOKE_URL",matches=".+")
class V8LiveSmokeTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String base=System.getenv("V8_LIVE_SMOKE_URL");

    @Test void realProviderToolsMemoryPlanAndInterview() throws Exception {
        var local=new YamlPropertiesFactoryBean();local.setResources(new FileSystemResource(".local-secrets.yml"));
        var dataSource=new DriverManagerDataSource(System.getenv().getOrDefault("V8_TEST_JDBC_URL","jdbc:postgresql://localhost:15432/java_rag"),
                "postgres",System.getenv().getOrDefault("V8_TEST_JDBC_PASSWORD",local.getObject().getProperty("POSTGRES_PASSWORD")));
        var jdbc=new JdbcTemplate(dataSource);var transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String username="v8p_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String password=UUID.randomUUID().toString();
        long user=jdbc.queryForObject("INSERT INTO app_user(username,password) VALUES(?,?) RETURNING id",Long.class,username,new BCryptPasswordEncoder().encode(password));
        String token=null;
        try {
            token=body(send("POST","/user/login",null,Map.of("username",username,"password",password))).get("data").get("token").asText();
            assertEquals(403,send("GET","/user",token,null).statusCode());
            assertEquals(400,send("POST","/interview/99999/answer",token,Map.of("answer"," ")).statusCode());
            assertEquals(200,send("PUT","/agent/profile",token,Map.of("target","Java后端实习","level","学生","dailyMinutes",120)).statusCode());
            var chat=send("POST","/agent/chat",token,Map.of("message","请调用getUserProfile读取我的求职目标，并简要说明。weaknesses、plan、sources用空数组。"));
            assertEquals(200,chat.statusCode(),chat.body());
            JsonNode first=body(chat).get("data");String conversation=first.get("conversationId").asText();
            JsonNode execution=body(send("GET","/agent/executions/"+first.get("executionId").asText(),token,null)).get("data");
            assertFinished(execution);
            assertTrue(execution.get("tools").toString().contains("getUserProfile"));
            var remembered=send("POST","/agent/chat",token,Map.of("conversationId",conversation,"message","我刚才查询的求职目标是什么？简要回答，其他数组为空。"));
            assertEquals(200,remembered.statusCode(),remembered.body());
            assertTrue(body(remembered).get("data").get("result").get("message").asText().contains("Java"));
            long sample=jdbc.queryForObject("INSERT INTO interview_session(user_id,category,difficulty,status,end_time) VALUES(?,'Java','EASY','COMPLETED',CURRENT_TIMESTAMP) RETURNING id",Long.class,user);
            jdbc.update("INSERT INTO interview_evaluation(session_id,score,strengths,weaknesses,suggestions) VALUES(?,60,'[\"理解数组和链表\"]','[\"HashMap扩容机制\"]','[\"复习HashMap resize\"]')",sample);
            jdbc.update("INSERT INTO interview_turn(session_id,request_id,round_number,answer,question,skipped,status,score,correct,complete,missing_points) VALUES(?,?,1,'Only doubles capacity','HashMap resize',false,'COMPLETED',35,false,false,'[\"rehashing\"]')",sample,UUID.randomUUID());
            long skippedSample=jdbc.queryForObject("INSERT INTO interview_session(user_id,category,difficulty,status,end_time) VALUES(?,'Java','EASY','COMPLETED',CURRENT_TIMESTAMP) RETURNING id",Long.class,user);
            jdbc.update("INSERT INTO interview_turn(session_id,request_id,round_number,answer,question,skipped,status,score,correct,complete) VALUES(?,?,1,'','Spring transaction propagation',true,'COMPLETED',0,false,false)",skippedSample,UUID.randomUUID());
            long highSample=jdbc.queryForObject("INSERT INTO interview_session(user_id,category,difficulty,status,end_time) VALUES(?,'Java','EASY','COMPLETED',CURRENT_TIMESTAMP) RETURNING id",Long.class,user);
            jdbc.update("INSERT INTO interview_evaluation(session_id,score,strengths,weaknesses,suggestions) VALUES(?,90,'[\"Java basics\"]','[\"Spring transactions\"]','[]')",highSample);
            jdbc.update("INSERT INTO interview_turn(session_id,request_id,round_number,answer,question,skipped,status,score,correct,complete,missing_points) VALUES(?,?,1,'Unsure','Spring transaction propagation',false,'COMPLETED',40,false,false,'[\"REQUIRES_NEW\"]')",highSample,UUID.randomUUID());
            var plan=send("POST","/agent/study-plans",token,Map.of("message","根据最近三次已完成的面试和学习档案制定7天Java提升计划，每天最多120分钟。题目ID仅引用工具结果；没有结果则用空数组。"));
            assertEquals(200,plan.statusCode(),plan.body());
            JsonNode planData=body(plan).get("data");assertEquals(7,planData.get("result").get("plan").size());
            assertTrue(planData.hasNonNull("conversationId"));assertTrue(planData.hasNonNull("executionId"));assertTrue(planData.hasNonNull("studyPlanId"));
            Set<Integer> days=new HashSet<>();
            for(JsonNode day:planData.path("result").path("plan")) {
                assertTrue(day.path("minutes").asInt()>0 && day.path("minutes").asInt()<=120);days.add(day.path("day").asInt());
                for(JsonNode question:day.path("questionIds")) assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM question WHERE id=?",Integer.class,question.asLong()));
            }
            assertEquals(Set.of(1,2,3,4,5,6,7),days);
            for(JsonNode weakness:planData.path("result").path("weaknesses"))
                for(JsonNode sessionId:weakness.path("sessionIds")) assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM interview_session WHERE id=? AND user_id=?",Integer.class,sessionId.asLong(),user));
            JsonNode planExecution=body(send("GET","/agent/executions/"+planData.get("executionId").asText(),token,null)).get("data");
            assertFinished(planExecution);
            Set<String> evidence=new HashSet<>();
            for (JsonNode tool:planExecution.get("tools")) {
                if ("SUCCEEDED".equals(tool.path("status").asText())) { evidence.add(tool.path("tool_name").asText()); }
            }
            assertTrue(evidence.containsAll(Set.of("getUserProfile","getInterviewHistory","analyzeWeaknesses")));
            assertGrounded(planData.get("result"),planExecution);
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/v87-evidence"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/v87-evidence/live-study-plan.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("response",planData,"execution",planExecution)));
            assertEquals(200,send("GET","/agent/study-plans/"+planData.get("studyPlanId").asLong(),token,null).statusCode());
            var started=send("POST","/interview",token,Map.of("category","Java","difficulty","EASY"));
            assertEquals(200,started.statusCode(),started.body());long session=body(started).get("data").get("sessionId").asLong();
            assertFinished(body(send("GET","/agent/executions/"+body(started).get("data").get("executionId").asText(),token,null)).get("data"));
            var firstSkip=send("POST","/interview/"+session+"/answer",token,Map.of("skip",true,"expectedRound",1,"requestId",UUID.randomUUID()));
            assertEquals(200,firstSkip.statusCode(),firstSkip.body());
            assertFinished(body(send("GET","/agent/executions/"+body(firstSkip).get("data").get("executionId").asText(),token,null)).get("data"));
            var skipped=send("POST","/interview/"+session+"/answer",token,Map.of("skip",true,"expectedRound",2,"requestId",UUID.randomUUID()));
            assertEquals("REVIEW",body(skipped).get("data").get("status").asText());
            assertEquals(2,body(send("GET","/interview/"+session+"/review",token,null)).get("data").size());
            assertEquals(0,body(send("POST","/interview/"+session+"/finish",token,null)).get("data").get("score").asInt());
            System.out.println("Live V8.8 verification passed: provider tools, memory, 7-day plan, permissions, committed states and traceable evidence");
        } finally {
            if (token!=null) {
                List<UUID> conversations=jdbc.query("SELECT id FROM agent_conversation WHERE user_id=?",(rs,row)->rs.getObject(1,UUID.class),user);
                for(UUID id:conversations){send("DELETE","/agent/conversations/"+id,token,null);}
                send("POST","/user/logout",token,null);
            }
            transactions.executeWithoutResult(ignored -> {
                assertEquals(username,jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,user));
                jdbc.update("DELETE FROM study_plan WHERE user_id=?",user);
                jdbc.update("DELETE FROM agent_message WHERE conversation_id IN(SELECT id FROM agent_conversation WHERE user_id=?)",user);
                jdbc.update("DELETE FROM agent_tool_call WHERE execution_id IN(SELECT id FROM agent_execution WHERE user_id=?)",user);
                jdbc.update("DELETE FROM interview_evaluation WHERE session_id IN(SELECT id FROM interview_session WHERE user_id=?)",user);
                jdbc.update("DELETE FROM agent_execution WHERE user_id=?",user);
                jdbc.update("DELETE FROM agent_conversation WHERE user_id=?",user);
                jdbc.update("DELETE FROM interview_turn WHERE session_id IN(SELECT id FROM interview_session WHERE user_id=?)",user);
                jdbc.update("DELETE FROM interview_message WHERE session_id IN(SELECT id FROM interview_session WHERE user_id=?)",user);
                jdbc.update("DELETE FROM interview_session WHERE user_id=?",user);
                jdbc.update("DELETE FROM user_learning_profile WHERE user_id=?",user);
                jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",user,username);
            });
        }
    }

    private HttpResponse<String> send(String method,String path,String token,Object data) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(90));
        if(token!=null){request.header("Authorization","Bearer "+token);}
        if(data!=null){request.header("Content-Type","application/json");}
        request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response) throws Exception {return json.readTree(response.body());}
    private void assertGrounded(JsonNode answer,JsonNode execution) throws Exception {
        Map<String,JsonNode> snapshots=new HashMap<>();
        for (JsonNode row:execution.path("evidence")) { snapshots.put(row.path("evidence_id").asText(),json.readTree(row.path("snapshot").asText())); }
        assertFalse(snapshots.isEmpty());
        for (JsonNode weakness:answer.path("weaknesses")) {
            assertFalse(weakness.path("evidenceIds").isEmpty());
            assertEquals(weakness.path("evidenceIds").size(),weakness.path("facts").size());
            for (JsonNode fact:weakness.path("facts")) {
                JsonNode record=snapshots.get(fact.path("evidenceId").asText());assertNotNull(record);
                assertEquals("WEAKNESS",record.path("type").asText());
                assertTrue(record.path("content").asText().contains(fact.path("quote").asText()));
                for (String field:List.of("sessionId","turnId","round","score","correct","skipped")) {
                    assertEquals(record.path("attributes").path(field).asText(""),fact.path(field).asText(""));
                }
            }
        }
        for (JsonNode day:answer.path("plan")) {
            assertEquals(day.path("questionIds").size(),day.path("recommendations").size());
            for (JsonNode recommendation:day.path("recommendations")) {
                JsonNode record=snapshots.get(recommendation.path("evidenceId").asText());assertNotNull(record);
                assertEquals("QUESTION",record.path("type").asText());
                assertEquals(record.path("attributes").path("questionId").asLong(),recommendation.path("questionId").asLong());
            }
        }
        for (JsonNode citation:answer.path("citations")) {
            JsonNode record=snapshots.get(citation.path("evidenceId").asText());assertNotNull(record);
            assertEquals("KNOWLEDGE",record.path("type").asText());
            assertEquals(record.path("attributes").path("chunkId"),citation.path("chunkId"));
            assertEquals(record.path("attributes").path("contentHash"),citation.path("contentHash"));
        }
    }
    private void assertFinished(JsonNode execution) {
        assertEquals("FINISHED",execution.path("state").asText());
        assertTrue(execution.hasNonNull("committed_at"));
        assertTrue(execution.path("model_calls").asInt()>0 && execution.path("model_calls").asInt()<=6);
        assertTrue(execution.path("tool_calls").asInt()<=12);
    }
}
