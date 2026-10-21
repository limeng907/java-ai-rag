package com.shtbs.interview.dto;

import com.shtbs.interview.enums.InterviewDifficulty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InterviewStartDTO {
    @NotBlank(message = "面试方向不能为空")
    @jakarta.validation.constraints.Size(max = 50)
    private String category;

    @NotNull(message = "面试难度不能为空")
    private InterviewDifficulty difficulty;

    @jakarta.validation.constraints.Size(max = 5)
    private java.util.List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long> knowledgeBaseIds;

    public java.util.List<Long> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(java.util.List<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }

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
}
