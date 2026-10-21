package com.shtbs.interview.ai;

import java.util.List;

public class AnswerAnalysis {
    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(100)
    private Integer score;

    @jakarta.validation.constraints.NotNull
    private Boolean correct;

    @jakarta.validation.constraints.NotNull
    private Boolean complete;

    @jakarta.validation.constraints.NotNull
    private Boolean needFollowUp;

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(max = 10)
    private List<@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String> missingPoints;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 2000)
    private String nextQuestion;

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public void setCorrect(Boolean correct) {
        this.correct = correct;
    }

    public Boolean getComplete() {
        return complete;
    }

    public void setComplete(Boolean complete) {
        this.complete = complete;
    }

    public Boolean getNeedFollowUp() {
        return needFollowUp;
    }

    public void setNeedFollowUp(Boolean needFollowUp) {
        this.needFollowUp = needFollowUp;
    }

    public List<String> getMissingPoints() {
        return missingPoints;
    }

    public void setMissingPoints(List<String> missingPoints) {
        this.missingPoints = missingPoints;
    }

    public String getNextQuestion() {
        return nextQuestion;
    }

    public void setNextQuestion(String nextQuestion) {
        this.nextQuestion = nextQuestion;
    }
}
