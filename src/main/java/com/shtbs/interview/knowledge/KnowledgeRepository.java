package com.shtbs.interview.knowledge;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class KnowledgeRepository {
    public record Base(long id, long ownerUserId, String name, String description,
                       Instant createdAt, Instant updatedAt) {}
    public record DocumentRow(long id, long knowledgeBaseId, long ownerUserId, String name,
                              String status, Integer activeVersion, int latestVersion,
                              String errorMessage, Instant createdAt, Instant updatedAt) {}
    public record VersionRow(long id, long documentId, int version, String originalName,
                             String objectKey, String fileType, String mimeType, long fileSize,
                             String fileHash, String status, int chunkCount, String embeddingModel,
                             Integer embeddingDimension, String chunkStrategyVersion,
                             String parserVersion, String errorMessage, String failedStage,
                             int retryCount, Instant createdAt, Instant processedAt) {}
    public record WorkItem(long documentId, int version) {}
    public record ChunkRow(String id, int index, String text, String hash) {}

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Base> BASE_MAPPER = (rs, row) -> new Base(
            rs.getLong("id"), rs.getLong("owner_user_id"), rs.getString("name"),
            rs.getString("description"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, row) -> new DocumentRow(
            rs.getLong("id"), rs.getLong("knowledge_base_id"), rs.getLong("owner_user_id"),
            rs.getString("name"), rs.getString("status"),
            rs.getObject("active_version", Integer.class), rs.getInt("latest_version"),
            rs.getString("error_message"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, row) -> new VersionRow(
            rs.getLong("id"), rs.getLong("document_id"), rs.getInt("version"),
            rs.getString("original_name"), rs.getString("object_key"),
            rs.getString("file_type"), rs.getString("mime_type"), rs.getLong("file_size"),
            rs.getString("file_hash"), rs.getString("status"), rs.getInt("chunk_count"),
            rs.getString("embedding_model"), rs.getObject("embedding_dimension", Integer.class),
            rs.getString("chunk_strategy_version"), rs.getString("parser_version"),
            rs.getString("error_message"), rs.getString("failed_stage"),
            rs.getInt("retry_count"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("processed_at")));

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Base findBase(long id) {
        return jdbc.query("SELECT * FROM knowledge_base WHERE id = ?", BASE_MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    public List<Base> listBases(long ownerUserId) {
        return jdbc.query("SELECT * FROM knowledge_base WHERE owner_user_id = ? ORDER BY id DESC",
                BASE_MAPPER, ownerUserId);
    }

    public long createBase(long ownerUserId, String name, String description) {
        Long id = jdbc.queryForObject("""
                INSERT INTO knowledge_base(owner_user_id, name, description)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, ownerUserId, name, description);
        return id;
    }

    public int deleteEmptyBase(long id, long ownerUserId) {
        return jdbc.update("""
                DELETE FROM knowledge_base b
                WHERE b.id = ? AND b.owner_user_id = ?
                  AND NOT EXISTS (SELECT 1 FROM knowledge_document d WHERE d.knowledge_base_id = b.id)
                """, id, ownerUserId);
    }

    public boolean hasLiveDocuments(long baseId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM knowledge_document
                               WHERE knowledge_base_id = ? AND status <> 'DELETED')
                """, Boolean.class, baseId);
        return Boolean.TRUE.equals(exists);
    }

    public void purgeDeletedDocuments(long baseId) {
        jdbc.update("""
                DELETE FROM knowledge_document_version
                WHERE document_id IN (SELECT id FROM knowledge_document
                                      WHERE knowledge_base_id = ? AND status = 'DELETED')
                """, baseId);
        jdbc.update("""
                DELETE FROM knowledge_document
                WHERE knowledge_base_id = ? AND status = 'DELETED'
                """, baseId);
    }

    public int countUserBases(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE owner_user_id = ?", Integer.class, userId);
        return count;
    }

    public long nextDocumentId() {
        Long id = jdbc.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('knowledge_document', 'id'))", Long.class);
        return id;
    }

    public DocumentRow findDocument(long id) {
        return jdbc.query("SELECT * FROM knowledge_document WHERE id = ?", DOCUMENT_MAPPER, id)
                .stream().findFirst().orElse(null);
    }

    public List<DocumentRow> listDocuments(long ownerUserId, Long baseId, int limit, long offset) {
        return jdbc.query("""
                SELECT * FROM knowledge_document
                WHERE owner_user_id = ? AND status <> 'DELETED'
                  AND (?::bigint IS NULL OR knowledge_base_id = ?)
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, DOCUMENT_MAPPER, ownerUserId, baseId, baseId, limit, offset);
    }

    public long countDocuments(long ownerUserId, Long baseId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document
                WHERE owner_user_id = ? AND status <> 'DELETED'
                  AND (?::bigint IS NULL OR knowledge_base_id = ?)
                """, Long.class, ownerUserId, baseId, baseId);
        return count;
    }

    //查询同一个知识库中，是否已存在相同的file_hash、处理指定有效处理状态的文档版本
    public boolean hasDuplicate(long baseId, String hash) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM knowledge_document d
                    JOIN knowledge_document_version v ON v.document_id = d.id
                    WHERE d.knowledge_base_id = ? AND d.status <> 'DELETED'
                      AND v.file_hash = ?
                      AND v.status IN ('UPLOADING','UPLOADED','QUEUED','PROCESSING','READY')
                )
                """, Boolean.class, baseId, hash);
        return Boolean.TRUE.equals(exists);
    }

    //防止两个并发上传请求同时通过查重
    public void lockFileHash(long baseId, String hash) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                Object.class, baseId + ":" + hash);
    }

    public boolean hasPendingVersion(long documentId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM knowledge_document_version
                    WHERE document_id = ?
                      AND status IN ('UPLOADING','UPLOADED','QUEUED','PROCESSING')
                )
                """, Boolean.class, documentId);
        return Boolean.TRUE.equals(exists);
    }

    public void insertDocument(long id, long baseId, long ownerUserId, String name) {
        jdbc.update("""
                INSERT INTO knowledge_document(id, knowledge_base_id, owner_user_id, name, status, latest_version)
                VALUES (?, ?, ?, ?, 'UPLOADING', 1)
                """, id, baseId, ownerUserId, name);
    }

    public int reserveNextVersion(long documentId, int expectedVersion) {
        return jdbc.update("""
                UPDATE knowledge_document
                SET latest_version = latest_version + 1,
                    status = CASE WHEN active_version IS NULL THEN 'UPLOADING' ELSE status END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND latest_version = ? AND status NOT IN ('DELETING','DELETED')
                """, documentId, expectedVersion);
    }

    public long insertVersion(long documentId, int version, String name, String key,
                              String type, String mime, long size, String hash, String status) {
        Long id = jdbc.queryForObject("""
                INSERT INTO knowledge_document_version(
                    document_id, version, original_name, object_key, file_type,
                    mime_type, file_size, file_hash, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, documentId, version, name, key, type, mime, size, hash, status);
        return id;
    }

    public VersionRow findVersion(long documentId, int version) {
        return jdbc.query("""
                SELECT * FROM knowledge_document_version
                WHERE document_id = ? AND version = ?
                """, VERSION_MAPPER, documentId, version)
                .stream().findFirst().orElse(null);
    }

    public List<VersionRow> listVersions(long documentId) {
        return jdbc.query("""
                SELECT * FROM knowledge_document_version
                WHERE document_id = ? ORDER BY version DESC
                """, VERSION_MAPPER, documentId);
    }

    public void markUploaded(long documentId, int version) {
        jdbc.update("""
                UPDATE knowledge_document_version SET status = 'UPLOADED', updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND version = ? AND status = 'UPLOADING'
                """, documentId, version);
        jdbc.update("""
                UPDATE knowledge_document SET status = 'UPLOADED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active_version IS NULL AND status NOT IN ('DELETING','DELETED')
                """, documentId);
    }

    public void markQueued(long documentId, int version) {
        jdbc.update("""
                UPDATE knowledge_document_version SET status = 'QUEUED', updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND version = ? AND status = 'UPLOADED'
                """, documentId, version);
        jdbc.update("""
                UPDATE knowledge_document SET status = 'QUEUED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active_version IS NULL AND status = 'UPLOADED'
                """, documentId);
    }

    public void markUploadFailed(long documentId, int version, String message) {
        int changed = jdbc.update("""
                UPDATE knowledge_document_version
                SET status = 'FAILED', failed_stage = 'UPLOAD', error_message = ?,
                    last_failed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND version = ? AND status = 'UPLOADING'
                """, message, documentId, version);
        if (changed != 1) { return; }
        jdbc.update("""
                UPDATE knowledge_document
                SET status = 'FAILED', error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active_version IS NULL AND status NOT IN ('DELETING','DELETED')
                """, message, documentId);
    }

    public int retryVersion(long documentId, int version) {
        int changed = jdbc.update("""
                UPDATE knowledge_document_version
                SET status = 'QUEUED', error_message = NULL, failed_stage = NULL,
                    retry_count = retry_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND version = ? AND status = 'FAILED'
                  AND failed_stage <> 'UPLOAD'
                """, documentId, version);
        if (changed == 1) {
            jdbc.update("""
                    UPDATE knowledge_document SET status = 'QUEUED', error_message = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND active_version IS NULL AND status = 'FAILED'
                    """, documentId);
        }
        return changed;
    }

    public int claimVersion(long documentId, int version, UUID token,
                            String model, int dimension, String strategy, String parser) {
        int changed = jdbc.update("""
                UPDATE knowledge_document_version v
                SET status = 'PROCESSING', processing_token = ?,
                    processing_started_at = CURRENT_TIMESTAMP, error_message = NULL,
                    failed_stage = NULL, embedding_model = ?, embedding_dimension = ?,
                    chunk_strategy_version = ?, parser_version = ?, updated_at = CURRENT_TIMESTAMP
                FROM knowledge_document d
                WHERE v.document_id = d.id AND d.id = ? AND v.version = ?
                  AND d.status NOT IN ('DELETING','DELETED')
                  AND v.status IN ('UPLOADED','QUEUED')
                """, token, model, dimension, strategy, parser, documentId, version);
        if (changed == 1) {
            jdbc.update("""
                    UPDATE knowledge_document SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND active_version IS NULL
                    """, documentId);
        }
        return changed;
    }

    public void markProcessingFailed(long documentId, int version, UUID token,
                                     String stage, String message) {
        int changed = jdbc.update("""
                UPDATE knowledge_document_version
                SET status = 'FAILED', failed_stage = ?, error_message = ?,
                    last_failed_at = CURRENT_TIMESTAMP, processing_token = NULL,
                    processing_started_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND version = ? AND status = 'PROCESSING'
                  AND processing_token = ?
                """, stage, message, documentId, version, token);
        if (changed == 1) {
            jdbc.update("""
                    UPDATE knowledge_document SET status = 'FAILED', error_message = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND active_version IS NULL AND status <> 'DELETING'
                    """, message, documentId);
        }
    }

    public int markVersionReady(long documentId, int version, UUID token, int chunkCount,
                                String model, int dimension, String strategy, String parser) {
        return jdbc.update("""
                UPDATE knowledge_document_version
                SET status = 'READY', chunk_count = ?, embedding_model = ?,
                    embedding_dimension = ?, chunk_strategy_version = ?, parser_version = ?,
                    processed_at = CURRENT_TIMESTAMP, processing_token = NULL,
                    processing_started_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND version = ? AND status = 'PROCESSING'
                  AND processing_token = ?
                """, chunkCount, model, dimension, strategy, parser, documentId, version, token);
    }

    public void lockDocumentForPublication(long documentId) {
        jdbc.queryForList("SELECT id FROM knowledge_document WHERE id=? FOR UPDATE",documentId);
    }

    public void setPublished(long documentId, Integer version, boolean published) {
        String sql = """
                UPDATE vector_store
                SET metadata = jsonb_set(metadata::jsonb, '{published}', ?::jsonb, true)::json
                WHERE metadata->>'documentId' = ?
                """ + (version == null ? "" : " AND metadata->>'documentVersion' = ?");
        if (version == null) {
            jdbc.update(sql, Boolean.toString(published), Long.toString(documentId));
        } else {
            jdbc.update(sql, Boolean.toString(published), Long.toString(documentId),
                    Integer.toString(version));
        }
    }

    public int activateVersion(long documentId, int version, String name) {
        return jdbc.update("""
                UPDATE knowledge_document
                SET active_version = ?, status = 'READY', name = ?, error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND latest_version = ? AND status NOT IN ('DELETING','DELETED')
                """, version, name, documentId, version);
    }

    public void deleteVersionChunks(long documentId, int version) {
        jdbc.update("""
                DELETE FROM vector_store
                WHERE metadata->>'documentId' = ? AND metadata->>'documentVersion' = ?
                """, Long.toString(documentId), Integer.toString(version));
    }

    public void deleteAttemptChunks(long documentId, int version, UUID token) {
        jdbc.update("""
                DELETE FROM vector_store WHERE metadata->>'documentId' = ?
                  AND metadata->>'documentVersion' = ? AND metadata->>'processingToken' = ?
                """, Long.toString(documentId), Integer.toString(version), token.toString());
    }

    public void publishAttempt(long documentId, int version, UUID token) {
        jdbc.update("""
                UPDATE vector_store
                SET metadata = jsonb_set(metadata::jsonb, '{published}', 'true'::jsonb, true)::json
                WHERE metadata->>'documentId' = ? AND metadata->>'documentVersion' = ?
                  AND metadata->>'processingToken' = ?
                """, Long.toString(documentId), Integer.toString(version), token.toString());
    }

    public List<WorkItem> staleUploads() {
        return jdbc.query("""
                SELECT v.document_id, v.version FROM knowledge_document_version v
                JOIN knowledge_document d ON d.id = v.document_id
                WHERE v.status = 'UPLOADING' AND v.updated_at < CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                  AND d.status NOT IN ('DELETING','DELETED') LIMIT 100
                """, (rs, row) -> new WorkItem(rs.getLong(1), rs.getInt(2)));
    }

    public void cleanAbandonedChunks() {
        jdbc.update("""
                DELETE FROM vector_store s WHERE s.metadata->>'ingest_owner'='knowledge-document-v1'
                  AND NOT EXISTS(SELECT 1 FROM knowledge_document d WHERE d.id::text=s.metadata->>'documentId')
                """);
        jdbc.update("""
                DELETE FROM vector_store s USING knowledge_document d, knowledge_document_version v
                WHERE s.metadata->>'ingest_owner' = 'knowledge-document-v1'
                  AND s.metadata->>'documentId' = d.id::text AND v.document_id = d.id
                  AND s.metadata->>'documentVersion' = v.version::text
                  AND (d.status = 'DELETED' OR
                       (s.metadata->>'published' = 'false' AND v.status IN ('FAILED','READY','DELETED')))
                """);
    }

    public void deleteInactiveChunks(long documentId, int activeVersion) {
        jdbc.update("""
                DELETE FROM vector_store
                WHERE metadata->>'documentId' = ? AND metadata->>'documentVersion' <> ?
                """, Long.toString(documentId), Integer.toString(activeVersion));
    }

    public void deleteAllChunks(long documentId) {
        jdbc.update("DELETE FROM vector_store WHERE metadata->>'documentId' = ?",
                Long.toString(documentId));
    }

    public List<ChunkRow> listChunks(long documentId, int version, int limit, long offset) {
        return jdbc.query("""
                SELECT id::text AS id, content,
                       (metadata->>'chunkIndex')::int AS chunk_index,
                       metadata->>'chunkHash' AS chunk_hash
                FROM vector_store
                WHERE metadata->>'documentId' = ? AND metadata->>'documentVersion' = ?
                ORDER BY (metadata->>'chunkIndex')::int
                LIMIT ? OFFSET ?
                """, (rs, row) -> new ChunkRow(rs.getString("id"), rs.getInt("chunk_index"),
                rs.getString("content"), rs.getString("chunk_hash")),
                Long.toString(documentId), Integer.toString(version), limit, offset);
    }

    public int markDeleting(long documentId) {
        return jdbc.update("""
                UPDATE knowledge_document
                SET status = 'DELETING', delete_requested_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP, error_message = NULL
                WHERE id = ? AND status NOT IN ('DELETING','DELETED')
                """, documentId);
    }

    public void markDeleteFailed(long documentId, String message) {
        jdbc.update("""
                UPDATE knowledge_document SET error_message = ?, retry_count = retry_count + 1,
                    last_failed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'DELETING'
                """, message, documentId);
    }

    public void markDeleted(long documentId) {
        jdbc.update("""
                UPDATE knowledge_document_version SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                """, documentId);
        jdbc.update("""
                UPDATE knowledge_document SET status = 'DELETED', active_version = NULL,
                    deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    error_message = NULL
                WHERE id = ? AND status = 'DELETING'
                """, documentId);
    }

    public List<String> distinctObjectKeys(long documentId) {
        return jdbc.queryForList("""
                SELECT DISTINCT object_key FROM knowledge_document_version WHERE document_id = ?
                """, String.class, documentId);
    }

    public List<WorkItem> queuedWork() {
        return jdbc.query("""
                SELECT v.document_id, v.version
                FROM knowledge_document_version v
                JOIN knowledge_document d ON d.id = v.document_id
                WHERE v.status IN ('UPLOADED','QUEUED')
                  AND v.updated_at < CURRENT_TIMESTAMP - INTERVAL '30 seconds'
                  AND d.status NOT IN ('DELETING','DELETED')
                ORDER BY v.updated_at LIMIT 100
                """, (rs, row) -> new WorkItem(rs.getLong(1), rs.getInt(2)));
    }

    public List<WorkItem> requeueStaleWork() {
        return jdbc.query("""
                UPDATE knowledge_document_version v
                SET status = 'QUEUED', processing_token = NULL,
                    processing_started_at = NULL, updated_at = CURRENT_TIMESTAMP
                FROM knowledge_document d
                WHERE v.document_id = d.id AND v.status = 'PROCESSING'
                  AND v.processing_started_at < CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                  AND d.status NOT IN ('DELETING','DELETED')
                RETURNING v.document_id, v.version
                """, (rs, row) -> new WorkItem(rs.getLong(1), rs.getInt(2)));
    }

    public List<Long> deletingDocuments() {
        return jdbc.queryForList("""
                SELECT id FROM knowledge_document WHERE status = 'DELETING'
                ORDER BY updated_at LIMIT 100
                """, Long.class);
    }
}
