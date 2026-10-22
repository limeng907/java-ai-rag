package com.shtbs.interview.agent;

import com.shtbs.interview.agent.runtime.ToolPolicy;
import com.shtbs.interview.agent.runtime.ExecutionBudget;
import com.shtbs.interview.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolPolicyTest {
    @Test void permissionsRemainReadOnlyAndUnknownKindsFailClosed() {
        assertEquals(Set.of("getUserProfile","searchKnowledge","searchQuestions"),ToolPolicy.allowedTools("CHAT"));
        assertEquals(5,ToolPolicy.allowedTools("STUDY").size());
        assertEquals(ToolPolicy.allowedTools("STUDY"),ToolPolicy.allowedTools("CHAT","请查看我的面试记录"));
        assertEquals(ToolPolicy.allowedTools("CHAT"),ToolPolicy.allowedTools("CHAT","ArrayList 使用什么结构？"));
        assertEquals(Set.of("searchKnowledge","searchQuestions"),ToolPolicy.allowedTools("INTERVIEW"));
        assertThrows(IllegalArgumentException.class,()->ToolPolicy.allowedTools("OTHER"));
    }
    @Test void strictParametersRejectCoercionMissingUnknownAndDuplicateFields() {
        for (String args:List.of("{}","[]","null","{\"limit\":0}","{\"limit\":6}","{\"limit\":1.5}",
                "{\"limit\":\"5\"}","{\"limit\":true}","{\"limit\":null}","{\"limit\":5,\"userId\":2}",
                "{\"limit\":1,\"limit\":2}","{\"limit\":5} {}","{\"limit\":999999999999}")) {
            assertEquals(6003,assertThrows(BusinessException.class,()->ToolPolicy.validateArguments("getInterviewHistory",args),args).getCode());
        }
        assertThrows(BusinessException.class,()->ToolPolicy.validateArguments("searchQuestions","{\"keyword\":\"Java\",\"limit\":11}"));
        assertThrows(BusinessException.class,()->ToolPolicy.validateArguments("searchKnowledge","{\"query\":\" \"}"));
        assertThrows(BusinessException.class,()->ToolPolicy.validateArguments("getUserProfile","{\"executionId\":\"x\"}"));
        assertDoesNotThrow(()->ToolPolicy.validateArguments("getInterviewHistory","{\"limit\":5}"));
        assertDoesNotThrow(()->ToolPolicy.validateArguments("searchQuestions","{\"keyword\":\"Java\",\"limit\":10}"));
        assertDoesNotThrow(()->ToolPolicy.validateArguments("searchKnowledge","{\"query\":\"HashMap\"}"));
        assertDoesNotThrow(()->ToolPolicy.validateArguments("getUserProfile","{}"));
    }
    @Test void emptyEvidenceIsDifferentFromMissingCalls() {
        assertFalse(ToolPolicy.hasRequiredEvidence("STUDY",Map.of()));
        assertFalse(ToolPolicy.hasRequiredEvidence("STUDY",Map.of("getUserProfile",List.of())));
        assertTrue(ToolPolicy.hasRequiredEvidence("STUDY",Map.of("getUserProfile",List.of("{}"),
                "getInterviewHistory",List.of("[]"),"analyzeWeaknesses",List.of("[]"))));
    }
    @Test void reserveLastTwoCallsAndCancellationIsPermanent() {
        var budget=new ExecutionBudget(6,12,48000,60000,20000);
        for (int i=0;i<4;i++) { assertFalse(budget.finishing());budget.nextModel(); }
        assertTrue(budget.finishing());budget.nextModel();budget.nextModel();assertFalse(budget.hasModelCall());
        budget.cancel();assertEquals(6008,assertThrows(BusinessException.class,budget::check).getCode());
    }
}
