package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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

@EnabledIfEnvironmentVariable(named="V89_LIVE_MEMORY_URL",matches=".+")
class V89MemoryLiveTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Test void realSummaryRecoversEarlyGoalAfterRedisEviction() throws Exception {
        String databasePassword=System.getenv("V8_TEST_JDBC_PASSWORD");
        if (databasePassword==null) {
            var local=new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
            local.setResources(new org.springframework.core.io.FileSystemResource(".local-secrets.yml"));
            databasePassword=Objects.requireNonNull(local.getObject()).getProperty("POSTGRES_PASSWORD");
        }
        var source=new DriverManagerDataSource(Objects.requireNonNull(System.getenv("V8_TEST_JDBC_URL")),"postgres",
                Objects.requireNonNull(databasePassword));
        var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var connection=new LettuceConnectionFactory("localhost",Integer.parseInt(System.getenv().getOrDefault("V89_TEST_REDIS_PORT","6379")));connection.afterPropertiesSet();
        var redis=new StringRedisTemplate(connection);
        String username="v89_"+UUID.randomUUID().toString().replace("-","").substring(0,12),password=UUID.randomUUID().toString();
        long user=jdbc.queryForObject("INSERT INTO app_user(username,password) VALUES(?,?) RETURNING id",Long.class,username,new BCryptPasswordEncoder().encode(password));
        UUID conversation=UUID.randomUUID();String token=null,key="agent:memory:"+user+":"+conversation;
        try {
            jdbc.update("INSERT INTO agent_conversation(id,user_id,kind) VALUES(?,?,'CHAT')",conversation,user);
            tx.executeWithoutResult(ignored->{
                for(int round=1;round<=9;round++) {
                    UUID execution=UUID.randomUUID();
                    jdbc.update("INSERT INTO agent_execution(id,user_id,conversation_id,kind,state,prompt_version,committed_at) VALUES(?,?,?,'CHAT','FINISHED',1,clock_timestamp())",execution,user,conversation);
                    String question=round==1?"请记住：我给学习计划起名为松树计划，学习目标是Java后端实习，每天安排45分钟。":"我们继续讨论Spring事务，这是第"+round+"轮。";
                    jdbc.update("INSERT INTO agent_message(conversation_id,execution_id,role,content) VALUES(?,?,'USER',?),(?,?,'ASSISTANT',?)",
                            conversation,execution,question,conversation,execution,"{\"message\":\"已记录本轮讨论内容。\",\"weaknesses\":[],\"plan\":[],\"sources\":[]}");
                }
            });
            token=send("POST","/user/login",null,Map.of("username",username,"password",password)).path("data").path("token").asText();
            assertFalse(token.isBlank());
            send("POST","/agent/chat",token,Map.of("conversationId",conversation,"message","用一句话说明Spring事务传播是什么，无需工具，其他数组为空。"));
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();List<String> summaries;
            do {
                summaries=jdbc.query("SELECT summary FROM agent_memory_summary WHERE conversation_id=?",(rs,n)->rs.getString(1),conversation);
                if (!summaries.isEmpty()) { break; }Thread.sleep(200);
            } while(System.nanoTime()<deadline);
            assertFalse(summaries.isEmpty(),"Background summary was not persisted");
            assertTrue(summaries.getFirst().contains("松树"),summaries.toString());
            assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE conversation_id=? AND id<=(SELECT last_message_id FROM agent_memory_summary WHERE conversation_id=?)",Integer.class,conversation,conversation));
            redis.delete(key);
            JsonNode answer=send("POST","/agent/chat",token,Map.of("conversationId",conversation,"message","我最早给学习计划起的名字是什么？每天安排多少分钟？仅根据对话历史回答，无需工具，其他数组为空。"));
            String restored=answer.path("data").path("result").path("message").asText();
            assertTrue(restored.contains("松树"),restored);assertTrue(restored.contains("45") || restored.contains("四十五"),restored);
            assertEquals(22,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message WHERE conversation_id=?",Integer.class,conversation));
            send("DELETE","/agent/conversations/"+conversation,token,null);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_summary WHERE conversation_id=?",Integer.class,conversation));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
            System.out.println("V8.9 live memory passed: real provider summary, early goal recovery after Redis eviction, complete history and deletion");
        } finally {
            if (token!=null) { send("POST","/user/logout",token,null); }
            tx.executeWithoutResult(ignored->{
                jdbc.update("DELETE FROM study_plan WHERE user_id=?",user);
                jdbc.update("DELETE FROM agent_message WHERE conversation_id=?",conversation);
                jdbc.update("DELETE FROM agent_tool_call WHERE execution_id IN (SELECT id FROM agent_execution WHERE user_id=?)",user);
                jdbc.update("DELETE FROM agent_execution WHERE user_id=?",user);
                jdbc.update("DELETE FROM agent_conversation WHERE id=? AND user_id=?",conversation,user);
                jdbc.update("DELETE FROM agent_memory_cache_cleanup WHERE conversation_id=? AND user_id=?",conversation,user);
                jdbc.update("DELETE FROM user_learning_profile WHERE user_id=?",user);
                jdbc.update("DELETE FROM app_user WHERE id=? AND username=?",user,username);
            });
            redis.delete(key);connection.destroy();
        }
    }
    private JsonNode send(String method,String path,String token,Object data) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(System.getenv("V89_LIVE_MEMORY_URL")+path)).timeout(Duration.ofSeconds(90));
        if (token!=null) { request.header("Authorization","Bearer "+token); }
        if (data!=null) { request.header("Content-Type","application/json"); }
        request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body());
    }
}
