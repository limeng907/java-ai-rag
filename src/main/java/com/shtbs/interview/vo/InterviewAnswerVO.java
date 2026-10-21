package com.shtbs.interview.vo;

public class InterviewAnswerVO {

    private Long sessionId;

    private String nextQuestion;

    private Integer completedRounds;
    private com.shtbs.interview.enums.InterviewStatus status;
    private java.util.UUID requestId;
    private java.util.UUID executionId;
    public java.util.UUID getExecutionId() { return executionId; }
    public void setExecutionId(java.util.UUID value) { executionId = value; }

    public Integer getCompletedRounds() { return completedRounds; }
    public void setCompletedRounds(Integer value) { completedRounds = value; }
    public com.shtbs.interview.enums.InterviewStatus getStatus() { return status; }
    public void setStatus(com.shtbs.interview.enums.InterviewStatus value) { status = value; }
    public java.util.UUID getRequestId() { return requestId; }
    public void setRequestId(java.util.UUID value) { requestId = value; }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getNextQuestion() {
        return nextQuestion;
    }

    public void setNextQuestion(String nextQuestion) {
        this.nextQuestion = nextQuestion;
    }
}
