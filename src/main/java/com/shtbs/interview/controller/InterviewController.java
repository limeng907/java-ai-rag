package com.shtbs.interview.controller;

import com.shtbs.interview.common.Result;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.dto.InterviewAnswerDTO;
import com.shtbs.interview.dto.InterviewStartDTO;
import com.shtbs.interview.service.InterviewService;
import com.shtbs.interview.vo.InterviewFinishVO;
import com.shtbs.interview.vo.InterviewSessionVO;
import com.shtbs.interview.vo.InterviewStartVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/interview")
public class InterviewController {
    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService){
        this.interviewService=interviewService;
    }

    @PostMapping
    public Result<InterviewStartVO> startInterview(
            @Valid
            @RequestBody
            InterviewStartDTO dto) {

        Long userId = UserContext.getUserId();

        InterviewStartVO vo =
                interviewService
                        .startInterview(userId, dto);

        return Result.success(vo);
    }

    @PostMapping("/{sessionId}/answer")
    public Result<?> answer(
            @PathVariable Long sessionId,
            @Valid @RequestBody InterviewAnswerDTO dto) {

        return Result.success(
                interviewService.answer(
                        sessionId,
                        dto
                )
        );
    }

    @PostMapping("/{sessionId}/finish")
    public Result<InterviewFinishVO> finishInterview(
            @PathVariable Long sessionId) {

        InterviewFinishVO vo =
                interviewService
                        .finishInterview(sessionId);

        return Result.success(vo);
    }

    @GetMapping
    public Result<java.util.List<java.util.Map<String,Object>>> list(
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(interviewService.list(UserContext.getUserId(),page,size));
    }

    @GetMapping("/{sessionId}/review")
    public Result<java.util.List<java.util.Map<String,Object>>> review(@PathVariable long sessionId) {
        return Result.success(interviewService.review(UserContext.getUserId(),sessionId));
    }

    @GetMapping("/wrong-answers")
    public Result<java.util.List<java.util.Map<String,Object>>> wrong(
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(interviewService.wrong(UserContext.getUserId(),page,size));
    }
}
