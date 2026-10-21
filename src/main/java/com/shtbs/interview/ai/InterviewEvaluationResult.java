package com.shtbs.interview.ai;

import java.util.List;

public class InterviewEvaluationResult {

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(100)
    private Integer score;

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(max = 10)
    private List<@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String> strengths;

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(max = 10)
    private List<@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String> weaknesses;

    @jakarta.validation.constraints.NotNull
    @jakarta.validation.constraints.Size(max = 10)
    private List<@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String> suggestions;

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
