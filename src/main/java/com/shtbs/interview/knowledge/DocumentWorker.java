package com.shtbs.interview.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
public class DocumentWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentWorker.class);

    private final KnowledgeRepository repository;
    private final DocumentTaskQueue tasks;
    private final S3ObjectStorage storage;
    private final DocumentChunker chunker;
    private final VectorStore vectorStore;
    private final TransactionTemplate transactions;
    private final String embeddingModel;
    private final int embeddingDimension;

    public DocumentWorker(KnowledgeRepository repository, DocumentTaskQueue tasks,
                          S3ObjectStorage storage, DocumentChunker chunker, VectorStore vectorStore,
                          PlatformTransactionManager transactionManager,
                          @Value("${spring.ai.ollama.embedding.options.model}") String embeddingModel,
                          @Value("${knowledge.embedding.dimension:768}") int embeddingDimension) {
        this.repository = repository;
        this.tasks = tasks;
        this.storage = storage;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.transactions = new TransactionTemplate(transactionManager);
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
    }

    @Scheduled(fixedDelayString = "${knowledge.worker.poll-delay-ms:1000}")
    public void poll() {
        try {
            drain(tasks.readNew());
        } catch (RuntimeException ex) {
            log.warn("Document task poll failed; it will be retried", ex);
        }
    }

    @Scheduled(initialDelayString = "${knowledge.worker.reconcile-delay-ms:30000}",
            fixedDelayString = "${knowledge.worker.reconcile-delay-ms:30000}")
    public void reconcile() {
        try {
            for (KnowledgeRepository.WorkItem item : repository.staleUploads()) {
                KnowledgeRepository.VersionRow version = repository.findVersion(item.documentId(), item.version());
                if (storage.exists(version.objectKey())) {
                    transactions.executeWithoutResult(ignored -> repository.markUploaded(item.documentId(), item.version()));
                    tasks.publishIndex(item.documentId(), item.version());
                } else {
                    transactions.executeWithoutResult(ignored -> repository.markUploadFailed(
                            item.documentId(), item.version(), "Upload interrupted; upload the file again"));
                }
            }
            for (KnowledgeRepository.WorkItem item : repository.requeueStaleWork()) {
                tasks.publishIndex(item.documentId(), item.version());
            }
            for (KnowledgeRepository.WorkItem item : repository.queuedWork()) {
                tasks.publishIndex(item.documentId(), item.version());
            }
            for (long documentId : repository.deletingDocuments()) {
                tasks.publishDelete(documentId);
            }
            drain(tasks.reclaimStale());
            repository.cleanAbandonedChunks();
        } catch (RuntimeException ex) {
            log.warn("Document task reconciliation failed; it will run again", ex);
        }
    }

    private void drain(List<DocumentTaskQueue.Task> batch) {
        for (DocumentTaskQueue.Task task : batch) {
            try {
                if ("INDEX".equals(task.kind())) {
                    processIndex(task.documentId(), task.version());
                } else if ("DELETE".equals(task.kind())) {
                    processDelete(task.documentId());
                } else {
                    log.warn("Ignoring unknown document task kind {}", task.kind());
                }
                tasks.acknowledge(task);
            } catch (RuntimeException ex) {
                // No ACK: Redis keeps the pending message for stale-message recovery.
                log.error("Document task failed before a durable state was recorded: {}", task, ex);
            }
        }
    }

    private void processIndex(long documentId, int versionNumber) {
        UUID token = UUID.randomUUID();
        int claimed = transactions.execute(ignored -> repository.claimVersion(
                documentId, versionNumber, token, embeddingModel, embeddingDimension,
                DocumentChunker.STRATEGY, DocumentChunker.PARSER));
        if (claimed != 1) {
            return;
        }

        String stage = "DOWNLOAD";
        boolean activated = false;
        try {
            KnowledgeRepository.DocumentRow document = repository.findDocument(documentId);
            KnowledgeRepository.VersionRow version = repository.findVersion(documentId, versionNumber);
            if (document == null || version == null) {
                throw new IllegalStateException("Document or version was removed");
            }
            byte[] bytes = storage.get(version.objectKey());

            stage = "PARSING";
            List<Document> chunks = chunker.chunks(document, version, bytes, token);

            stage = "VECTOR_WRITE";
            vectorStore.add(chunks);

            stage = "ACTIVATE";
            transactions.executeWithoutResult(ignored -> {
                repository.lockDocumentForPublication(documentId);
                if (repository.markVersionReady(documentId, versionNumber, token, chunks.size(),
                        embeddingModel, embeddingDimension, DocumentChunker.STRATEGY,
                        DocumentChunker.PARSER) != 1) {
                    throw new IllegalStateException("Document processing lease expired");
                }
                repository.setPublished(documentId, null, false);
                repository.publishAttempt(documentId, versionNumber, token);
                if (repository.activateVersion(documentId, versionNumber, version.originalName()) != 1) {
                    throw new IllegalStateException("Document changed or is being deleted");
                }
            });
            activated = true;
            log.info("Document {} version {} is READY with {} chunks", documentId,
                    versionNumber, chunks.size());
        } catch (RuntimeException ex) {
            log.warn("Document {} version {} failed at {}", documentId, versionNumber, stage, ex);
            String failedStage = stage;
            transactions.executeWithoutResult(ignored -> repository.markProcessingFailed(
                    documentId, versionNumber, token, failedStage, safeError(ex)));
            try {
                repository.deleteAttemptChunks(documentId, versionNumber, token);
            } catch (RuntimeException cleanupError) {
                log.warn("Failed to clean unpublished chunks for document {} version {}",
                        documentId, versionNumber, cleanupError);
            }
        }

        if (activated) {
            try {
                repository.deleteInactiveChunks(documentId, versionNumber);
            } catch (RuntimeException ex) {
                // Old chunks are unpublished, so a cleanup failure cannot expose them.
                log.warn("Failed to clean old unpublished chunks for document {}", documentId, ex);
            }
        }
    }

    private void processDelete(long documentId) {
        KnowledgeRepository.DocumentRow document = repository.findDocument(documentId);
        if (document == null || !"DELETING".equals(document.status())) {
            return;
        }
        try {
            repository.deleteAllChunks(documentId);
            for (String key : repository.distinctObjectKeys(documentId)) {
                storage.delete(key);
            }
            transactions.executeWithoutResult(ignored -> repository.markDeleted(documentId));
            log.info("Document {} deletion completed", documentId);
        } catch (RuntimeException ex) {
            repository.markDeleteFailed(documentId, safeError(ex));
            log.warn("Document {} deletion will be retried", documentId, ex);
        }
    }

    private String safeError(RuntimeException ex) {
        String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
