package com.shtbs.interview.vo;

public class InterviewStartVO {

    private Long sessionId;

    private String question;
    private java.util.UUID executionId;
    public java.util.UUID getExecutionId() { return executionId; }
    public void setExecutionId(java.util.UUID value) { executionId = value; }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
