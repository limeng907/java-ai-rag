package com.shtbs.interview.vo;

import com.shtbs.interview.enums.InterviewDifficulty;
import com.shtbs.interview.enums.InterviewStatus;

import java.time.LocalDateTime;

public class InterviewSessionVO {
    private Long sessionId;

    private String category;

    private InterviewDifficulty difficulty;

    private InterviewStatus status;


    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public InterviewDifficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(InterviewDifficulty difficulty) {
        this.difficulty = difficulty;
    }

    public InterviewStatus getStatus() {
        return status;
    }

    public void setStatus(InterviewStatus status) {
        this.status = status;
    }

}
