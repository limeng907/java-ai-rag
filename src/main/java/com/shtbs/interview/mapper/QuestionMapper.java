package com.shtbs.interview.mapper;
import com.shtbs.interview.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface QuestionMapper {
    void insert(Question question);

    Question selectById(@Param("id") Long id);

   // List<Question> selectAll();

    void update(Question question);

    void deleteById(@Param("id")Long id);

    List<Question> selectQuestions(String category, String difficulty,Integer size,Integer offset);

    Long countQuestions(String category, String difficulty);
}
