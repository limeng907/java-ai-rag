package com.shtbs.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.agent.memory.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SummaryConstraintsTest {
    private final ObjectMapper json=new ObjectMapper();
    private String summary(String statement) throws Exception {
        return json.writeValueAsString(Map.of("userStatements",List.of(statement),"preferences",List.of(),"topics",List.of("Spring Boot"),"openQuestions",List.of(),"historicalAssistantSuggestions",List.of("建议每天学习90分钟")));
    }
    private MemoryRepository.Row row(long id,String text) { return new MemoryRepository.Row(id,UUID.randomUUID(),"USER",text); }
    @Test void droppedBudgetIsRestoredFromOriginalStatement() throws Exception {
        var result=json.readTree(SummaryConstraints.preserve(summary("用户正在学习Java"),List.of(row(1,"我每天只有45分钟")),json));
        assertTrue(result.path("userStatements").toString().contains("45"));assertTrue(result.path("topics").toString().contains("Spring Boot"));
    }
    @Test void correctionReplacesStaleBudgetAcrossIncrementalSummaries() throws Exception {
        var rows=List.of(row(1,"我每天只有45分钟"),row(2,"我现在每天只能学习30分钟"));
        String result=summary("用户每天学习45分钟");
        for(int i=0;i<3;i++) { result=SummaryConstraints.preserve(result,rows,json); }
        assertTrue(json.readTree(result).path("userStatements").toString().contains("30"));
        assertFalse(json.readTree(result).path("userStatements").toString().contains("45"));
        assertTrue(json.readTree(result).path("historicalAssistantSuggestions").toString().contains("90"));
    }
    @Test void conflictingBudgetsRemainUnresolved() throws Exception {
        var result=SummaryConstraints.preserve(summary("用户每天学习45分钟"),List.of(row(1,"我每天只有45分钟"),row(2,"我每天只有30分钟")),json);
        assertTrue(json.readTree(result).path("userStatements").toString().contains("冲突"));
    }
    @Test void assistantSuggestionCannotOverrideAUserConstraint() throws Exception {
        var rows=List.of(row(1,"我每天只有45分钟"),new MemoryRepository.Row(2,UUID.randomUUID(),"ASSISTANT","我每天只有90分钟"));
        var result=SummaryConstraints.preserve(summary("用户每天学习90分钟"),rows,json);
        assertFalse(json.readTree(result).path("userStatements").toString().contains("90"));
    }
    @Test void fixingBudgetDoesNotDiscardGoalOrLearningPreferenceInSameSentence() throws Exception {
        var result=SummaryConstraints.preserve(summary("用户准备Java后端实习，每天学习90分钟，偏好项目实践"),List.of(row(1,"我每天只有45分钟")),json);
        var statements=json.readTree(result).path("userStatements").toString();
        assertTrue(statements.contains("后端实习"));assertTrue(statements.contains("项目实践"));assertTrue(statements.contains("45"));assertFalse(statements.contains("90"));
    }
    @Test void staleBudgetWithDifferentWordingIsRemovedDuringIncrementalUpdate() throws Exception {
        var rows=List.of(row(1,"我每天只有45分钟"),row(2,"我现在每天只能学习30分钟"));
        for(String old:List.of("用户原先学习时间45分钟","用户最初可用45分钟，已讨论Spring Boot","用户从45分钟改成30分钟")) {
            var result=SummaryConstraints.preserve(summary(old),rows,json);
            var statements=json.readTree(result).path("userStatements").toString();
            assertFalse(statements.contains("45"),statements);assertTrue(statements.contains("30"),statements);
        }
    }
}
