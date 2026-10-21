package com.shtbs.interview.agent;

import com.shtbs.interview.dto.InterviewAnswerDTO;
import com.shtbs.interview.enums.InterviewStatus;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.utils.VectorUtil;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InterviewPolicyTest {
    @Test void seventhAnswerEntersReview() {
        assertEquals(InterviewStatus.REVIEW,InterviewPolicy.afterAnswer(6,0,false).status());
        assertThrows(BusinessException.class,()->InterviewPolicy.afterAnswer(7,0,false));
    }
    @Test void consecutiveSkipsEnterReview() {
        assertEquals(InterviewStatus.IN_PROGRESS,InterviewPolicy.afterAnswer(0,0,true).status());
        assertEquals(InterviewStatus.REVIEW,InterviewPolicy.afterAnswer(1,1,true).status());
    }
    @Test void answerResetsSkipStreak() { assertEquals(0,InterviewPolicy.afterAnswer(1,1,false).skipStreak()); }
    @Test void blankAnswerRequiresExplicitSkip() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();var dto=new InterviewAnswerDTO();
            dto.setAnswer("  ");assertFalse(validator.validate(dto).isEmpty());
            dto.setSkip(true);assertTrue(validator.validate(dto).isEmpty());
            dto.setAnswer("HashMap");assertFalse(validator.validate(dto).isEmpty());
        }
    }
    @Test void answerLengthIsBounded() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var dto=new InterviewAnswerDTO();dto.setAnswer("x".repeat(4001));
            assertFalse(factory.getValidator().validate(dto).isEmpty());
        }
    }
    @Test void cosineIsScaleIndependent() {
        assertEquals(1,VectorUtil.cosineSimilarity(new float[]{2,0},new float[]{4,0}),1e-12);
        assertEquals(0,VectorUtil.cosineSimilarity(new float[]{2,0},new float[]{0,4}),1e-12);
        assertEquals(-1,VectorUtil.cosineSimilarity(new float[]{2,0},new float[]{-4,0}),1e-12);
    }
    @Test void invalidVectorsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->VectorUtil.cosineSimilarity(new float[]{0,0},new float[]{1,0}));
        assertThrows(IllegalArgumentException.class,()->VectorUtil.cosineSimilarity(new float[]{1},new float[]{1,0}));
    }
}
