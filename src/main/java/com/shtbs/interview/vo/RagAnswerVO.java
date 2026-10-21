package com.shtbs.interview.vo;

import java.util.List;

public class RagAnswerVO {

    private String answer;
    private List<String> sources;

    public RagAnswerVO(String answer, List<String> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public List<String> getSources() {
        return sources;
    }
}