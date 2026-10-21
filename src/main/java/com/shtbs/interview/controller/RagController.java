package com.shtbs.interview.controller;

import com.shtbs.interview.common.Result;
import com.shtbs.interview.dto.RagQuestionDTO;
import com.shtbs.interview.service.RagService;
import com.shtbs.interview.vo.RagAnswerVO;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/knowledge")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/chat")
    public Result<RagAnswerVO> chat(
            @Valid @RequestBody RagQuestionDTO dto) {

        RagAnswerVO result =
                ragService.chat(dto.getQuestion());

        return Result.success(result);
    }
}