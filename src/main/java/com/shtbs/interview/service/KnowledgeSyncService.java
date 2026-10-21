
package com.shtbs.interview.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class KnowledgeSyncService {

    // 标记哪些向量由本同步程序管理，谁上传谁管理
    private static final String OWNER = "knowledge-sync-v1";

    private final VectorStore vectorStore;//对向量继续增删改查
    private final JdbcTemplate pgJdbcTemplate;//查询数据库现有的记录

    private final ResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    public KnowledgeSyncService(
            VectorStore vectorStore,
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgJdbcTemplate) {

        this.vectorStore = vectorStore;
        this.pgJdbcTemplate = pgJdbcTemplate;
    }

    // 用于返回本次同步的统计信息
    public record SyncResult(
            int scannedFiles,
            int addedChunks,
            int unchangedChunks,
            int deletedChunks
    ) {}

    // 数据库中已有的Chunk
    private record StoredChunk(String id, String source) {}

    public synchronized SyncResult sync() throws IOException {

        // 1. 扫描文件夹
        Map<String, Resource> resources = scanResources();

        if (resources.isEmpty()) {
            throw new IllegalStateException(
                    "knowledge目录中没有找到TXT或Markdown文件，已取消同步"
            );
        }

        // 2. Chunk切分器
        TokenTextSplitter splitter =
                TokenTextSplitter.builder()
                        .withChunkSize(500)
                        .build();

        // 每个文件本次应该拥有的Document，按照本地文件现在的内容，数据库最终“应该存在”的 Chunk。状态对比 / reconciliation（状态协调）
        Map<String, List<Document>> desiredBySource =
                new LinkedHashMap<>();

        // 3. 先读取并切分全部文件
        for (Map.Entry<String, Resource> entry : resources.entrySet()) {

            String source = entry.getKey();
            Resource resource = entry.getValue();

            TextReader reader = new TextReader(resource);
            reader.setCharset(StandardCharsets.UTF_8);

            List<Document> chunks =
                    splitter.apply(reader.read());

            List<Document> desired = new ArrayList<>();

            // 记录同一个文件中重复出现的相同Chunk
            Map<String, Integer> occurrences = new HashMap<>();

            for (Document chunk : chunks) {

                String content = chunk.getText();

                if (content == null || content.isBlank()) {
                    continue;
                }

                // 计算Chunk内容Hash，如果内容不同生成的hash也就不同--内容身份
                String chunkHash = sha256(content);

                //如果这个 chunkHash 第一次出现，就存入 1；如果以前已经出现过，就把旧值和 1 相加。
                int occurrence =
                        occurrences.merge(chunkHash, 1, Integer::sum);

                // 根据文件、内容、出现次数生成稳定ID，相同输入永远得到相同 UUID。
                String id = UUID.nameUUIDFromBytes(
                        (OWNER + "|" + source + "|" +
                                chunkHash + "|" + occurrence)
                                .getBytes(StandardCharsets.UTF_8)
                ).toString();

                Map<String, Object> metadata =
                        new HashMap<>(chunk.getMetadata());

                metadata.put("source", source);
                metadata.put("ingest_owner", OWNER);
                metadata.put("chunk_hash", chunkHash);

                // 这份项目规则归入Project分类，其余暂归Java
                String category =
                        source.equals("interview_rules.txt")
                                ? "Project" : "Java";

                metadata.put("category", category);
//使用自己的构建的稳定ID，而不使用spring ai自动生成的document id
                desired.add(new Document(
                        id,
                        content,
                        metadata
                ));
            }

            desiredBySource.put(source, desired);
        }

        // 4. 查询数据库中本同步程序管理的Chunk
        List<StoredChunk> stored = pgJdbcTemplate.query(
                """
                SELECT
                    id::text AS id,
                    metadata->>'source' AS source
                FROM vector_store
                WHERE metadata->>'ingest_owner' = ?
                """,
                (rs, rowNum) -> new StoredChunk(
                        rs.getString("id"),
                        rs.getString("source")
                ),
                OWNER
        );

        Map<String, Set<String>> existingBySource =
                new HashMap<>();

        for (StoredChunk row : stored) {

            if (row.source() == null) {
                throw new IllegalStateException(
                        "发现缺少source的受管理Chunk，请先检查数据库"
                );
            }

            existingBySource
                    .computeIfAbsent(
                            row.source(),
                            k -> new HashSet<>()
                    )
                    .add(row.id());
        }

        List<Document> toAdd = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();

        int unchanged = 0;

        // 5. 比较本次Chunk与已有Chunk
        for (Map.Entry<String, List<Document>> entry
                : desiredBySource.entrySet()) {

            String source = entry.getKey();
            List<Document> desired = entry.getValue();

            Set<String> existingIds = existingBySource
                    .getOrDefault(source, Set.of());

            Set<String> desiredIds = new HashSet<>();

            for (Document doc : desired) {

                desiredIds.add(doc.getId());

                if (existingIds.contains(doc.getId())) {
                    // 没变的Chunk直接跳过
                    unchanged++;
                } else {
                    // 新增或修改后的Chunk
                    toAdd.add(doc);
                }
            }

            // 旧文件中已经不存在的Chunk需要删除
            for (String existingId : existingIds) {

                if (!desiredIds.contains(existingId)) {
                    toDelete.add(existingId);
                }
            }
        }

        // 6. 删除那些源文件已经不存在的Chunk
        for (Map.Entry<String, Set<String>> entry
                : existingBySource.entrySet()) {

            if (!desiredBySource.containsKey(entry.getKey())) {
                toDelete.addAll(entry.getValue());
            }
        }

        // 7. 先添加新的Chunk，确保新增数据成功后再清理旧数据
        if (!toAdd.isEmpty()) {
            vectorStore.add(toAdd);
        }

        // 8. 删除失效的Chunk
        if (!toDelete.isEmpty()) {
            vectorStore.delete(toDelete);
        }

        return new SyncResult(
                resources.size(),
                toAdd.size(),
                unchanged,
                toDelete.size()
        );
    }

    // 扫描TXT和Markdown文件
    private Map<String, Resource> scanResources() throws IOException {

        Map<String, Resource> result = new TreeMap<>();

        String[] patterns = {
                "classpath*:knowledge/*.txt",
                "classpath*:knowledge/*.md"
        };

        for (String pattern : patterns) {

            for (Resource resource : resolver.getResources(pattern)) {

                String filename = resource.getFilename();

                if (filename == null) {
                    throw new IllegalStateException(
                            "发现无法识别文件名的知识库资源"
                    );
                }

                Resource previous =
                        result.putIfAbsent(filename, resource);

                if (previous != null) {
                    throw new IllegalStateException(
                            "发现同名知识库文件：" + filename
                    );
                }
            }
        }

        return result;
    }

    // 计算SHA-256
    private String sha256(String content) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    content.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256算法不可用", e
            );
        }
    }
}

