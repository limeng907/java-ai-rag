package com.shtbs.interview.agent;

import com.shtbs.interview.agent.memory.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shtbs.interview.agent.memory.MemoryFacts.Field.*;
import static com.shtbs.interview.agent.memory.MemoryFacts.Status.*;

class MemoryFactsTest {
    private MemoryRepository.Row user(long id,String text) { return new MemoryRepository.Row(id,UUID.randomUUID(),"USER",text); }
    @Test void explicitStatementHasExactMessageSource() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天只有45分钟")),false);
        assertEquals(FOUND,result.status());assertEquals("45",result.value());
        assertEquals(new MemoryFacts.Source(1,"我每天只有45分钟"),result.sources().getFirst());
    }
    @Test void missingFactNeverGuesses() {
        var result=MemoryFacts.resolve(INTERVIEW_COMPANY,List.of(user(1,"讨论HashMap")),false);
        assertEquals(NOT_FOUND,result.status());assertNull(result.value());assertTrue(result.sources().isEmpty());
    }
    @Test void assistantClaimsNeverBecomeUserFacts() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(new MemoryRepository.Row(1,UUID.randomUUID(),"ASSISTANT","我每天只有45分钟")),false);
        assertEquals(NOT_FOUND,result.status());
    }
    @Test void conflictingStatementsAreNotSilentlyResolved() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天只有45分钟"),user(2,"我每天只有30分钟")),false);
        assertEquals(CONFLICT,result.status());assertNull(result.value());assertEquals(2,result.sources().size());
    }
    @Test void explicitCorrectionSupersedesEarlierStatement() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天只有45分钟"),user(2,"我现在每天只能学习30分钟")),false);
        assertEquals(FOUND,result.status());assertEquals("30",result.value());assertEquals(2,result.sources().getFirst().messageId());
    }
    @Test void conditionalQuotedAndQuestionStatementsAreNotFacts() {
        for (String text:List.of("假设我每天只有45分钟","如果我每天只有45分钟","“我每天只有45分钟”","我每天学习45分钟？")) {
            assertEquals(NOT_FOUND,MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,text)),false).status(),text);
        }
    }
    @Test void unparseableRelevantStatementPreventsStaleAnswer() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天只有45分钟"),user(2,"我现在每天学习时间不确定")),false);
        assertEquals(CONFLICT,result.status());assertNull(result.value());
    }
    @Test void rangeAndMultipleValuesAreNotReducedToOne() {
        assertEquals(CONFLICT,MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天学习30到45分钟")),false).status());
        assertEquals(CONFLICT,MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天学习999分钟")),false).status());
    }
    @Test void explicitCompanyStatementHasProvenance() {
        var result=MemoryFacts.resolve(INTERVIEW_COMPANY,List.of(user(4,"我之前面试过星河公司。")),false);
        assertEquals(FOUND,result.status());assertEquals("星河",result.value());assertEquals(4,result.sources().getFirst().messageId());
    }
    @Test void ambiguousCompanyDoesNotBecomeCertain() {
        for (String text:List.of("我之前面试过某家公司","我之前面试过星河和银河公司","我面试过可能是星河公司")) {
            assertEquals(CONFLICT,MemoryFacts.resolve(INTERVIEW_COMPANY,List.of(user(1,text)),false).status());
        }
    }
    @Test void boundedLookupReportsItsLimit() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天只有45分钟")),true);
        assertEquals(NOT_FOUND,result.status());assertTrue(result.lookupLimited());assertNull(result.value());
    }
    @Test void recallRoutingDoesNotHijackGeneralAdvice() {
        assertEquals(DAILY_MINUTES,MemoryFacts.question("我之前说每天能学习多少分钟？"));
        assertEquals(INTERVIEW_COMPANY,MemoryFacts.question("我之前面试的是哪家公司？"));
        assertNull(MemoryFacts.question("每天学习多少分钟比较合理？"));
    }
    @Test void earlierRecallQuestionDoesNotContaminateTheNextRecall() {
        var result=MemoryFacts.resolve(DAILY_MINUTES,List.of(user(1,"我每天只有45分钟"),user(2,"我每天能学习多少分钟")),false);
        assertEquals(FOUND,result.status());assertEquals("45",result.value());
    }
    @Test void onlyExplicitShortStatementsReceiveDeterministicAcknowledgement() {
        assertTrue(MemoryFacts.acknowledgement("我每天只有45分钟").contains("45分钟"));
        assertNull(MemoryFacts.acknowledgement("我每天学习多久比较好？"));
        assertNull(MemoryFacts.acknowledgement("假设我每天只有45分钟"));
        assertNull(MemoryFacts.acknowledgement("我每天学习30到45分钟"));
    }
}
