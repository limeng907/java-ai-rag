package com.shtbs.interview.controller;

import com.shtbs.interview.agent.AgentModels;
import com.shtbs.interview.agent.PromptService;
import com.shtbs.interview.common.Result;
import com.shtbs.interview.security.RequireAdmin;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/prompts")
@RequireAdmin
public class PromptController {
    private final PromptService prompts;
    public PromptController(PromptService prompts) { this.prompts=prompts; }
    @GetMapping public Result<List<PromptService.Template>> list() { return Result.success(prompts.list()); }
    @PostMapping("/{name}") public Result<Void> create(@PathVariable String name,@Valid @RequestBody AgentModels.PromptRequest request) {
        prompts.create(name,request);return Result.success();
    }
    @PutMapping("/{name}/active/{version}") public Result<Void> activate(@PathVariable String name,@PathVariable int version) {
        prompts.activate(name,version);return Result.success();
    }
}
