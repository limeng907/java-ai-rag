package com.shtbs.interview.dto;

import jakarta.validation.constraints.NotBlank;

public class InterviewAnswerDTO {

    @jakarta.validation.constraints.Size(max = 4000)
    private String answer;

    private boolean skip;
    private java.util.UUID requestId;
    @jakarta.validation.constraints.Min(1)
    @jakarta.validation.constraints.Max(7)
    private Integer expectedRound;

    @jakarta.validation.constraints.AssertTrue(message = "回答不能为空；跳过时请设置skip=true且不要提供回答")
    public boolean isAnswerValid() { return skip ? answer == null || answer.isBlank() : answer != null && !answer.isBlank(); }
    public boolean isSkip() { return skip; }
    public void setSkip(boolean skip) { this.skip = skip; }
    public java.util.UUID getRequestId() { return requestId; }
    public void setRequestId(java.util.UUID requestId) { this.requestId = requestId; }
    public Integer getExpectedRound() { return expectedRound; }
    public void setExpectedRound(Integer expectedRound) { this.expectedRound = expectedRound; }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
