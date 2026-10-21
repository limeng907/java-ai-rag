package com.shtbs.interview.service.Impl;

import com.shtbs.interview.service.KnowledgeService;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;

import java.util.List;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {
    private final VectorStore vectorStore;
    private final Resource resource;

    public KnowledgeServiceImpl(
            VectorStore vectorStore,
            @Value("classpath:knowledge/hashmap.txt") Resource resource){
                this.vectorStore=vectorStore;
                this.resource=resource;
    }

    public int importKnowledge() {

        // 1. Extract：读取文件
        TextReader reader = new TextReader(resource);

        reader.getCustomMetadata().put("category", "Java");
        reader.getCustomMetadata().put("topic", "HashMap");
        reader.getCustomMetadata().put("source", "hashmap.txt");

        List<Document> documents = reader.read();

        // 2. Transform：将长文本拆成 Chunk
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)//每个Chunk的目标大小为500token
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(5)//最小文本长度判断阈值，小于它的不会被保留
                .withPunctuationMarks(
                        List.of('。', '！', '？', '；', '\n'))//遇到这些字符时，可以把它们作为候选断句位置
                .build();

        List<Document> chunks = splitter.apply(documents);//执行切分

        System.out.println("Chunk总数：" + chunks.size());

        for (int i = 0; i < chunks.size(); i++) {

            Document chunk = chunks.get(i);

            System.out.println("========== Chunk " + (i + 1) + " ==========");

            System.out.println("文本内容：");
            System.out.println(chunk.getText());

            System.out.println("字符长度：" + chunk.getText().length());

            System.out.println("Metadata：" + chunk.getMetadata());
        }

        // 3. Load：向量化并写入 PostgreSQL
        vectorStore.add(chunks);

        return chunks.size();
    }

    public List<Document> searchKnowledge(String question) {

        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(3)
                        .filterExpression(new FilterExpressionBuilder()
                                .eq("ingest_owner", "knowledge-sync-v1").build())
                        .build()
        );
    }

}
