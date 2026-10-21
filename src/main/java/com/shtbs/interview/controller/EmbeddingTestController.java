package com.shtbs.interview.controller;

import com.shtbs.interview.utils.VectorUtil;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ai/embedding/test")
public class EmbeddingTestController {

    private final EmbeddingModel embeddingModel;

    public EmbeddingTestController(
            EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @GetMapping
    public Map<String, Object> testSimilarity() {

        String textA = "HashMap底层采用什么数据结构？";
        String textB = "HashMap内部如何存储键值对？";
        String textC = "Spring Boot如何发送邮件？";

        // 第一步：文本转换为向量
        float[] vectorA = embeddingModel.embed(textA);
        float[] vectorB = embeddingModel.embed(textB);
        float[] vectorC = embeddingModel.embed(textC);

        // 第二步：计算余弦相似度
        double similarityAB =
                VectorUtil.cosineSimilarity(vectorA, vectorB);

        double similarityAC =
                VectorUtil.cosineSimilarity(vectorA, vectorC);

        return Map.of(
                "dimensions", vectorA.length,
                "similarityAB", similarityAB,
                "similarityAC", similarityAC
        );
    }
}