package com.shtbs.interview.dto;

import jakarta.validation.constraints.Size;

public class QuestionUpdateDTO {

    @Size(max = 200, message = "标题不能超过200个字符")
    private String title;

    private String category;

    private String content;

    private String difficulty;

    private String answer;

    // getter/setter

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
