package com.shtbs.interview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.shtbs.interview.agent.*;
import com.shtbs.interview.common.Result;
import com.shtbs.interview.context.UserContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentService service;
    private final AgentRepository repository;
    public AgentController(AgentService service,AgentRepository repository) { this.service=service;this.repository=repository; }
    @PostMapping("/chat") public Result<AgentModels.Response> chat(@Valid @RequestBody AgentModels.Request request) {
        return Result.success(service.handle(UserContext.getUserId(),request,false));
    }
    @PostMapping("/study-plans") public Result<AgentModels.Response> study(@Valid @RequestBody AgentModels.Request request) {
        return Result.success(service.handle(UserContext.getUserId(),request,true));
    }
    @GetMapping("/profile") public Result<AgentModels.Profile> profile() { return Result.success(repository.profile(UserContext.getUserId())); }
    @PutMapping("/profile") public Result<Void> profile(@Valid @RequestBody AgentModels.Profile profile) {
        repository.profile(UserContext.getUserId(),profile);return Result.success();
    }
    @GetMapping("/conversations") public Result<List<Map<String,Object>>> conversations(
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(service.conversations(UserContext.getUserId(),page,size));
    }
    @GetMapping("/conversations/{id}/messages") public Result<List<Map<String,Object>>> messages(
            @PathVariable UUID id,@RequestParam(defaultValue="0") long afterId) {
        return Result.success(service.messages(UserContext.getUserId(),id,afterId));
    }
    @DeleteMapping("/conversations/{id}") public Result<Void> delete(@PathVariable UUID id) {
        service.delete(UserContext.getUserId(),id);return Result.success();
    }
    @GetMapping("/executions/{id}") public Result<Map<String,Object>> execution(@PathVariable UUID id) {
        return Result.success(service.execution(UserContext.getUserId(),id));
    }
    @GetMapping("/executions") public Result<List<Map<String,Object>>> executions(
            @RequestParam(required=false) Long sessionId,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return Result.success(service.executions(UserContext.getUserId(),sessionId,page,size));
    }
    @GetMapping("/study-plans") public Result<List<Map<String,Object>>> plans(
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(service.plans(UserContext.getUserId(),page,size));
    }
    @GetMapping("/study-plans/{id}") public Result<JsonNode> plan(@PathVariable long id) {
        return Result.success(service.plan(UserContext.getUserId(),id));
    }
}
