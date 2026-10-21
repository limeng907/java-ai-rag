package com.shtbs.interview.controller;

import com.shtbs.interview.common.PageResult;
import com.shtbs.interview.common.Result;
import com.shtbs.interview.dto.QuestionCreateDTO;
import com.shtbs.interview.dto.QuestionUpdateDTO;
import com.shtbs.interview.entity.Question;
import com.shtbs.interview.service.QuestionService;
import com.shtbs.interview.vo.QuestionVO;
import jakarta.validation.Valid;
import com.shtbs.interview.security.RequireAdmin;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/question")
public class QuestionController {
    private final QuestionService questionService;
    public QuestionController(QuestionService questionService){
        this.questionService=questionService;
    }

    @PostMapping()
    @RequireAdmin
    public Result<Void> addQuestion(@Valid @RequestBody QuestionCreateDTO dto){
        questionService.addQuestion(dto);

        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<QuestionVO> getQuestionById(@PathVariable Long id){
        QuestionVO questionVO=questionService.getQuestionById(id);
        return Result.success(questionVO);
    }

   /* @GetMapping()
    public List<Question> getAllQuestions(){
        return questionService.getAllQuestions();
    }*/

    @PatchMapping("/{id}")
    @RequireAdmin
    public Result<Void> updateQuestion(@PathVariable Long id,
             @Valid @RequestBody QuestionUpdateDTO dto){

         questionService.updateQuestion(id,dto);

         return Result.success();
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    public Result<Void> deleteQuestion(@PathVariable Long id){
        questionService.deleteQuestion(id);

        return Result.success();
    }

    //根据类别和难度来选择问题
    @GetMapping
    public Result<PageResult<QuestionVO>>getQuestions(
            @RequestParam(required = false)String category,
            @RequestParam(required = false)String difficulty,
            @RequestParam(defaultValue = "1")Integer page,
            @RequestParam(defaultValue ="10")Integer size
    ){
         PageResult<QuestionVO>result=questionService.getQuestions(category,difficulty,page,size);

        return Result.success(result);
}

}
