package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.agent.evidence.*;
import com.shtbs.interview.agent.validation.AgentEvidenceValidator;
import com.shtbs.interview.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentEvidenceValidatorTest {
    private final AgentEvidenceValidator validator=new AgentEvidenceValidator();
    private final ObjectMapper json=new ObjectMapper();
    private EvidenceRegistry registry() { return new EvidenceRegistry(UUID.randomUUID(),1,List.of()); }
    private void add(EvidenceRegistry registry,String tool,String value) {
        registry.accept(registry.prepare(tool,UUID.randomUUID().toString(),value));
    }
    private EvidenceRegistry weakness() {
        var registry=registry();
        add(registry,"analyzeWeaknesses","[{\"sessionId\":1,\"turnId\":10,\"round\":3,\"score\":45,\"correct\":false,\"skipped\":false,\"question\":\"HashMap resize\",\"missingPointsJson\":\"Missing relocation\"}]");
        return registry;
    }
    private AgentModels.Answer answer(String topic,String id,AgentModels.EvidenceFact fact) {
        return new AgentModels.Answer("analysis",List.of(new AgentModels.Weakness(topic,"Recorded assessment",List.of(1L),List.of(id),List.of(fact))),List.of(),List.of());
    }
    private AgentModels.EvidenceFact fact(String id,Integer score) {
        return new AgentModels.EvidenceFact(id,"HashMap",1L,10L,3,score,false,false);
    }
    @Test void validTurnFactsAreVerifiedAndCompletedByBackend() {
        var registry=weakness();var answer=answer("HashMap","W1",fact("W1",45));
        assertNull(validator.validate(answer,registry,false,60));
        assertEquals(45,validator.complete(answer,registry).weaknesses().getFirst().facts().getFirst().score());
    }
    @Test void realIdCannotSupportWrongScoreCorrectnessOrRound() {
        var registry=weakness();
        assertNotNull(validator.validate(answer("HashMap","W1",fact("W1",90)),registry,false,60));
        var wrong=new AgentModels.EvidenceFact("W1","HashMap",1L,10L,4,45,true,false);
        assertNotNull(validator.validate(answer("HashMap","W1",wrong),registry,false,60));
    }
    @Test void missingForeignOrCrossTypeIdsCannotSupportWeakness() {
        var registry=weakness();
        assertNotNull(validator.validate(answer("HashMap","W999",fact("W999",45)),registry,false,60));
        add(registry,"getInterviewHistory","[{\"sessionId\":1,\"score\":90}]");
        assertNotNull(validator.validate(answer("HashMap","H1",fact("H1",90)),registry,false,60));
        var foreign=new AgentModels.Weakness("HashMap","x",List.of(2L),List.of("W1"),List.of(fact("W1",45)));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(foreign),List.of(),List.of()),registry,false,60));
    }
    @Test void skipAndHighScoreCorrectTurnAreNotAbilityWeaknessEvidence() {
        for (String fields:List.of("\"score\":0,\"correct\":false,\"skipped\":true","\"score\":90,\"correct\":true,\"skipped\":false")) {
            var registry=registry();add(registry,"analyzeWeaknesses","[{\"sessionId\":1,\"turnId\":10,\"question\":\"HashMap\","+fields+"}]");
            assertEquals(EvidenceType.INTERVIEW,registry.get("H1").type());
            assertNotNull(validator.validate(answer("HashMap","H1",fact("H1",0)),registry,false,60));
        }
    }
    @Test void legacySummaryCannotBePresentedAsTurnScore() {
        var registry=registry();add(registry,"analyzeWeaknesses","[{\"sessionId\":1,\"sessionWeaknessesExcerpt\":\"HashMap\"}]");
        assertEquals("SESSION_SUMMARY",registry.get("W1").attributes().path("granularity").asText());
        assertNotNull(validator.validate(answer("HashMap","W1",fact("W1",45)),registry,false,60));
        var fact=new AgentModels.EvidenceFact("W1","HashMap",1L,null,null,null,null,null);
        assertNull(validator.validate(answer("HashMap","W1",fact),registry,false,60));
    }
    @Test void exactQuoteAndExplicitTopicAssociationAreRequired() {
        var registry=weakness();
        assertNotNull(validator.validate(answer("Redis","W1",fact("W1",45)),registry,false,60));
        assertNotNull(validator.validate(answer("Java","W1",fact("W1",45)),registry,false,60));
        var fabricated=new AgentModels.EvidenceFact("W1","Invented excerpt",1L,10L,3,45,false,false);
        assertNotNull(validator.validate(answer("HashMap","W1",fabricated),registry,false,60));
    }
    @Test void questionRecommendationNeedsQuestionToolAndTopicMatchNotJustReason() {
        var registry=registry();add(registry,"searchQuestions","[{\"questionId\":2,\"title\":\"HashMap\",\"content\":\"resize\"}]");
        var recommendation=new AgentModels.QuestionRecommendation(2,"Q1","Sounds useful");
        var day=new AgentModels.PlanDay(1,"Redis",60,List.of("Review"),List.of(2L),List.of(recommendation));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(day),List.of()),registry,false,60));
        day=new AgentModels.PlanDay(1,"HashMap",60,List.of("Review"),List.of(2L),List.of(recommendation));
        assertNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(day),List.of()),registry,false,60));
        day=new AgentModels.PlanDay(1,"HashMap",60,List.of("Review"),List.of(2L));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(day),List.of()),registry,false,60));
    }
    @Test void preciseKnowledgeIdentityIsBackendControlledAndVersionCannotBeChanged() {
        var registry=registry();add(registry,"searchKnowledge","[{\"source\":\"book\",\"scope\":\"PRIVATE\",\"documentId\":11,\"version\":2,\"chunkId\":\"chunk\",\"contentHash\":\"hash\",\"text\":\"HashMap\"}]");
        var citation=new AgentModels.KnowledgeCitation("K1",null,null,null,null,null,null);
        var answer=new AgentModels.Answer("x",List.of(),List.of(),List.of("book"),List.of(citation));
        assertNull(validator.validate(answer,registry,false,60));
        assertEquals(2,validator.complete(answer,registry).citations().getFirst().version());
        citation=new AgentModels.KnowledgeCitation("K1","PRIVATE","book",11L,1,"chunk","hash");
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(),List.of("book"),List.of(citation)),registry,false,60));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(),List.of("book")),registry,false,60));
    }
    @Test void noRecursiveCollectionOrModelControlledRegistration() {
        var registry=registry();
        add(registry,"searchQuestions","[{\"questionId\":2,\"source\":\"fake\",\"sessionId\":999,\"evidence\":[{\"evidenceId\":\"W1\"}]}]");
        assertNull(registry.get("W1"));assertEquals(EvidenceType.QUESTION,registry.get("Q1").type());
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(),List.of("fake")),registry,false,60));
    }
    @Test void emptyHistoryAllowsBasicPlanButTimeAndSevenDaysStillApply() {
        var registry=registry();add(registry,"getInterviewHistory","[]");add(registry,"analyzeWeaknesses","[]");
        assertNull(validator.validate(new AgentModels.Answer("No interview evidence",List.of(),days(60),List.of()),registry,true,60));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),days(61),List.of()),registry,true,60));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(),List.of()),registry,true,60));
    }
    @Test void registryIsExecutionScopedAndFrozenRegistryRejectsLateEvidence() {
        var registry=weakness();assertNull(registry().get("W1"));
        var prepared=registry.prepare("searchQuestions","q","[{\"questionId\":2,\"title\":\"Java\"}]");
        registry.freeze();assertThrows(BusinessException.class,()->registry.accept(prepared));
        assertThrows(BusinessException.class,()->registry.prepare("getUserProfile","p","{}"));
    }
    @Test void oldStoredJsonRemainsReadableButNewWeaknessMustHaveEvidence() throws Exception {
        var old=json.readValue("{\"message\":\"x\",\"weaknesses\":[{\"topic\":\"HashMap\",\"reason\":\"x\",\"sessionIds\":[1]}],\"plan\":[],\"sources\":[]}",AgentModels.Answer.class);
        assertTrue(old.citations().isEmpty());assertTrue(old.weaknesses().getFirst().evidenceIds().isEmpty());
        assertNotNull(validator.validate(old,weakness(),false,60));
    }
    @Test void recordAttributesAreDefensiveCopiesAndEvidenceCountIsBounded() {
        var registry=weakness();var attributes=registry.get("W1").attributes();
        ((com.fasterxml.jackson.databind.node.ObjectNode)attributes).put("score",90);
        assertEquals(45,registry.get("W1").attributes().path("score").asInt());
        var bounded=registry();
        for (int i=0;i<120;i++) { add(bounded,"getInterviewHistory","[{\"sessionId\":"+(i+1)+"}]"); }
        assertThrows(BusinessException.class,()->bounded.prepare("getInterviewHistory","overflow","[{\"sessionId\":121}]"));
    }
    @Test void t817CorrectnessMismatchIsRejectedWithoutChangingAnyOtherFact() {
        var wrong=new AgentModels.EvidenceFact("W1","HashMap",1L,10L,3,45,true,false);
        assertNotNull(validator.validate(answer("HashMap","W1",wrong),weakness(),false,60));
    }
    @Test void t811And815SameShortIdInOtherExecutionCannotSupplyForeignFacts() {
        var first=weakness();var second=registry();
        add(second,"analyzeWeaknesses","[{\"sessionId\":2,\"turnId\":20,\"round\":2,\"score\":30,\"correct\":false,\"skipped\":false,\"question\":\"HashMap resize\"}]");
        assertNotEquals(first.executionId(),second.executionId());
        assertNotNull(first.get("W1"));assertNotNull(second.get("W1"));
        assertNotNull(validator.validate(answer("HashMap","W1",fact("W1",45)),second,false,60));
        assertNull(validator.validate(answer("HashMap","W1",fact("W1",45)),first,false,60));
    }
    @Test void t820OmittedFactsAreFilledFromBackendRatherThanInvented() {
        var registry=weakness();var unspecified=new AgentModels.EvidenceFact("W1","HashMap",null,null,null,null,null,null);
        var valid=answer("HashMap","W1",unspecified);assertNull(validator.validate(valid,registry,false,60));
        var completed=validator.complete(valid,registry).weaknesses().getFirst().facts().getFirst();
        assertEquals(45,completed.score());assertEquals(false,completed.correct());assertEquals(false,completed.skipped());
        assertEquals(10L,completed.turnId());assertEquals(3,completed.round());
    }
    @Test void t821InventedWeaknessRejectedButEvidenceFreeBasicPlanAccepted() {
        var empty=registry();
        assertNotNull(validator.validate(answer("HashMap","W1",fact("W1",45)),empty,false,60));
        assertNull(validator.validate(new AgentModels.Answer("Basic practice, no personal diagnosis",List.of(),days(60),List.of()),empty,true,60));
    }
    @Test void t831ExistingEvidenceCannotBeReassignedToNonexistentQuestion() {
        var registry=registry();add(registry,"searchQuestions","[{\"questionId\":201,\"title\":\"HashMap resize\"}]");
        var day=new AgentModels.PlanDay(1,"HashMap",60,List.of("Review"),List.of(999L),List.of(new AgentModels.QuestionRecommendation(999,"Q1","Useful")));
        assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(day),List.of()),registry,false,60));
    }
    @Test void t833To835ReasonsCannotReplaceUncertainOrConflictingTopicEvidence() {
        var registry=registry();add(registry,"searchQuestions","[{\"questionId\":203,\"title\":\"CSS flexbox\",\"content\":\"Frontend layout\"}]");
        for(String topic:List.of("HashMap","Java")) {
            var day=new AgentModels.PlanDay(1,topic,60,List.of("Review"),List.of(203L),List.of(new AgentModels.QuestionRecommendation(203,"Q1","Perfect for this user")));
            assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(),List.of(day),List.of()),registry,false,60));
        }
        assertNull(validator.validate(new AgentModels.Answer("No reliable recommendation",List.of(),days(60),List.of()),registry,true,60));
    }
    @Test void t838EachRequiredEvidenceFieldIsCheckedSeparately() {
        var registry=weakness();var proper=answer("HashMap","W1",fact("W1",45)).weaknesses().getFirst();
        for(var w:List.of(new AgentModels.Weakness("HashMap","x",List.of(1L),List.of(),proper.facts()),
                new AgentModels.Weakness("HashMap","x",List.of(1L),List.of("W1"),List.of()),
                new AgentModels.Weakness("HashMap","x",List.of(1L),List.of("W1"),List.of(new AgentModels.EvidenceFact("W1","",1L,10L,3,45,false,false))))) {
            assertNotNull(validator.validate(new AgentModels.Answer("x",List.of(w),List.of(),List.of()),registry,false,60));
        }
    }
    @Test void conflictingReasonIsReplacedBeforeReturningTheCompletedAnswer() throws Exception {
        var registry=weakness();var w=new AgentModels.Weakness("HashMap","The recorded score was 90",List.of(1L),List.of("W1"),List.of(fact("W1",45)));
        var value=new AgentModels.Answer("x",List.of(w),List.of(),List.of());
        assertNull(validator.validate(value,registry,false,60));
        var completed=validator.complete(value,registry);
        assertEquals(45,completed.weaknesses().getFirst().facts().getFirst().score());
        assertTrue(completed.weaknesses().getFirst().reason().contains("45分"));
        assertFalse(json.writeValueAsString(completed).contains("90"));
        assertTrue(completed.weaknesses().getFirst().reason().contains("未答对"));
    }
    @Test void personalAssessmentMessageCannotReintroduceConflictingScore() throws Exception {
        var value=answer("HashMap","W1",fact("W1",45));
        value=new AgentModels.Answer("You scored 90 and answered correctly",value.weaknesses(),List.of(),List.of());
        var completed=validator.complete(value,weakness());
        assertFalse(json.writeValueAsString(completed).contains("90"));
        assertFalse(completed.message().contains("answered correctly"));
    }
    @Test void summaryReasonDoesNotInventTurnFacts() {
        var registry=registry();add(registry,"analyzeWeaknesses","[{\"sessionId\":1,\"sessionWeaknessesExcerpt\":\"HashMap\"}]");
        var value=answer("HashMap","W1",new AgentModels.EvidenceFact("W1","HashMap",1L,null,null,null,null,null));
        var completed=validator.complete(value,registry).weaknesses().getFirst();
        assertTrue(completed.reason().contains("未提供逐轮评分"));assertNull(completed.facts().getFirst().score());
    }
    private List<AgentModels.PlanDay> days(int minutes) {
        List<AgentModels.PlanDay> days=new ArrayList<>();
        for(int day=1;day<=7;day++) { days.add(new AgentModels.PlanDay(day,"Java",minutes,List.of("Review"),List.of())); }
        return days;
    }
}
