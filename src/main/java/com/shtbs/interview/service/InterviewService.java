package com.shtbs.interview.service;

import com.shtbs.interview.dto.InterviewAnswerDTO;
import com.shtbs.interview.dto.InterviewStartDTO;
import com.shtbs.interview.entity.InterviewSession;
import com.shtbs.interview.vo.InterviewAnswerVO;
import com.shtbs.interview.vo.InterviewFinishVO;
import com.shtbs.interview.vo.InterviewSessionVO;
import com.shtbs.interview.vo.InterviewStartVO;

public interface InterviewService {
    InterviewStartVO startInterview(Long userId, InterviewStartDTO dto);

    InterviewAnswerVO answer(Long sessionId, InterviewAnswerDTO dto);

    InterviewFinishVO finishInterview(Long sessionId);

    java.util.List<java.util.Map<String,Object>> list(long userId,int page,int size);
    java.util.List<java.util.Map<String,Object>> review(long userId,long sessionId);
    java.util.List<java.util.Map<String,Object>> wrong(long userId,int page,int size);
}
