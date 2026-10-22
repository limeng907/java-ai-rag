package com.shtbs.interview.agent.validation;

import com.shtbs.interview.agent.AgentModels;
import com.shtbs.interview.agent.evidence.EvidenceRecord;
import com.shtbs.interview.agent.evidence.EvidenceRegistry;
import com.shtbs.interview.agent.evidence.EvidenceType;
import java.util.*;
import java.util.regex.Pattern;

/** Checks the deterministic parts of a knowledge answer before it is committed. */
public final class ChatAnswerPolicy {
    private static final Pattern SUBJECT=Pattern.compile("\\b(?:[A-Z][a-z0-9]+){2,}\\b");
    private static final Pattern MARKER=Pattern.compile("[\\[（(](K[0-9]+)[\\]）)]");
    private static final Pattern SENTENCE_BOUNDARY=Pattern.compile("(?<=[。！？!?])|(?<=\\.)\\s+|\\n+");

    public String validate(AgentModels.Answer answer,EvidenceRegistry registry,String question,boolean personal) {
        if (!answer.plan().isEmpty()) { return "CHAT不能生成学习计划；请使用STUDY模式"; }
        if (personal) { return null; }
        if (!answer.weaknesses().isEmpty()) { return "普通知识问答不能输出个人薄弱点"; }
        if (List.of("薄弱点","个人薄弱","历史面试评价","历史评分","学习诊断")
                .stream().anyMatch(answer.message()::contains)) {
            return "普通知识问答的正文不能转成个人学习诊断";
        }

        Map<String,EvidenceRecord> cited=new HashMap<>();
        for (var citation:answer.citations()) {
            EvidenceRecord record=registry.get(citation.evidenceId());
            if (record!=null && record.type()==EvidenceType.KNOWLEDGE) { cited.put(citation.evidenceId(),record); }
        }
        String message=answer.message();
        String lower=message.toLowerCase(Locale.ROOT);
        boolean uncertain=List.of("无法确认","无法判断","资料不足","没有相关资料","no evidence","cannot confirm","not enough information")
                .stream().anyMatch(lower::contains);
        if (uncertain && cited.isEmpty()) { return null; }
        var asked=SUBJECT.matcher(question);
        while (asked.find()) {
            if (!message.contains(asked.group())) {
                return "回答未回应当前问题中的"+asked.group()+"，请直接回答或说明证据不足";
            }
        }

        for (String sentence:SENTENCE_BOUNDARY.split(message)) {
            Set<String> ids=new HashSet<>();
            var markers=MARKER.matcher(sentence);
            while (markers.find()) { ids.add(markers.group(1)); }
            var subjects=SUBJECT.matcher(sentence);
            while (subjects.find()) {
                String subject=subjects.group();
                if (ids.stream().map(cited::get).filter(Objects::nonNull)
                        .noneMatch(record->record.content().contains(subject))) {
                    return "技术陈述中的"+subject+"缺少同句引用的对应知识Chunk；请检索并引用，或删去该陈述";
                }
            }
        }
        if (!cited.isEmpty() && !question.isBlank()) {
            boolean namesSubject=cited.values().stream().anyMatch(record->{
                var subjects=SUBJECT.matcher(record.content());
                while (subjects.find()) {
                    if (message.contains(subjects.group())) { return true; }
                }
                return false;
            });
            boolean evidenceHasSubject=cited.values().stream().anyMatch(record->SUBJECT.matcher(record.content()).find());
            if (evidenceHasSubject && !namesSubject) {
                return "回答未指出本次引用资料中的技术对象，请直接回答当前问题";
            }
        }
        return null;
    }
}
