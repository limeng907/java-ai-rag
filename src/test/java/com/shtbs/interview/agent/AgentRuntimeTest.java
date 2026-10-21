package com.shtbs.interview.agent;

import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.knowledge.KnowledgeDocumentService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentRuntimeTest {
    private ChatModel model;
    private AgentRepository repository;
    private PromptService prompts;
    private AgentRuntime runtime;
    private AgentTools tools;
    private VectorStore vectors;
    private ValidatorFactory validation;
    private static final String ANSWER="{\"message\":\"ok\",\"weaknesses\":[],\"plan\":[],\"sources\":[]}";

    @BeforeEach void setup() {
        model=mock(ChatModel.class);repository=mock(AgentRepository.class);prompts=mock(PromptService.class);
        vectors=mock(VectorStore.class);
        when(prompts.active(anyString())).thenAnswer(i->new PromptService.Template(i.getArgument(0),1,"Return JSON",true));
        when(repository.profile(anyLong())).thenReturn(new AgentModels.Profile("Java","Student",60));
        when(repository.history(anyLong(),anyInt())).thenReturn(List.of());
        when(repository.weaknesses(anyLong())).thenReturn(List.of());
        when(repository.json(any())).thenAnswer(i->new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(i.getArgument(0)));
        tools=new AgentTools(repository,mock(KnowledgeDocumentService.class),vectors);
        validation=Validation.buildDefaultValidatorFactory();runtime=create(60000,20000);
    }
    private AgentRuntime create(long timeout,long callTimeout) {
        return new AgentRuntime(model,repository,prompts,tools,validation.getValidator(),6,12,48000,timeout,callTimeout,4096);
    }
    @AfterEach void cleanup() { runtime.shutdown();validation.close(); }

    @Test void multipleToolsUseServerIdentityAndReturnValidatedNotFinished() {
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("p","getUserProfile","{}"),
                call("h","getInterviewHistory","{\"limit\":5}"),call("w","analyzeWeaknesses","{}")),reply(ANSWER));
        var result=run("CHAT");assertEquals("ok",result.value().message());assertEquals(3,result.toolResults().size());
        verify(repository,times(2)).profile(1);verify(repository).history(1,5);verify(repository).weaknesses(1);
        verify(repository).state(result.executionId(),"VALIDATED",2,3,null);
        verify(repository,never()).completeExecution(any());
        verify(repository,never()).state(any(),eq("FINISHED"),anyInt(),anyInt(),any());
        verify(repository,times(3)).finishGroundedTool(any(),eq(1L),anyLong(),anyString(),anyList(),any());
    }
    @Test void repeatedSearchReusesFetchButKeepsBothCallIdsAndBudgets() {
        when(repository.questions("HashMap",3)).thenReturn(List.of(Map.of("questionId",1,"title","HashMap")));
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("q1","searchQuestions","{\"keyword\":\"HashMap\",\"limit\":3}")),
                toolReply(call("q2","searchQuestions","{\"limit\":3,\"keyword\":\"HashMap\"}")),reply(ANSWER));
        var result=run("CHAT");verify(repository,times(1)).questions("HashMap",3);
        verify(repository).startTool(result.executionId(),"q1","searchQuestions","{\"keyword\":\"HashMap\",\"limit\":3}");
        verify(repository).startTool(result.executionId(),"q2","searchQuestions","{\"limit\":3,\"keyword\":\"HashMap\"}");
        verify(repository).state(result.executionId(),"VALIDATED",3,2,null);
        assertEquals("q1",result.evidence().get("Q1").toolCallId());assertEquals("q2",result.evidence().get("Q2").toolCallId());
        assertEquals(2,result.toolResults().get("searchQuestions").size());
    }
    @Test void toolLoopRechecksTokenBudgetBeforeSecondModelCall() {
        when(repository.profile(anyLong())).thenReturn(new AgentModels.Profile("中".repeat(20000),"Student",60));
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("p","getUserProfile","{}")));
        assertEquals(6002,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        verify(model,times(1)).call(any(Prompt.class));
        verify(repository).state(any(),eq("TOOL_CALL"),eq(1),eq(1),isNull());
    }
    @Test void invalidArgumentsInSecondCallPreventEntireBatch() {
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("p","getUserProfile","{}"),
                call("h","getInterviewHistory","{\"limit\":\"5\"}")));
        assertEquals(6003,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        verify(repository,never()).startTool(any(),anyString(),anyString(),anyString());
        verify(repository,never()).profile(anyLong());
    }
    @Test void lastTwoCallsGenerateAndRepairWithNoTools() {
        var count=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i->{
            int n=count.incrementAndGet();
            if (n<=4) { return toolReply(call("p"+n,"getUserProfile","{}")); }
            var options=(ToolCallingChatOptions)((Prompt)i.getArgument(0)).getOptions();
            assertTrue(options.getToolCallbacks().isEmpty());assertTrue(options.getToolNames().isEmpty());
            return reply(n==5?"not-json":ANSWER);
        });
        var result=run("CHAT");assertEquals(6,count.get());
        verify(repository).state(result.executionId(),"VALIDATED",6,4,null);
    }
    @Test void finalStageCannotResumeToolExecution() {
        var count=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i->toolReply(call("p"+count.incrementAndGet(),"getUserProfile","{}")));
        assertEquals(6002,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        assertEquals(5,count.get());verify(repository,times(4)).profile(1);
    }
    @Test void missingEvidenceFailsButSuccessfulEmptyHistoryIsAccepted() {
        when(model.call(any(Prompt.class))).thenReturn(reply(ANSWER));
        assertEquals(6002,assertThrows(BusinessException.class,()->run("STUDY")).getCode());
        verify(model,times(4)).call(any(Prompt.class));clearInvocations(model);
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("p","getUserProfile","{}"),
                call("h","getInterviewHistory","{\"limit\":5}"),call("w","analyzeWeaknesses","{}")),reply(ANSWER));
        assertEquals(List.of("[]"),run("CHAT").toolResults().get("getInterviewHistory"));
        verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void invalidOutputIsRepairedOnlyOnceAndRepairCannotUseTools() {
        when(model.call(any(Prompt.class))).thenReturn(reply("not-json"));
        assertEquals(6003,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        verify(model,times(2)).call(any(Prompt.class));
        verify(repository).failExecution(any(),any(BusinessException.class));
    }
    @Test void toolsIgnoringInterruptCannotPublishLateSuccessOrRunNextTool() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var lateAudit=new CountDownLatch(1);
        when(vectors.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenAnswer(i->{
            entered.countDown();boolean done=false;
            while (!done) { try { done=release.await(2,TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
            return List.of();
        });
        doAnswer(i->{lateAudit.countDown();return null;}).when(repository).finishTool(anyLong(),isNull(),eq("TIMED_OUT"),anyLong());
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("k","searchKnowledge","{\"query\":\"Java\"}"),call("p","getUserProfile","{}")));
        runtime.shutdown();runtime=create(3000,300);
        try {
            assertEquals(6008,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
            assertEquals(0,entered.getCount());release.countDown();assertTrue(lateAudit.await(2,TimeUnit.SECONDS));
            verify(repository,never()).profile(anyLong());
            verify(repository,never()).finishTool(anyLong(),anyString(),eq("SUCCEEDED"),anyLong());
            verify(repository).tool(any(),eq("p"),eq("getUserProfile"),eq("{}"),isNull(),eq("NOT_EXECUTED"),eq(0L));
            verify(model,times(1)).call(any(Prompt.class));
        } finally { release.countDown(); }
    }
    @Test void t701HistoryLimitThreeUsesAuthenticatedUser() {
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("history-3","getInterviewHistory","{\"limit\":3}")),reply(ANSWER));
        run("CHAT");verify(repository).history(1,3);verify(repository,never()).history(eq(1002L),anyInt());
    }
    @Test void t702SequentialToolsKeepPreviousResponsesAndCallIds() {
        var step=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i->{
            int n=step.incrementAndGet();Prompt prompt=i.getArgument(0);
            if(n>1) {
                var responses=prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                        .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
                        .flatMap(m->m.getResponses().stream()).toList();
                assertTrue(responses.stream().anyMatch(r->r.id().equals("history") && r.name().equals("getInterviewHistory") && !r.responseData().isBlank()));
                if(n==3) assertTrue(responses.stream().anyMatch(r->r.id().equals("weak") && r.name().equals("analyzeWeaknesses")));
            }
            return n==1?toolReply(call("history","getInterviewHistory","{\"limit\":3}")):
                    n==2?toolReply(call("weak","analyzeWeaknesses","{}")):reply(ANSWER);
        });
        run("CHAT");assertEquals(3,step.get());
    }
    @Test void t703BatchToolResponsesMatchTheirIds() {
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("history","getInterviewHistory","{\"limit\":3}"),call("weak","analyzeWeaknesses","{}")),reply(ANSWER));
        run("CHAT");var capture=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model,times(2)).call(capture.capture());
        var responses=capture.getAllValues().getLast().getInstructions().stream()
                .filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
                .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast).flatMap(m->m.getResponses().stream()).toList();
        assertEquals(Map.of("history","getInterviewHistory","weak","analyzeWeaknesses"),responses.stream()
                .collect(java.util.stream.Collectors.toMap(r->r.id(),r->r.name())));
    }
    @Test void t704ChatCanFinishWithoutTools() {
        when(model.call(any(Prompt.class))).thenReturn(reply(ANSWER));
        assertEquals("ok",run("CHAT").value().message());verify(model).call(any(Prompt.class));
        verify(repository,never()).startTool(any(),anyString(),anyString(),anyString());
    }
    @Test void t706InterviewCannotCallUserProfile() {
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("bad","getUserProfile","{}")));
        assertEquals(6002,assertThrows(BusinessException.class,()->run("INTERVIEW")).getCode());
        verify(repository,never()).profile(anyLong());verify(repository,never()).startTool(any(),anyString(),anyString(),anyString());
    }
    @Test void t707UnknownToolCannotExecute() {
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("bad","deleteAllUsers","{}")));
        assertEquals(6002,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        verify(repository,never()).startTool(any(),anyString(),anyString(),anyString());
    }
    @Test void t708To710InvalidArgumentsNeverReachBusinessTools() {
        for(var invalid:List.of(call("limit","getInterviewHistory","{\"limit\":1000}"),
                call("query","searchKnowledge","{\"query\":\""+"x".repeat(1001)+"\"}"),
                call("identity","getInterviewHistory","{\"limit\":3,\"userId\":1002}"))) {
            when(model.call(any(Prompt.class))).thenReturn(toolReply(invalid));
            assertEquals(6003,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        }
        verify(repository,never()).history(anyLong(),anyInt());verifyNoInteractions(vectors);
        verify(repository,never()).startTool(any(),anyString(),anyString(),anyString());
    }
    @Test void t712CumulativeToolBudgetRejectsThirteenthCallBeforeBatch() {
        var round=new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i->{
            int n=round.incrementAndGet();var batch=new ArrayList<AssistantMessage.ToolCall>();
            for(int k=0;k<(n<3?5:3);k++) batch.add(call(n+"-"+k,"getUserProfile","{}"));
            return toolReply(batch.toArray(AssistantMessage.ToolCall[]::new));
        });
        assertEquals(6002,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        verify(repository,times(10)).startTool(any(),anyString(),anyString(),anyString());verify(repository,times(10)).profile(1);
        verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void t713SixModelCallsNeverBecomeSeven() {
        var step=new AtomicInteger();when(model.call(any(Prompt.class))).thenAnswer(i->step.incrementAndGet()<=4?
                toolReply(call("p"+step.get(),"getUserProfile","{}")):reply("not-json"));
        assertEquals(6003,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        assertEquals(6,step.get());verify(repository,never()).state(any(),eq("VALIDATED"),anyInt(),anyInt(),any());
    }
    @Test void t714DefaultTwentySecondTimeoutRejectsLateModelSuccess() throws Exception {
        var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(i->{
            try { while(true) { try { release.await();break; } catch(InterruptedException ignored) {} } return reply(ANSWER); }
            finally { exited.countDown(); }
        });
        long start=System.nanoTime();
        try {
            assertEquals(6008,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertTrue(elapsed>=19000 && elapsed<26000,"elapsed="+elapsed);
        } finally { release.countDown();assertTrue(exited.await(3,TimeUnit.SECONDS)); }
        verify(model).call(any(Prompt.class));verify(repository,never()).state(any(),eq("VALIDATED"),anyInt(),anyInt(),any());
    }
    @Test void t715DefaultSixtySecondTotalDeadlineStopsIndividuallyValidCalls() {
        var step=new AtomicInteger();when(model.call(any(Prompt.class))).thenAnswer(i->{
            int n=step.incrementAndGet();Thread.sleep(16000);return toolReply(call("p"+n,"getUserProfile","{}"));
        });
        long start=System.nanoTime();assertEquals(6008,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertTrue(elapsed>=59000 && elapsed<66000,"elapsed="+elapsed);assertEquals(4,step.get());
        verify(repository,times(3)).startTool(any(),anyString(),anyString(),anyString());
        verify(repository,never()).state(any(),eq("VALIDATED"),anyInt(),anyInt(),any());
    }
    @Test void t716ToolExceptionIsAuditedAsFailureAndStopsExecution() {
        when(repository.history(1,3)).thenThrow(new IllegalStateException("injected storage failure"));
        when(model.call(any(Prompt.class))).thenReturn(toolReply(call("h","getInterviewHistory","{\"limit\":3}"),call("p","getUserProfile","{}")));
        assertEquals(6001,assertThrows(BusinessException.class,()->run("CHAT")).getCode());
        verify(repository).finishTool(anyLong(),isNull(),eq("FAILED"),anyLong());verify(repository,never()).profile(anyLong());
        verify(repository,never()).finishGroundedTool(any(),anyLong(),anyLong(),anyString(),anyList(),any());
        verify(model).call(any(Prompt.class));
    }
    private AgentRuntime.Run<AgentModels.Answer> run(String kind) {
        return runtime.run(1,null,null,kind,"x",List.of(),List.of(),AgentModels.Answer.class);
    }
    private ChatResponse reply(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private AssistantMessage.ToolCall call(String id,String name,String arguments) { return new AssistantMessage.ToolCall(id,"function",name,arguments); }
    private ChatResponse toolReply(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }
}
