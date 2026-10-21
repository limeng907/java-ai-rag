package com.shtbs.interview.agent;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import com.shtbs.interview.knowledge.KnowledgeDocumentService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentTools {
    public record KnowledgeHit(String source,Long documentId,Integer version,String chunkId,String text,
                               String scope,Long knowledgeBaseId,String contentHash) {}
    private final AgentRepository repository;
    private final KnowledgeDocumentService knowledge;
    private final VectorStore vectors;
    public AgentTools(AgentRepository repository, KnowledgeDocumentService knowledge, VectorStore vectors) {
        this.repository=repository;this.knowledge=knowledge;this.vectors=vectors;
    }

    @Tool(description="查询当前用户最近1到5场已经完成的模拟面试和最终评价")
    public List<Map<String,Object>> getInterviewHistory(@ToolParam(description="记录数，1到5") int limit, ToolContext context) {
        return repository.history(user(context),limit);
    }

    @Tool(description="读取当前用户已完成面试中的低分、错答、跳过及缺失知识点证据；不是未测主题的分数")
    public List<Map<String,Object>> analyzeWeaknesses(ToolContext context) {
        return repository.weaknesses(user(context));
    }

    @Tool(description="读取当前用户求职目标、能力阶段和每日学习分钟数，不包含邮箱或密码")
    public AgentModels.Profile getUserProfile(ToolContext context) { return repository.profile(user(context)); }

    @Tool(description="按关键词搜索公共题库的题目标题、分类和内容，不返回标准答案")
    public List<Map<String,Object>> searchQuestions(@ToolParam(description="关键词，最多100字符") String keyword,
                                                   @ToolParam(description="数量，1到10") int limit) {
        return repository.questions(keyword,limit);
    }

    @Tool(description="检索公共知识及本次请求明确指定的私人知识库，返回资料和可引用来源；无资料时返回空列表")
    @SuppressWarnings("unchecked")
    public List<KnowledgeHit> searchKnowledge(@ToolParam(description="检索问题，最多1000字符") String query, ToolContext context) {
        if (query==null || query.isBlank() || query.length()>1000) { throw new BusinessException(ErrorCode.PARAM_ERROR); }
        List<Document> documents=new ArrayList<>();
        documents.addAll(vectors.similaritySearch(SearchRequest.builder().query(query).topK(3).similarityThreshold(0.3)
                .filterExpression(new FilterExpressionBuilder().eq("ingest_owner","knowledge-sync-v1").build()).build()));
        for (long base : (List<Long>)context.getContext().getOrDefault("knowledgeBaseIds",List.of())) {
            documents.addAll(knowledge.search(user(context),base,query));
        }
        List<Long> selected=(List<Long>)context.getContext().getOrDefault("knowledgeBaseIds",List.of());
        return documents.stream().map(doc->repository.knowledgeHit(user(context),selected,doc.getId(),false))
                .filter(java.util.Objects::nonNull).distinct().limit(8).toList();
    }

    private long user(ToolContext context) {
        Object id=context.getContext().get("userId");
        if (!(id instanceof Long userId)) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        return userId;
    }
}
