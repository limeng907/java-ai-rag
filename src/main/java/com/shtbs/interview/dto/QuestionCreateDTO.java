package com.shtbs.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class QuestionCreateDTO {

    @NotBlank(message="分类不能为空")
    private String category;

    @NotBlank(message="标题不能为空")
    @Size(max = 200,message="标题不能超过200个字符")
    private String title;

    @NotBlank(message="内容不能为空")
    private String content;

    @NotBlank(message="难度不能为空")
    private String difficulty;

    @NotBlank(message="答案不能为空")
    private String answer;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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
