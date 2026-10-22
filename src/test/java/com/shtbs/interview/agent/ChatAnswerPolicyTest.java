package com.shtbs.interview.agent;

import com.shtbs.interview.agent.evidence.EvidenceRegistry;
import com.shtbs.interview.agent.validation.ChatAnswerPolicy;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatAnswerPolicyTest {
    private final ChatAnswerPolicy policy=new ChatAnswerPolicy();

    private EvidenceRegistry evidence() {
        var registry=new EvidenceRegistry(UUID.randomUUID(),1,List.of(10L));
        registry.accept(registry.prepare("searchKnowledge","first","[{\"source\":\"LinkedList.md\",\"documentId\":1,\"version\":1,\"chunkId\":\"linked\",\"scope\":\"PRIVATE\",\"contentHash\":\"linked-hash\",\"text\":\"LinkedList is a doubly linked list; indexed access traverses nodes.\"}]"));
        registry.accept(registry.prepare("searchKnowledge","second","[{\"source\":\"ArrayList.md\",\"documentId\":2,\"version\":1,\"chunkId\":\"array\",\"scope\":\"PRIVATE\",\"contentHash\":\"array-hash\",\"text\":\"ArrayList stores elements in a resizable array and indexed access is constant time.\"}]"));
        return registry;
    }
    private AgentModels.Answer answer(String message,String... ids) {
        return new AgentModels.Answer(message,List.of(),List.of(),
                java.util.Arrays.stream(ids).map(id->id.equals("K1")?"LinkedList.md":"ArrayList.md").toList(),
                java.util.Arrays.stream(ids).map(id->new AgentModels.KnowledgeCitation(id,null,null,null,null,null,null)).toList());
    }

    @Test void ordinaryChatRejectsPersonalDiagnosisAndPlans() {
        var weakness=new AgentModels.Weakness("ArrayList","personal diagnosis",List.of(1L));
        var diagnosed=new AgentModels.Answer("ArrayList [K2]",List.of(weakness),List.of(),List.of("ArrayList.md"),
                List.of(new AgentModels.KnowledgeCitation("K2",null,null,null,null,null,null)));
        assertNotNull(policy.validate(diagnosed,evidence(),"Which list uses a resizable array?",false));
        var plan=new AgentModels.PlanDay(1,"ArrayList",30,List.of("Review"),List.of());
        assertNotNull(policy.validate(new AgentModels.Answer("x",List.of(),List.of(plan),List.of()),evidence(),"x",false));
    }

    @Test void linkedListComparisonNeedsArrayListChunkInTheSameSentence() {
        var registry=evidence();
        String question="Which list uses doubly linked nodes?";
        assertNotNull(policy.validate(answer("LinkedList 使用双向链表 [K1]。ArrayList 使用可扩容数组 [K1]。","K1"),registry,question,false));
        assertNull(policy.validate(answer("LinkedList 使用双向链表 [K1]。ArrayList 使用可扩容数组 [K2]。","K1","K2"),registry,question,false));
        assertNotNull(policy.validate(answer("LinkedList 使用双向链表 [K1]，而 ArrayList 使用可扩容数组。","K1"),registry,question,false));
        assertNotNull(policy.validate(answer("LinkedList uses nodes. ArrayList uses an array [K1] [K2].","K1","K2"),registry,question,false));
    }

    @Test void citationListAloneDoesNotCoverAnUnmarkedTechnicalClaim() {
        assertNotNull(policy.validate(answer("LinkedList 使用双向链表。","K1"),evidence(),"Which list?",false));
        assertNull(policy.validate(answer("LinkedList 使用双向链表（K1）。","K1"),evidence(),"Which list?",false));
    }

    @Test void questionAndCitedSubjectMustAppearInTheAnswer() {
        var registry=evidence();
        assertNotNull(policy.validate(answer("以下薄弱点依据历史面试整理。","K2"),registry,"Which list uses a resizable array?",false));
        assertNotNull(policy.validate(answer("请根据资料复习。","K2"),registry,"Which list uses a resizable array?",false));
        assertNotNull(policy.validate(answer("LinkedList 使用双向链表 [K1]。","K1"),registry,"ArrayList 使用什么结构？",false));
        assertNull(policy.validate(answer("资料不足，无法确认。"),registry,"ArrayList 使用什么结构？",false));
    }
}
