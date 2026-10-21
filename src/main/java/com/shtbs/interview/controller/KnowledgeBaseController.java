package com.shtbs.interview.controller;

import com.shtbs.interview.common.Result;
import com.shtbs.interview.context.UserContext;
import com.shtbs.interview.dto.KnowledgeBaseCreateDTO;
import com.shtbs.interview.knowledge.KnowledgeBaseManagementService;
import com.shtbs.interview.knowledge.KnowledgeRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseManagementService bases;

    public KnowledgeBaseController(KnowledgeBaseManagementService bases) {
        this.bases = bases;
    }

    @PostMapping
    public Result<KnowledgeRepository.Base> create(@Valid @RequestBody KnowledgeBaseCreateDTO request) {
        return Result.success(bases.create(UserContext.getUserId(),
                request.name(), request.description()));
    }

    @GetMapping
    public Result<List<KnowledgeRepository.Base>> list() {
        return Result.success(bases.list(UserContext.getUserId()));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeRepository.Base> get(@PathVariable long id) {
        return Result.success(bases.requireOwned(UserContext.getUserId(), id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        bases.delete(UserContext.getUserId(), id);
        return Result.success();
    }
}
