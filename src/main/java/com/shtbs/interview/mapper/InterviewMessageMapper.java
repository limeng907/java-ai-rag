package com.shtbs.interview.mapper;

import com.shtbs.interview.entity.InterviewMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InterviewMessageMapper {
    int insert(InterviewMessage messgae);

    List<InterviewMessage> selectBySessionId(
            @Param("sessionId") Long sessionId
    );
}
