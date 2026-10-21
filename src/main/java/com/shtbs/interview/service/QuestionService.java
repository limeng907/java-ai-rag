package com.shtbs.interview.service;

import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.dto.QuestionCreateDTO;
import com.shtbs.interview.dto.QuestionUpdateDTO;
import com.shtbs.interview.entity.Question;
import com.shtbs.interview.vo.QuestionVO;

import java.util.List;

public interface QuestionService {
    void addQuestion(QuestionCreateDTO dto);

    QuestionVO getQuestionById(Long id);

  //  List<Question> getAllQuestions();

    void updateQuestion(Long id,QuestionUpdateDTO dto);

    void deleteQuestion(Long id);

    PageResult<QuestionVO> getQuestions(String category, String difficulty, Integer page, Integer size);

}
