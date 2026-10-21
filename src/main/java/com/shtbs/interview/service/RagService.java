package com.shtbs.interview.service;

import com.shtbs.interview.vo.RagAnswerVO;

public interface RagService {

    RagAnswerVO chat(String question);
    public String chatWithAdvisor(String question);
}
