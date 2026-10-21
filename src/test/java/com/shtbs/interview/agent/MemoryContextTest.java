package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.agent.memory.*;
import com.shtbs.interview.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryContextTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ContextBuilder builder=new ContextBuilder(json);
    private final TokenBudgetManager tokens=new TokenBudgetManager(4096,256,256);
    private List<MemoryRepository.Row> pair(long id,String content) {
        UUID round=UUID.randomUUID();return List.of(new MemoryRepository.Row(id,round,"USER","question"),
                new MemoryRepository.Row(id+1,round,"ASSISTANT","{\"message\":\""+content+"\"}"));
    }
    @Test void currentQuestionAppearsOnceAndSummaryIsNeverSystem() {
        var history=builder.history(new MemoryRepository.Summary("old reference",2,1),pair(3,"reply"));
        var result=builder.build("system",history,"current");
        assertEquals(1,result.stream().filter(SystemMessage.class::isInstance).count());
        assertEquals(1,result.stream().filter(m->"current".equals(m.getText())).count());
        assertInstanceOf(UserMessage.class,result.get(1));assertEquals("current",result.getLast().getText());
    }
    @Test void historyProjectionPreservesValidContentAndRemovesOldEvidence() throws Exception {
        String raw="{\"message\":\""+"中".repeat(6000)+"\",\"weaknesses\":[{\"evidenceIds\":[\"W1\"]}],\"citations\":[{\"evidenceId\":\"K1\"}],\"plan\":[{\"topic\":\"Spring\",\"tasks\":[\"review\"],\"questionIds\":[12]}]}";
        String projected=builder.project(raw);var value=json.readTree(projected);
        assertEquals(6000,value.path("historicalAssistantReply").asText().length());
        assertEquals("Spring",value.path("historicalPlanSuggestions").get(0).path("topic").asText());
        assertFalse(projected.contains("evidenceId"));assertFalse(projected.contains("questionIds"));
    }
    @Test void invalidLegacyJsonIsExplicitlyMarked() { assertTrue(builder.project("{\"message\":\"cut").contains("could not be parsed")); }
    @Test void budgetEvictsWholeOldRoundsAndKeepsNewestRoundSummaryAndQuestion() {
        List<MemoryRepository.Row> rows=new ArrayList<>(pair(1,"x".repeat(3000)));rows.addAll(pair(3,"recent"));
        var messages=builder.build("system",builder.history(new MemoryRepository.Summary("important",1,1),rows),"current");
        tokens.fit(messages,List.of(),512,48000);
        assertFalse(messages.stream().anyMatch(m->m.getText().contains("x".repeat(100))));
        assertTrue(messages.stream().anyMatch(m->m.getText().contains("recent")));
        assertTrue(messages.stream().anyMatch(m->m.getText().contains("important")));
        assertEquals("current",messages.getLast().getText());assertEquals(5,messages.size());
    }
    @Test void requiredToolProtocolIsNeverCutToMakeItFit() {
        var call=new AssistantMessage.ToolCall("evidence-id","function","searchKnowledge","{\"query\":\"x\"}");
        var result=ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("evidence-id","searchKnowledge","x".repeat(5000)))).build();
        var messages=builder.build("system",List.of(),"current");
        messages.add(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());messages.add(result);
        assertEquals(6002,assertThrows(BusinessException.class,()->tokens.fit(messages,List.of(),512,48000)).getCode());
        assertSame(result,messages.getLast());assertEquals(call,((AssistantMessage)messages.get(2)).getToolCalls().getFirst());
    }
    @Test void toolDefinitionsAndChineseAndOutputReserveCountTowardBudget() {
        ToolCallback tool=mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("search").description("x".repeat(3500)).inputSchema("{}").build());
        var messages=builder.build("system",List.of(),"current");
        assertThrows(BusinessException.class,()->tokens.fit(messages,List.of(tool),512,48000));
        assertEquals(3,tokens.estimate("中"));
        assertThrows(BusinessException.class,()->tokens.fit(builder.build("system",List.of(),"中".repeat(1200)),List.of(),512,48000));
    }
    @Test void oldCharacterLimitAlsoRemovesWholeRounds() {
        var messages=builder.build("system",builder.history(null,pair(1,"x".repeat(800))),"current");
        tokens.fit(messages,List.of(),512,700);assertEquals(2,messages.size());
    }
    @Test void mismatchedExecutionsAreNotCombinedIntoARound() {
        var rows=List.of(new MemoryRepository.Row(1,UUID.randomUUID(),"USER","u"),new MemoryRepository.Row(2,UUID.randomUUID(),"ASSISTANT","{}"));
        assertTrue(builder.history(null,rows).isEmpty());
    }

    @Test void t911And921FourShortMessagesNeedNoSummaryOrEviction() {
        var rows=new ArrayList<>(pair(1,"first reply"));rows.addAll(pair(3,"latest reply"));
        var prompt=builder.build("system",builder.history(null,rows),"current question");
        int before=prompt.size();tokens.fit(prompt,List.of(),512,48000);
        assertEquals(before,prompt.size());assertEquals(6,prompt.size());
        assertTrue(prompt.stream().anyMatch(m->m.getText().contains("first reply")));
        assertTrue(prompt.stream().anyMatch(m->m.getText().contains("latest reply")));
    }
    @Test void t913SummaryAndTenUncoveredMessagesMakeTheExactPrompt() {
        var rows=new ArrayList<MemoryRepository.Row>();for(int n=16;n<=20;n++) rows.addAll(pair(n*2-1,"answer-"+n));
        var summary=new MemoryRepository.Summary("Spring propagation is the user's earlier difficulty",30,3);
        var history=builder.history(summary,rows);var prompt=builder.build("system",history,"What did I say recently?");
        assertEquals(13,prompt.size());assertTrue(prompt.get(1).getText().contains(summary.text()));
        assertTrue(prompt.stream().anyMatch(m->m.getText().contains("answer-16")));
        assertTrue(prompt.stream().anyMatch(m->m.getText().contains("answer-20")));
        assertEquals("What did I say recently?",prompt.getLast().getText());
        assertFalse(prompt.stream().anyMatch(m->m.getText().contains("answer-15")));
    }
    @Test void t918To920RecentAndSummarizedFactsAreVisibleButOmittedDetailsAreAbsent() {
        String summary="{\"topics\":[\"Spring transaction propagation\"],\"userStatements\":[\"Study two hours daily\"]}";
        var history=builder.history(new MemoryRepository.Summary(summary,30,3),pair(31,"recent HashMap note"));
        var prompt=builder.build("system",history,"Do you remember the old secret code?");
        String submitted=prompt.stream().map(Message::getText).filter(Objects::nonNull).reduce("",(a,b)->a+"\n"+b);
        assertTrue(submitted.contains("Spring transaction propagation"));assertTrue(submitted.contains("Study two hours daily"));
        assertTrue(submitted.contains("recent HashMap note"));assertFalse(submitted.contains("old-secret-771"));
        assertEquals(1,prompt.stream().filter(m->"Do you remember the old secret code?".equals(m.getText())).count());
    }
    @Test void t922And923OversizeOldRoundIsRemovedWhileRecentRoundSurvives() {
        var rows=new ArrayList<>(pair(1,"x".repeat(10000)));rows.addAll(pair(3,"latest"));
        var messages=builder.build("system",builder.history(null,rows),"current");tokens.fit(messages,List.of(),512,48000);
        assertFalse(messages.stream().anyMatch(m->m.getText().contains("x".repeat(100))));
        assertTrue(messages.stream().anyMatch(m->m.getText().contains("latest")));
        assertEquals("current",messages.getLast().getText());
    }
    @Test void t927LargePlanJsonIsProjectedWithoutStructuredEvidenceOrRecommendations() throws Exception {
        String raw="{\"message\":\"Review Spring\",\"weaknesses\":[{\"evidenceIds\":[\"W1\"]}],"
                +"\"citations\":[{\"evidenceId\":\"K1\"}],\"plan\":[{\"day\":1,\"topic\":\"Spring\",\"minutes\":60,"
                +"\"tasks\":[\"read\"],\"questionIds\":[201],\"recommendations\":[{\"questionId\":201,\"evidenceId\":\"Q1\"}]}],"
                +"\"audit\":\""+"unused".repeat(2000)+"\"}";
        String projected=builder.project(raw);var node=json.readTree(projected);
        assertTrue(raw.length()>projected.length()*10);assertEquals("Review Spring",node.path("historicalAssistantReply").asText());
        assertEquals("Spring",node.path("historicalPlanSuggestions").get(0).path("topic").asText());
        for(String forbidden:List.of("evidenceId","questionIds","recommendations","citations","audit")) assertFalse(projected.contains(forbidden));
    }
    @Test void t926SystemCurrentQuestionAndToolEvidenceCannotBeSilentlyDropped() {
        var required=new ToolResponseMessage.ToolResponse("call-1","analyzeWeaknesses","W1: recorded low score");
        var tool=ToolResponseMessage.builder().responses(List.of(required)).build();
        var messages=builder.build("S".repeat(3200),List.of(),"Q".repeat(700));messages.add(tool);
        int before=messages.size();
        assertEquals(6002,assertThrows(BusinessException.class,()->tokens.fit(messages,List.of(),512,48000)).getCode());
        assertEquals(before,messages.size());assertTrue(messages.getFirst().getText().contains("S".repeat(3200)));
        assertTrue(messages.stream().anyMatch(m->"Q".repeat(700).equals(m.getText())));
        assertSame(tool,messages.getLast());
    }
    @Test void t932OldFormatAndPartialPairNeverImportAnotherExecutionAsAValidRound() {
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        var rows=List.of(new MemoryRepository.Row(1,one,"USER","my old detail"),
                new MemoryRepository.Row(2,one,"ASSISTANT","{\"message\":\"cut"),
                new MemoryRepository.Row(3,two,"USER","unpaired new message"));
        var history=builder.history(null,rows);
        assertEquals(2,history.size());assertEquals("my old detail",history.getFirst().getText());
        assertTrue(history.getLast().getText().contains("could not be parsed"));
        assertFalse(history.stream().anyMatch(m->m.getText().contains("unpaired new message")));
        assertTrue(builder.history(null,List.of(new MemoryRepository.Row(4,UUID.randomUUID(),"USER","orphan"))).isEmpty());
    }
}
