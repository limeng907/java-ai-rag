package com.shtbs.interview.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import com.shtbs.interview.agent.memory.MemoryFacts;

public final class AgentModels {
    private AgentModels() {}
    public record Request(UUID conversationId, @NotBlank @Size(max = 4000) String message,
                          @Size(max = 5) List<@NotNull @Positive Long> knowledgeBaseIds, MemoryFacts.Field memoryField) {
        public Request(UUID conversationId,String message,List<Long> knowledgeBaseIds) { this(conversationId,message,knowledgeBaseIds,null); }
    }
    public record Profile(@NotBlank @Size(max = 200) String target,
                          @NotBlank @Size(max = 100) String level,
                          @Min(15) @Max(480) int dailyMinutes) {}
    public record Weakness(@NotBlank @Size(max = 200) String topic,
                           @NotBlank @Size(max = 1000) String reason,
                           @NotNull @Size(max = 5) List<Long> sessionIds,
                           @Size(max = 5) List<@NotBlank String> evidenceIds,
                           @Size(max = 5) List<@Valid EvidenceFact> facts) {
        public Weakness { evidenceIds=evidenceIds==null?List.of():List.copyOf(evidenceIds);facts=facts==null?List.of():List.copyOf(facts); }
        public Weakness(String topic,String reason,List<Long> sessionIds) { this(topic,reason,sessionIds,List.of(),List.of()); }
    }
    public record EvidenceFact(@NotBlank String evidenceId,@NotBlank @Size(max=500) String quote,
                               Long sessionId,Long turnId,Integer round,Integer score,Boolean correct,Boolean skipped) {}
    public record QuestionRecommendation(@Positive long questionId,@NotBlank String evidenceId,
                                          @NotBlank @Size(max=500) String reason) {}
    public record KnowledgeCitation(@NotBlank String evidenceId,String scope,String source,Long documentId,
                                    Integer version,String chunkId,String contentHash) {}
    public record PlanDay(@Min(1) @Max(7) int day,
                          @NotBlank @Size(max = 200) String topic,
                          @Min(1) @Max(480) int minutes,
                          @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 500) String> tasks,
                          @NotNull @Size(max = 10) List<Long> questionIds,
                          @Size(max=10) List<@Valid QuestionRecommendation> recommendations) {
        public PlanDay { recommendations=recommendations==null?List.of():List.copyOf(recommendations); }
        public PlanDay(int day,String topic,int minutes,List<String> tasks,List<Long> questionIds) {
            this(day,topic,minutes,tasks,questionIds,List.of());
        }
    }
    public record Answer(@NotBlank @Size(max = 6000) String message,
                         @NotNull @Size(max = 10) List<@Valid Weakness> weaknesses,
                         @NotNull @Size(max = 7) List<@Valid PlanDay> plan,
                         @NotNull @Size(max = 20) List<String> sources,
                         @Size(max=20) List<@Valid KnowledgeCitation> citations) {
        public Answer { citations=citations==null?List.of():List.copyOf(citations); }
        public Answer(String message,List<Weakness> weaknesses,List<PlanDay> plan,List<String> sources) {
            this(message,weaknesses,plan,sources,List.of());
        }
    }
    public record Response(UUID conversationId, UUID executionId, Long studyPlanId, Answer result,MemoryFacts.Result memoryFact) {
        public Response(UUID conversationId,UUID executionId,Long studyPlanId,Answer result) { this(conversationId,executionId,studyPlanId,result,null); }
    }
    public record PromptRequest(@Min(1) int version, @NotBlank @Size(max = 12000) String content) {}
}
