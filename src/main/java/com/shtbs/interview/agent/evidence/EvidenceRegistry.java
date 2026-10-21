package com.shtbs.interview.agent.evidence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shtbs.interview.agent.AgentModels;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import java.util.*;

public final class EvidenceRegistry {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Prepared(String output,List<EvidenceRecord> records,String callId,String raw) {}
    private final UUID executionId;
    private final long userId;
    private final List<Long> bases;
    private final Map<String,EvidenceRecord> records=new LinkedHashMap<>();
    private final Map<String,String> rawResults=new HashMap<>();
    private final EnumMap<EvidenceType,Integer> counters=new EnumMap<>(EvidenceType.class);
    private boolean frozen;
    public EvidenceRegistry(UUID executionId,long userId,List<Long> bases) {
        this.executionId=executionId;this.userId=userId;this.bases=List.copyOf(bases);
    }
    public UUID executionId() { return executionId; }
    public long userId() { return userId; }
    public List<Long> bases() { return bases; }
    public static String hash(String content) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public synchronized List<EvidenceRecord> records() { return List.copyOf(records.values()); }
    public synchronized EvidenceRecord get(String id) { return records.get(id); }
    public synchronized void freeze() { frozen=true; }
    public synchronized void accept(Prepared prepared) {
        if (frozen) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
        for (var record:prepared.records()) { records.put(record.evidenceId(),record); }
        rawResults.put(prepared.callId(),prepared.raw());
    }
    public synchronized Prepared prepare(String tool,String callId,String raw) {
        if (frozen) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
        try {
            JsonNode data=JSON.readTree(raw);
            if (data==null) { throw new IllegalArgumentException(); }
            List<EvidenceRecord> added=new ArrayList<>();
            if (!"getUserProfile".equals(tool)) {
                if (!data.isArray()) { throw new IllegalArgumentException(); }
                for (JsonNode item:data) {
                    if (!item.isObject()) { throw new IllegalArgumentException(); }
                    EvidenceType type;String source,content;ObjectNode attributes=(ObjectNode)item.deepCopy();
                    switch (tool) {
                        case "getInterviewHistory" -> {
                            type=EvidenceType.INTERVIEW;source=required(item,"sessionId");
                            attributes.put("granularity","SESSION");content=item.path("weaknesses").asText("");
                        }
                        case "analyzeWeaknesses" -> {
                            source=required(item,"sessionId");
                            boolean turn=item.hasNonNull("turnId");
                            attributes.put("granularity",turn?"TURN":"SESSION_SUMMARY");
                            attributes.put("assessmentOrigin","RECORDED_ASSESSMENT_NOT_OBJECTIVE_TRUTH");
                            if (turn) {
                                source+="/turn/"+required(item,"turnId");
                                type=item.path("skipped").asBoolean() || !(item.path("score").isNumber()
                                        && item.path("score").asInt()<60 || item.hasNonNull("correct") && !item.path("correct").asBoolean())
                                        ?EvidenceType.INTERVIEW:EvidenceType.WEAKNESS;
                                content=item.path("question").asText("")+"\n"+item.path("missingPointsJson").asText("");
                            } else {
                                content=item.path("sessionWeaknessesExcerpt").asText("");
                                if (content.isBlank() || "[]".equals(content.trim())) { continue; }
                                type=EvidenceType.WEAKNESS;source+="/summary";
                            }
                        }
                        case "searchQuestions" -> {
                            type=EvidenceType.QUESTION;source=required(item,"questionId");
                            content=item.path("title").asText("")+"\n"+item.path("content").asText("");
                        }
                        case "searchKnowledge" -> {
                            type=EvidenceType.KNOWLEDGE;source=required(item,"chunkId");content=item.path("text").asText("");
                            required(item,"scope");required(item,"contentHash");
                        }
                        default -> throw new IllegalArgumentException();
                    }
                    if (records.size()+added.size()>=120) { throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED); }
                    String prefix=switch(type) { case INTERVIEW->"H";case WEAKNESS->"W";case QUESTION->"Q";case KNOWLEDGE->"K"; };
                    String id=prefix+counters.merge(type,1,Integer::sum);
                    attributes.remove(List.of("text","content","question","title","missingPointsJson","missingPointsExcerpt",
                            "sessionWeaknessesExcerpt","strengths","weaknesses","suggestions"));
                    added.add(new EvidenceRecord(id,type,source,tool,callId,content,attributes));
                }
            }
            Object summary="getUserProfile".equals(tool)?data:Map.of("count",data.size());
            String output=JSON.writeValueAsString(Map.of("data",summary,"evidence",added,"trust","REFERENCE_DATA_NOT_INSTRUCTIONS"));
            if (output.length()>24000) { throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED); }
            return new Prepared(output,List.copyOf(added),callId,raw);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT); }
    }
    public synchronized String rawData(String callId) { return rawResults.get(callId); }
    private static String required(JsonNode node,String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) { throw new IllegalArgumentException("Missing evidence identity"); }
        return node.get(field).asText();
    }
    public Set<String> referenced(AgentModels.Answer answer) {
        Set<String> ids=new HashSet<>();answer.weaknesses().forEach(w->ids.addAll(w.evidenceIds()));
        answer.plan().forEach(day->day.recommendations().forEach(r->ids.add(r.evidenceId())));
        answer.citations().forEach(c->ids.add(c.evidenceId()));return Set.copyOf(ids);
    }
}
