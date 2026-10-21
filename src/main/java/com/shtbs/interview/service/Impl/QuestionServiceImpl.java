package com.shtbs.interview.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.dto.QuestionCreateDTO;
import com.shtbs.interview.dto.QuestionUpdateDTO;
import com.shtbs.interview.entity.Question;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.mapper.QuestionMapper;
import com.shtbs.interview.service.QuestionService;
import com.shtbs.interview.vo.QuestionVO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class QuestionServiceImpl implements QuestionService {
    private  final  QuestionMapper questionMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Logger log =
            LoggerFactory.getLogger(QuestionServiceImpl.class);

    public QuestionServiceImpl(QuestionMapper questionMapper,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper){
        this.questionMapper=questionMapper;
        this.redisTemplate=redisTemplate;
        this.objectMapper=objectMapper;
    }

    public void addQuestion(QuestionCreateDTO dto){
        log.info("开始添加啊题目,title={},category={}",
                dto.getTitle(),
                dto.getCategory());

        Question question=new Question();
        question.setCategory(dto.getCategory());
        question.setTitle(dto.getTitle());
        question.setContent(dto.getContent());
        question.setDifficulty(dto.getDifficulty());
        question.setAnswer(dto.getAnswer());

        questionMapper.insert(question);
        log.info("题目添加成功，id={}", question.getId());
    }

    public QuestionVO getQuestionById(Long id){
        log.info("开始查询题目");

        //先查询redis

        String key="question:"+id;
        String json=redisTemplate.opsForValue().get(key);
        if(json!=null){
            try{
                return objectMapper.readValue(
                        json,QuestionVO.class
                );
            }catch (Exception e){
                throw new RuntimeException(e);
            }
        }
        // 3. Redis 没有，查询 MySQL
        Question question =
                questionMapper.selectById(id);

        if(question==null){
            log.warn("找不到题目");
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
        }

        QuestionVO questionVO =
                convertToVO(question);

        // 4. 转 JSON
        try {

            String value =
                    objectMapper.writeValueAsString(
                            questionVO
                    );

            // 5. 写 Redis，10分钟过期
            redisTemplate
                    .opsForValue()
                    .set(
                            key,
                            value,
                            10,
                            TimeUnit.MINUTES
                    );

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        log.error("查询成功");
         return questionVO;
    }


    /*public List<Question> getAllQuestions(){
        return questionMapper.selectAll();
    }*/

    public void updateQuestion(Long id,QuestionUpdateDTO dto){
        log.info("开始更新题目");
        Question question =
                questionMapper.selectById(id);

        if (question == null) {
            throw new BusinessException(
                    ErrorCode.QUESTION_NOT_FOUND
            );
        }

        if (dto.getCategory() != null) {
            question.setCategory(dto.getCategory());
        }

        if (dto.getTitle() != null) {
            question.setTitle(dto.getTitle());
        }

        if (dto.getContent() != null) {
            question.setContent(dto.getContent());
        }

        if (dto.getDifficulty() != null) {
            question.setDifficulty(dto.getDifficulty());
        }

        if (dto.getAnswer() != null) {
            question.setAnswer(dto.getAnswer());
        }

        //1.更新数据库
        questionMapper.update(question);
         log.info("更新成功，题目id={}",id);
         //2.删除缓存
        String key="question:"+question.getId();
        redisTemplate.delete(key);
    }

    public void deleteQuestion(Long id){
        log.info("开始删除题目，id={}", id);

        Question question = questionMapper.selectById(id);
        if(question==null){
            log.warn("删除题目失败，题目不存在，id={}",id);
            throw new BusinessException(
                    ErrorCode.QUESTION_NOT_FOUND
            );
        }

        questionMapper.deleteById(id);
        log.info("题目删除成功，id={}", id);

        String key="question:"+id;
        redisTemplate.delete(key);
    }
    public PageResult<QuestionVO> getQuestions(String category, String difficulty, Integer page, Integer size){
        if (page == null || size == null || page < 1 || page > 1000000 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        log.info("开始筛选题目");
        int offset=(page-1)*size;

        List<Question> questionList=questionMapper.selectQuestions(category,difficulty,size,offset);

        if(questionList==null){
            log.warn("找不到符合要求的题目，category={}，difficulty={}",category,difficulty);
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
        }

        Long total =
                questionMapper.countQuestions(
                        category,
                        difficulty
                );

        List<QuestionVO> vos =
                questionList.stream()
                        .map(this::convertToVO)
                        .toList();
        log.info("筛选成功");

        return new PageResult<>(
                vos,
                total,
                page,
                size
        );
    }

//辅助方法
    private QuestionVO convertToVO(Question question) {

        QuestionVO vo = new QuestionVO();

        vo.setId(question.getId());

        vo.setCategory(question.getCategory());

        vo.setTitle(question.getTitle());

        vo.setDifficulty(question.getDifficulty());

        return vo;
    }

}
