package com.shtbs.interview.controller;

import com.shtbs.interview.common.Result;
import com.shtbs.interview.security.RequireAdmin;
import com.shtbs.interview.service.KnowledgeService;
import com.shtbs.interview.service.KnowledgeSyncService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeSyncService knowledgeSyncService;

    public KnowledgeController(KnowledgeService knowledgeService,KnowledgeSyncService knowledgeSyncService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeSyncService=knowledgeSyncService;
    }

    @PostMapping("/import")
    @RequireAdmin
    public Result<KnowledgeSyncService.SyncResult> importKnowledge()
            throws IOException {

        KnowledgeSyncService.SyncResult result =
                knowledgeSyncService.sync();

        return Result.success(result);
    }

    @GetMapping("/search")
    public List<Document> searchKnowledge(
            @RequestParam String question) {

        return knowledgeService.searchKnowledge(question);
    }
}
