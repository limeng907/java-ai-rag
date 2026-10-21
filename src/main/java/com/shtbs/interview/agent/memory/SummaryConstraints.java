package com.shtbs.interview.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Preserves narrowly supported explicit constraints from the original summarized prefix. */
public final class SummaryConstraints {
    private SummaryConstraints() {}
    public static String preserve(String summary,List<MemoryRepository.Row> original,ObjectMapper json) {
        try {
            ObjectNode value=(ObjectNode)json.readTree(summary);
            for (var field:MemoryFacts.Field.values()) {
                var fact=MemoryFacts.resolve(field,original,original.size()>1000);
                if (fact.status()==MemoryFacts.Status.NOT_FOUND) { continue; }
                for (String section:List.of("userStatements","preferences")) {
                    var retained=new ArrayList<String>();
                    for(var item:value.path(section)) {
                        String text=item.asText();
                        if(!mentions(field,text)) { retained.add(text);continue; }
                        // Drop the affected clause, including stale numerical variants without "daily".
                        // Preserve neighboring goal/preference clauses rather than discarding the whole entry.
                        StringBuilder remainingText=new StringBuilder();
                        for(String clause:text.split("(?<=[，,。；;])")) {
                            if(!mentions(field,clause)) { remainingText.append(clause); }
                        }
                        String remaining=remainingText.toString().strip();
                        if(!remaining.isBlank() && !remaining.matches("[用户我，,。；;\\s]+")) { retained.add(remaining); }
                    }
                    var array=value.putArray(section);retained.forEach(array::add);
                }
                var statements=value.withArray("userStatements");
                while(statements.size()>=8) { statements.remove(statements.size()-1); }
                String text=fact.status()==MemoryFacts.Status.CONFLICT?
                        (field==MemoryFacts.Field.DAILY_MINUTES?"用户每天学习时间存在冲突，需再次确认。":"用户面试公司陈述存在冲突，需再次确认。"):
                        (field==MemoryFacts.Field.DAILY_MINUTES?"用户每天可学习"+fact.value()+"分钟。":"用户明确说曾面试"+fact.value()+"公司。");
                statements.add(text);
            }
            return json.writeValueAsString(value);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Invalid summary JSON",ex); }
    }
    private static boolean mentions(MemoryFacts.Field field,String text) {
        String lower=text.toLowerCase(Locale.ROOT);
        return field==MemoryFacts.Field.DAILY_MINUTES?
                (lower.contains("每天") || lower.contains("每日") || lower.contains("daily") || lower.contains("per day")
                        || lower.matches("(?s).*[0-9零一二三四五六七八九十百]+\\s*(?:分钟|小时|minutes?|mins?|hours?).*")):
                ((lower.contains("面试") && lower.contains("公司")) || lower.contains("interviewed"));
    }
}
