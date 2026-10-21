package com.shtbs.interview.service;

import org.springframework.ai.document.Document;

import java.util.List;

public interface KnowledgeService {
     int importKnowledge();

     List<Document> searchKnowledge(String question);
}
