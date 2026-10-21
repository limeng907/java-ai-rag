package com.shtbs.interview.controller;

import com.shtbs.interview.common.Result;
import com.shtbs.interview.dto.AiChatRequest;
import com.shtbs.interview.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/chat")
    public Result<String> chat(@Valid @RequestBody AiChatRequest request) {
        String answer=aiService.chat(request.getMessage());
        return Result.success(answer);
    }
}

