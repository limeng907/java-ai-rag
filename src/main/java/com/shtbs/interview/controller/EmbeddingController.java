package com.shtbs.interview.controller;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/ai/embedding")
public class EmbeddingController {

    private final EmbeddingModel embeddingModel;

    public EmbeddingController(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostMapping
    public Map<String, Object> embedding(
            @RequestBody Map<String, String> request) {

        String text = request.get("text");

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }

        // 调用 Embedding 模型
        float[] vector = embeddingModel.embed(text);

        return Map.of(
                "text", text,
                "dimensions", vector.length,
                "vectorPreview", Arrays.copyOf(
                        vector, Math.min(10, vector.length))
        );
    }
}