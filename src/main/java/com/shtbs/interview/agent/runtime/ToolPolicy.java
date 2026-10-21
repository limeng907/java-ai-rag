package com.shtbs.interview.agent.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.util.*;

public final class ToolPolicy {
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> READ_TOOLS=Set.of("getInterviewHistory","analyzeWeaknesses",
            "getUserProfile","searchKnowledge","searchQuestions");
    private ToolPolicy() {}

    public static Set<String> allowedTools(String kind) {
        return switch (kind) {
            case "CHAT","STUDY" -> READ_TOOLS;
            case "INTERVIEW","EVALUATION" -> Set.of("searchKnowledge","searchQuestions");
            default -> throw new IllegalArgumentException("Unknown agent kind");
        };
    }

    public static boolean hasRequiredEvidence(String kind,Map<String,List<String>> evidence) {
        return !"STUDY".equals(kind) || Set.of("getUserProfile","getInterviewHistory","analyzeWeaknesses")
                .stream().allMatch(name->evidence.containsKey(name) && !evidence.get(name).isEmpty());
    }

    public static void validateBatch(List<AssistantMessage.ToolCall> calls,Set<String> permitted,Set<String> usedIds) {
        Set<String> ids=new HashSet<>();
        for (var call:calls) {
            if (!permitted.contains(call.name()) || call.id()==null || call.id().isBlank()
                    || call.id().length()>200 || !ids.add(call.id()) || usedIds.contains(call.id())) {
                throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED);
            }
            validateArguments(call.name(),call.arguments());
        }
        usedIds.addAll(ids);
    }

    public static void validateArguments(String name,String arguments) {
        try {
            if (arguments==null || arguments.length()>4000) { throw new IllegalArgumentException(); }
            JsonNode node=JSON.readTree(arguments);
            Set<String> fields=switch (name) {
                case "getUserProfile","analyzeWeaknesses" -> Set.of();
                case "getInterviewHistory" -> Set.of("limit");
                case "searchQuestions" -> Set.of("keyword","limit");
                case "searchKnowledge" -> Set.of("query");
                default -> throw new IllegalArgumentException();
            };
            if (node==null || !node.isObject() || node.size()!=fields.size()) { throw new IllegalArgumentException(); }
            for (String field:fields) { if (!node.has(field)) { throw new IllegalArgumentException(); } }
            if (fields.contains("limit")) {
                JsonNode limit=node.get("limit");int max="getInterviewHistory".equals(name)?5:10;
                if (!limit.isIntegralNumber() || !limit.canConvertToInt() || limit.intValue()<1 || limit.intValue()>max) {
                    throw new IllegalArgumentException();
                }
            }
            for (String field:List.of("keyword","query")) {
                if (fields.contains(field)) {
                    JsonNode value=node.get(field);int max="query".equals(field)?1000:100;
                    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length()>max) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (Exception ex) { throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT); }
    }
}
