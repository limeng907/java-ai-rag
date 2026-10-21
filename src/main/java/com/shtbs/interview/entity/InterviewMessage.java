package com.shtbs.interview.entity;

import com.shtbs.interview.enums.InterviewRole;

import java.time.LocalDateTime;

public class InterviewMessage {

    private Long id;

    private Long sessionId;

    private InterviewRole role;

    private String content;

    private LocalDateTime createTime;

    // getter setter

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public InterviewRole getRole() {
        return role;
    }

    public void setRole(InterviewRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}