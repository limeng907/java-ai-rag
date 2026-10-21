package com.shtbs.interview.mapper;

import com.shtbs.interview.entity.InterviewSession;
import com.shtbs.interview.enums.InterviewStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDateTime;

@Mapper
public interface InterviewSessionMapper {

    int insert(InterviewSession session);

    InterviewSession selectById(Long id);

    int finishInterview(@Param("id")Long id,
                        @Param("status")InterviewStatus status,
                        @Param("endTime")LocalDateTime endTime);
}
