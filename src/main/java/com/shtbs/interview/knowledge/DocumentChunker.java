package com.shtbs.interview.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DocumentChunker {
    public static final String STRATEGY = "token-500-v1";
    public static final String PARSER = "tika-text-v1";

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(500).build();

    public List<Document> chunks(KnowledgeRepository.DocumentRow document,
                                 KnowledgeRepository.VersionRow version, byte[] bytes) {
        return chunks(document, version, bytes, null);
    }

    public List<Document> chunks(KnowledgeRepository.DocumentRow document,
                                 KnowledgeRepository.VersionRow version, byte[] bytes, UUID attempt) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() { return version.originalName(); }
        };

        List<Document> parsed;
        if (version.fileType().equals("txt") || version.fileType().equals("md")) {
            TextReader reader = new TextReader(resource);
            reader.setCharset(StandardCharsets.UTF_8);
            parsed = reader.read();
        } else {
            parsed = new TikaDocumentReader(resource).read();
        }
        if (parsed.isEmpty() || parsed.stream().allMatch(d -> d.getText() == null || d.getText().isBlank())) {
            throw new IllegalArgumentException("文档未解析出可用文本");
        }

        List<Document> split = splitter.apply(parsed);
        List<Document> result = new ArrayList<>();
        for (Document chunk : split) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            int index = result.size();
            String hash = sha256(text.getBytes(StandardCharsets.UTF_8));
            String id = UUID.nameUUIDFromBytes((document.id() + ":" + version.version()
                    + ":" + index + ":" + hash + ":" + attempt).getBytes(StandardCharsets.UTF_8)).toString();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId", document.id());
            metadata.put("documentVersion", version.version());
            metadata.put("knowledgeBaseId", document.knowledgeBaseId());
            metadata.put("userId", document.ownerUserId());
            metadata.put("source", version.originalName());
            metadata.put("chunkIndex", index);
            metadata.put("chunkHash", hash);
            metadata.put("fileType", version.fileType());
            metadata.put("ingest_owner", "knowledge-document-v1");
            metadata.put("published", false);
            if (attempt != null) { metadata.put("processingToken", attempt.toString()); }
            result.add(new Document(id, text, metadata));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("文档没有足够的文本可供向量化");
        }
        return result;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
