package com.shtbs.interview.mapper;

import com.shtbs.interview.entity.InterviewEvaluation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewEvaluationMapper {
    int insert(InterviewEvaluation evaluation);
}
