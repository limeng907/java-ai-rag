package com.shtbs.interview.vo;

import java.util.List;

public class InterviewFinishVO {
    private Long sessionId;
    private java.util.UUID executionId;
    public java.util.UUID getExecutionId() { return executionId; }
    public void setExecutionId(java.util.UUID value) { executionId = value; }

    private Integer score;

    private List<String> strengths;

    private List<String> weaknesses;

    private List<String> suggestions;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }
}
