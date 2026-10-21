package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.controller.KnowledgeDocumentController;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.knowledge.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real S3, Redis Stream, splitter, Ollama and pgvector. Chat decisions are scripted for reproducibility. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="STABILIZATION_RAG",matches="true")
class RagLifecycleTest {
    private String url,password,schema,bucket;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PgVectorStore vectors;
    private KnowledgeRepository knowledge;
    private KnowledgeBaseManagementService bases;
    private KnowledgeDocumentService documents;
    private S3Client s3;
    private S3ObjectStorage storage;
    private LettuceConnectionFactory redisConnection;
    private StringRedisTemplate redis;
    private DocumentTaskQueue queue;
    private DocumentWorker worker;
    private AgentRepository repository;
    private AgentRuntime runtime;
    private AgentService agent;
    private ChatModel model;
    private ValidatorFactory validation;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private long base;
    private final List<Map<String,Object>> observations=new ArrayList<>();

    @BeforeAll void infrastructure() throws Exception {
        url=Objects.requireNonNull(System.getenv("V8_TEST_JDBC_URL"));password=Objects.requireNonNull(System.getenv("V8_TEST_JDBC_PASSWORD"));
        schema="stabilization_rag_"+UUID.randomUUID().toString().replace("-","");bucket=schema.replace('_','-');
        try (var c=DriverManager.getConnection(url,"postgres",password)) {
            c.createStatement().execute("CREATE SCHEMA "+schema);
            c.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        }
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public","postgres",password);
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        try(var c=ds.getConnection();var statement=c.createStatement()) {
            for(String script:List.of("001_business_schema.sql","002_document_lifecycle.sql","003_agent_platform.sql","004_agent_runtime.sql","005_agent_grounding.sql","006_agent_memory.sql")) {
                statement.execute(Files.readString(Path.of("db/postgresql",script)));
            }
        }
        var embedding=OllamaEmbeddingModel.builder().ollamaApi(OllamaApi.builder()
                .baseUrl(System.getenv().getOrDefault("STABILIZATION_OLLAMA_URL","http://localhost:11434")).build())
                .defaultOptions(OllamaEmbeddingOptions.builder().model("nomic-embed-text").build()).build();
        vectors=PgVectorStore.builder(jdbc,embedding).schemaName(schema).dimensions(768).initializeSchema(true).build();vectors.afterPropertiesSet();
        redisConnection=new LettuceConnectionFactory("localhost",Integer.parseInt(System.getenv().getOrDefault("V87_TEST_REDIS_PORT","56382")));
        // A live application may be running on database 0; its Worker must never consume this schema's jobs.
        redisConnection.setDatabase(1);
        redisConnection.afterPropertiesSet();redis=new StringRedisTemplate(redisConnection);redis.afterPropertiesSet();
        s3=S3Client.builder().endpointOverride(URI.create(System.getenv().getOrDefault("STABILIZATION_S3_URL","http://localhost:59000")))
                .region(Region.US_EAST_1).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("stabilization-test","stabilization-test-secret"))).build();
        storage=new S3ObjectStorage(s3,bucket);validation=Validation.buildDefaultValidatorFactory();
    }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE app_user,vector_store RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO app_user(id,username,password,role) VALUES(1,'rag-one','test','USER'),(2,'rag-two','test','USER')");
        redis.delete("knowledge:document:tasks");queue=new DocumentTaskQueue(redis);
        knowledge=new KnowledgeRepository(jdbc);bases=new KnowledgeBaseManagementService(knowledge,manager);repository=new AgentRepository(jdbc,json);
        model=mock(ChatModel.class);documents=new KnowledgeDocumentService(knowledge,bases,new DocumentFileInspector(),storage,queue,vectors,ChatClient.builder(model),manager);
        worker=worker(vectors);runtime=new AgentRuntime(model,repository,new PromptService(jdbc),new AgentTools(repository,documents,vectors),validation.getValidator(),6,12,48000,60000,20000,4096);
        agent=new AgentService(runtime,repository,new AgentMemory(redis,jdbc,json),bases,json,jdbc,manager);
        base=bases.create(1,"RAG acceptance","isolated fixture").id();
    }
    private DocumentWorker worker(VectorStore target) { return new DocumentWorker(knowledge,queue,storage,new DocumentChunker(),target,manager,"nomic-embed-text",768); }
    private MockMultipartFile file(String name,String text) { return new MockMultipartFile("file",name,"text/markdown",text.getBytes(StandardCharsets.UTF_8)); }
    private String content(String marker) {
        return "# HashMap resize\n\n"+ ("HashMap doubles its capacity when the number of entries exceeds the load factor threshold. During resize, entries move into buckets in the new table. "+marker+".\n\n").repeat(40);
    }
    private long upload(String marker) throws Exception { return documents.upload(1,base,file("Java.md",content(marker))).id(); }
    private void ready(long id,int version) {
        assertEquals("READY",knowledge.findVersion(id,version).status());assertEquals(version,knowledge.findDocument(id).activeVersion());
    }
    @AfterEach void cleanupObjects() {
        if(runtime!=null) { runtime.shutdown(); }
        if(knowledge!=null) {
            for(String key:jdbc.queryForList("SELECT DISTINCT object_key FROM knowledge_document_version",String.class)) { storage.delete(key); }
        }
        UserContext.remove();
    }
    @AfterAll void cleanup() throws Exception {
        Files.createDirectories(Path.of("docs/testing/v8-stabilization-evidence"));
        Files.writeString(Path.of("docs/testing/v8-stabilization-evidence/rag-observations.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(observations));
        if(s3!=null) { try { s3.deleteBucket(b->b.bucket(bucket)); } finally { s3.close(); } }
        if(redisConnection!=null) { redis.delete("knowledge:document:tasks");redisConnection.destroy(); }
        if(validation!=null) { validation.close(); }
        if(schema!=null && schema.matches("stabilization_rag_[a-f0-9]{32}")) {
            try(var c=DriverManager.getConnection(url,"postgres",password)) { c.createStatement().execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }
    @Test void multipartUploadReachesRealVectorRetrievalAndCommittedAgentCitation() throws Exception {
        UserContext.setUserId(1L);
        var mvc=MockMvcBuilders.standaloneSetup(new KnowledgeDocumentController(documents)).build();
        var response=mvc.perform(multipart("/knowledge-bases/{baseId}/documents",base).file(file("Java.md",content("CAPACITY_DOUBLES_V1"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        long id=json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data").path("id").asLong();assertTrue(id>0);
        assertTrue(storage.exists(knowledge.findVersion(id,1).objectKey()));
        assertEquals(content("CAPACITY_DOUBLES_V1"),new String(documents.download(1,id).bytes(),StandardCharsets.UTF_8));
        assertEquals("QUEUED",knowledge.findVersion(id,1).status());worker.poll();ready(id,1);
        assertTrue(knowledge.findVersion(id,1).chunkCount()>1);
        assertEquals(768,jdbc.queryForObject("SELECT vector_dims(embedding) FROM vector_store LIMIT 1",Integer.class));
        var hits=documents.search(1,base,"When does HashMap double capacity during resize?");
        assertFalse(hits.isEmpty());assertTrue(hits.stream().anyMatch(d->d.getText().contains("CAPACITY_DOUBLES_V1")));
        when(model.call(any(Prompt.class))).thenReturn(tool("s1"),reply(cited("K1")));
        var answer=agent.handle(1,new AgentModels.Request(null,"Explain HashMap resize",List.of(base)),false);
        assertEquals(id,answer.result().citations().getFirst().documentId());assertEquals(1,answer.result().citations().getFirst().version());
        assertEquals("FINISHED",jdbc.queryForObject("SELECT state FROM agent_execution WHERE id=?",String.class,answer.executionId()));
        var record=jdbc.queryForObject("SELECT record::text FROM agent_evidence WHERE execution_id=? AND evidence_id='K1'",String.class,answer.executionId());
        assertTrue(record.contains("CAPACITY_DOUBLES_V1"));
        observations.add(Map.of("case","real-ingestion-and-agent-citation","chunks",knowledge.findVersion(id,1).chunkCount(),"hits",hits.size(),"embeddingDimensions",768,"result",answer.result()));
    }
    @Test void duplicateStreamDeliveryDoesNotDuplicateChunksOrPublishAgain() throws Exception {
        long id=upload("DEDUP");worker.poll();ready(id,1);int count=jdbc.queryForObject("SELECT COUNT(*) FROM vector_store",Integer.class);
        queue.publishIndex(id,1);queue.publishIndex(id,1);worker.poll();
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM vector_store",Integer.class));ready(id,1);
        assertEquals(0L,redis.opsForStream().size("knowledge:document:tasks"));
    }
    @Test void fixedRetrievalCorpusRanksExpectedSourcesFirst() throws Exception {
        Map<String,String> corpus=Map.of(
                "HashMap.md","HashMap resize doubles table capacity when entries exceed the load factor threshold. HashMap redistributes entries into new buckets.",
                "Spring.md","Spring transaction propagation REQUIRES_NEW suspends the current transaction and starts an independent transaction. REQUIRED joins an existing transaction.",
                "Redis.md","Redis AOF persistence appends write commands to an append-only file. The everysec fsync policy flushes the AOF file once per second.");
        for(var entry:corpus.entrySet()) { documents.upload(1,base,file(entry.getKey(),entry.getValue())); }
        worker.poll();
        String[][] questions={{"When does HashMap resize its bucket array?","HashMap.md"},{"HashMap load factor threshold and capacity doubling","HashMap.md"},
                {"What does Spring REQUIRES_NEW transaction propagation do?","Spring.md"},{"Which propagation suspends the current transaction?","Spring.md"},
                {"How does Redis append-only file persistence work?","Redis.md"},{"Redis AOF everysec fsync policy","Redis.md"}};
        var results=new ArrayList<Map<String,Object>>();
        for(var question:questions) {
            var hits=documents.search(1,base,question[0]);assertFalse(hits.isEmpty());
            String actual=hits.getFirst().getMetadata().get("source").toString();
            results.add(Map.of("query",question[0],"expected",question[1],"actualTop1",actual,"passed",question[1].equals(actual)));
            assertEquals(question[1],actual,question[0]);
        }
        observations.add(Map.of("case","fixed-corpus-top1","queries",results,"count",questions.length));
    }
    @Test void partialVectorFailureKeepsV1AndRetryPublishesOnlyOneV2Attempt() throws Exception {
        long id=upload("OLD_V1");worker.poll();ready(id,1);
        documents.replaceFile(1,id,file("Java.md",content("NEW_V2")));
        assertTrue(documents.search(1,base,"HashMap resize").stream().allMatch(d->((Number)d.getMetadata().get("documentVersion")).intValue()==1));
        var broken=spy(vectors);
        doAnswer(i->{List<Document> chunks=i.getArgument(0);vectors.add(chunks.subList(0,1));throw new IllegalStateException("injected partial vector write");}).when(broken).add(anyList());
        worker(broken).poll();assertEquals("FAILED",knowledge.findVersion(id,2).status());assertEquals(1,knowledge.findDocument(id).activeVersion());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE metadata->>'documentVersion'='2'",Integer.class));
        assertFalse(documents.search(1,base,"HashMap resize").isEmpty());
        documents.retry(1,id);worker.poll();ready(id,2);
        var hits=documents.search(1,base,"HashMap resize");assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(d->((Number)d.getMetadata().get("documentVersion")).intValue()==2));
        assertEquals(knowledge.findVersion(id,2).chunkCount(),jdbc.queryForObject("SELECT COUNT(*) FROM vector_store",Integer.class));
    }
    @Test void abandonedProcessingLeaseCanBeRecoveredWithoutExposingPartialChunks() throws Exception {
        long id=upload("RECOVER");UUID token=UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(s->assertEquals(1,knowledge.claimVersion(id,1,token,"nomic-embed-text",768,DocumentChunker.STRATEGY,DocumentChunker.PARSER)));
        var chunks=new DocumentChunker().chunks(knowledge.findDocument(id),knowledge.findVersion(id,1),content("RECOVER").getBytes(StandardCharsets.UTF_8),token);
        vectors.add(chunks.subList(0,1));assertTrue(documents.search(1,base,"HashMap resize").isEmpty());
        jdbc.update("UPDATE knowledge_document_version SET processing_started_at=clock_timestamp()-INTERVAL '11 minutes' WHERE document_id=?",id);
        worker.reconcile();worker.poll();worker.reconcile();ready(id,1);
        assertEquals(knowledge.findVersion(id,1).chunkCount(),jdbc.queryForObject("SELECT COUNT(*) FROM vector_store WHERE (metadata->>'published')::boolean",Integer.class));
    }
    @Test void foreignUserAndDeletedDocumentCannotSupplyPrivateEvidence() throws Exception {
        long id=upload("PRIVATE_ONLY");worker.poll();ready(id,1);
        assertThrows(BusinessException.class,()->documents.search(2,base,"HashMap"));
        assertThrows(BusinessException.class,()->agent.handle(2,new AgentModels.Request(null,"HashMap",List.of(base)),false));verify(model,never()).call(any(Prompt.class));
        var keys=knowledge.distinctObjectKeys(id);documents.delete(1,id);
        assertTrue(documents.search(1,base,"HashMap").isEmpty());worker.poll();assertEquals("DELETED",knowledge.findDocument(id).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vector_store",Integer.class));for(String key:keys) { assertFalse(storage.exists(key)); }
    }
    @Test void emptyKnowledgeCannotBackFabricatedCitation() {
        when(model.call(any(Prompt.class))).thenReturn(tool("empty"),reply(cited("K1")),reply(cited("K1")));
        assertEquals(6003,assertThrows(BusinessException.class,()->agent.handle(1,new AgentModels.Request(null,"Unrecorded company name",List.of(base)),false)).getCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
    }
    @Test void cachedSearchStillRevalidatesVersionBeforeCommit() throws Exception {
        long id=upload("CACHE_V1");worker.poll();ready(id,1);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i->{
            int n=calls.incrementAndGet();if(n<3) { return tool("s"+n); }
            documents.replaceFile(1,id,file("Java.md",content("CACHE_V2")));worker.poll();ready(id,2);
            return reply(cited("K1"));
        });
        assertEquals(6003,assertThrows(BusinessException.class,()->agent.handle(1,new AgentModels.Request(null,"HashMap",List.of(base)),false)).getCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE status='SUCCEEDED'",Integer.class));
    }
    private ChatResponse tool(String id) { return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(id,"function","searchKnowledge","{\"query\":\"HashMap resize\"}"))).build()))); }
    private ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private String cited(String evidence) { return "{\"message\":\"HashMap doubles capacity during resize.\",\"weaknesses\":[],\"plan\":[],\"sources\":[\"Java.md\"],\"citations\":[{\"evidenceId\":\""+evidence+"\"}]}"; }
}
