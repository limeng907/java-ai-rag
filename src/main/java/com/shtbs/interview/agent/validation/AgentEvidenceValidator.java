package com.shtbs.interview.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.shtbs.interview.agent.AgentModels;
import com.shtbs.interview.agent.evidence.*;
import java.util.*;
import java.util.regex.Pattern;

public final class AgentEvidenceValidator {
    private static final Pattern WORD=Pattern.compile("[a-z][a-z0-9+#.-]{2,}");
    private static final Set<String> GENERIC=Set.of("java","backend","基础","复习","学习","review","question","后端","综合");
    public String validate(AgentModels.Answer answer,EvidenceRegistry registry,boolean study,int dailyMinutes) {
        for (var weakness:answer.weaknesses()) {
            Set<String> ids=new HashSet<>(weakness.evidenceIds());
            if (ids.isEmpty() || ids.size()!=weakness.evidenceIds().size()) { return "每个薄弱点必须引用本次WEAKNESS证据，不能只引用sessionIds"; }
            Set<Long> sessions=new HashSet<>();Set<String> facts=new HashSet<>();
            for (String id:ids) {
                var record=registry.get(id);
                if (record==null || record.type()!=EvidenceType.WEAKNESS || !"analyzeWeaknesses".equals(record.toolName())) {
                    return "薄弱点证据不存在、类型不符，或属于跳过及非薄弱记录";
                }
                sessions.add(record.attributes().path("sessionId").asLong());
                if (!related(weakness.topic(),record.content())) { return "薄弱点主题关联不确定，请移除该薄弱点，不得只凭真实ID诊断"; }
            }
            if (!sessions.equals(new HashSet<>(weakness.sessionIds())) || sessions.size()!=weakness.sessionIds().size()) {
                return "sessionIds必须与所引证据对应的面试完全一致";
            }
            for (var fact:weakness.facts()) {
                if (!ids.contains(fact.evidenceId()) || !facts.add(fact.evidenceId())) { return "事实必须逐一对应evidenceIds"; }
                var record=registry.get(fact.evidenceId());JsonNode a=record.attributes();
                if (fact.quote().length()<2 || !record.content().contains(fact.quote())) { return "quote必须是所引证据content中原样存在的片段"; }
                if (!same(a,"sessionId",fact.sessionId()) || !same(a,"turnId",fact.turnId()) ||
                        !same(a,"round",fact.round()) || !same(a,"score",fact.score()) ||
                        !same(a,"correct",fact.correct()) || !same(a,"skipped",fact.skipped())) {
                    return "结构化事实与记录冲突；分数、轮次、correct和skipped不得更改，未知事实不得填写";
                }
            }
            if (!facts.equals(ids)) { return "每个薄弱点证据都必须提供一项facts及原文quote"; }
        }
        if (study && (answer.plan().size()!=7 || answer.plan().stream().map(AgentModels.PlanDay::day).distinct().count()!=7)) {
            return "plan必须包含day为1到7的7个不同日期";
        }
        for (var day:answer.plan()) {
            if (day.minutes()>dailyMinutes) { return "每日minutes不可超过学习档案预算"; }
            Set<Long> questions=new HashSet<>();
            for (var recommendation:day.recommendations()) {
                var record=registry.get(recommendation.evidenceId());
                if (record==null || record.type()!=EvidenceType.QUESTION || !"searchQuestions".equals(record.toolName()) ||
                        record.attributes().path("questionId").asLong()!=recommendation.questionId() ||
                        !questions.add(recommendation.questionId())) { return "题目推荐必须唯一对应本次searchQuestions的QUESTION证据"; }
                if (!related(day.topic(),record.content())) { return "题目与当天主题关联不确定，请移除该题目ID及recommendation，保留基础学习任务"; }
            }
            if (!questions.equals(new HashSet<>(day.questionIds())) || questions.size()!=day.questionIds().size()) {
                return "questionIds必须与recommendations一一对应，包含questionId、evidenceId和reason";
            }
        }
        Set<String> sources=new HashSet<>(),citations=new HashSet<>();
        for (var citation:answer.citations()) {
            var record=registry.get(citation.evidenceId());
            if (record==null || record.type()!=EvidenceType.KNOWLEDGE || !"searchKnowledge".equals(record.toolName()) ||
                    !citations.add(citation.evidenceId())) { return "知识引用必须唯一对应本次KNOWLEDGE证据"; }
            JsonNode a=record.attributes();
            if (!same(a,"scope",citation.scope()) || !same(a,"source",citation.source()) ||
                    !same(a,"documentId",citation.documentId()) || !same(a,"version",citation.version()) ||
                    !same(a,"chunkId",citation.chunkId()) || !same(a,"contentHash",citation.contentHash())) {
                return "知识引用的范围、版本、Chunk或内容哈希与本次证据不一致";
            }
            sources.add(a.path("source").asText());
        }
        if (!sources.equals(new HashSet<>(answer.sources())) || sources.size()!=answer.sources().size()) {
            return "sources须与citations中的本次知识来源一致；没有精确证据时两者均为空数组";
        }
        return null;
    }
    private static boolean same(JsonNode attributes,String field,Object claimed) {
        if (claimed==null) { return true; }
        JsonNode actual=attributes.get(field);
        return actual!=null && !actual.isNull() && actual.asText().equals(String.valueOf(claimed));
    }
    public static boolean related(String topic,String content) {
        String subject=topic.toLowerCase(Locale.ROOT).strip(),text=content.toLowerCase(Locale.ROOT);
        if (GENERIC.contains(subject)) { return false; }
        Set<String> words=new HashSet<>();var matcher=WORD.matcher(subject);
        while (matcher.find()) { if (!GENERIC.contains(matcher.group())) { words.add(matcher.group()); } }
        var other=WORD.matcher(text);
        while (other.find()) { if (words.contains(other.group())) { return true; } }
        return subject.length()>=2 && subject.codePoints().anyMatch(c->c>127) && text.contains(subject);
    }
    public AgentModels.Answer complete(AgentModels.Answer answer,EvidenceRegistry registry) {
        var weaknesses=answer.weaknesses().stream().map(w->new AgentModels.Weakness(w.topic(),recordedReason(w,registry),w.sessionIds(),w.evidenceIds(),
                w.facts().stream().map(f->{
                    JsonNode a=registry.get(f.evidenceId()).attributes();
                    return new AgentModels.EvidenceFact(f.evidenceId(),f.quote(),number(a,"sessionId"),number(a,"turnId"),
                            integer(a,"round"),integer(a,"score"),bool(a,"correct"),bool(a,"skipped"));
                }).toList())).toList();
        var citations=answer.citations().stream().map(c->{
            JsonNode a=registry.get(c.evidenceId()).attributes();
            return new AgentModels.KnowledgeCitation(c.evidenceId(),a.path("scope").asText(),a.path("source").asText(),
                    number(a,"documentId"),integer(a,"version"),a.path("chunkId").asText(),a.path("contentHash").asText());
        }).toList();
        // Personal assessment prose is never copied from the model after fact validation.
        String message=weaknesses.isEmpty()?answer.message():"以下薄弱点依据已保存的面试评价整理；历史评分不是客观能力结论，学习任务属于建议。";
        return new AgentModels.Answer(message,weaknesses,answer.plan(),answer.sources(),citations);
    }
    private static String recordedReason(AgentModels.Weakness weakness,EvidenceRegistry registry) {
        List<String> descriptions=new ArrayList<>();
        for (String id:weakness.evidenceIds()) {
            JsonNode a=registry.get(id).attributes();
            StringBuilder text=new StringBuilder("历史面试记录");
            if (a.hasNonNull("round")) { text.append("第").append(a.get("round").asInt()).append("轮"); }
            if (a.hasNonNull("score")) { text.append("，记录得分").append(a.get("score").asInt()).append("分"); }
            if (a.hasNonNull("correct")) { text.append("，记录判定为").append(a.get("correct").asBoolean()?"正确":"未答对"); }
            if (!a.hasNonNull("turnId")) { text.append("，依据整场历史评价，未提供逐轮评分"); }
            descriptions.add(text.append("（").append(id).append("）").toString());
        }
        return String.join("；",descriptions)+"。此处复述已保存的评价，不代表客观能力认定。";
    }
    private static Long number(JsonNode a,String field) { return a.hasNonNull(field)?a.get(field).longValue():null; }
    private static Integer integer(JsonNode a,String field) { return a.hasNonNull(field)?a.get(field).intValue():null; }
    private static Boolean bool(JsonNode a,String field) { return a.hasNonNull(field)?a.get(field).booleanValue():null; }
}
