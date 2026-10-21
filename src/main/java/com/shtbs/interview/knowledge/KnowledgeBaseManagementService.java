package com.shtbs.interview.knowledge;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class KnowledgeBaseManagementService {
    private final KnowledgeRepository repository;
    private final TransactionTemplate transactions;

    public KnowledgeBaseManagementService(KnowledgeRepository repository,
                                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    //创建知识库
    public KnowledgeRepository.Base create(long userId, String name, String description) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        try {
            long id = repository.createBase(userId, normalized, description);
            return repository.findBase(id);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_EXISTS);
        }
    }

    public List<KnowledgeRepository.Base> list(long userId) {
        return repository.listBases(userId);
    }

    //避免出现B来操作A的知识库的现象
    public KnowledgeRepository.Base requireOwned(long userId, long baseId) {
        KnowledgeRepository.Base base = repository.findBase(baseId);
        if (base == null || base.ownerUserId() != userId) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return base;
    }

    public void delete(long userId, long baseId) {
        requireOwned(userId, baseId);
        int changed = transactions.execute(status -> {
            if (repository.hasLiveDocuments(baseId)) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_EMPTY);
            }
            repository.purgeDeletedDocuments(baseId);
            return repository.deleteEmptyBase(baseId, userId);
        });
        if (changed != 1) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_EMPTY);
        }
    }
}
