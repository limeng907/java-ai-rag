package com.shtbs.interview.agent.memory;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.ToLongFunction;

@Component
public class TokenBudgetManager {
    private final int contextTokens,safetyTokens,toolReserveTokens;
    private final ToLongFunction<String> estimator;
    @org.springframework.beans.factory.annotation.Autowired
    public TokenBudgetManager(@Value("${agent.context-tokens:65536}") int contextTokens,
                              @Value("${agent.token-safety-margin:2048}") int safetyTokens,
                              @Value("${agent.tool-reserve-tokens:4096}") int toolReserveTokens) {
        this(contextTokens,safetyTokens,toolReserveTokens,text->text.getBytes(StandardCharsets.UTF_8).length);
    }
    // Replaceable estimator; UTF-8 bytes are deliberately conservative, not a model tokenizer.
    public TokenBudgetManager(int contextTokens,int safetyTokens,int toolReserveTokens,ToLongFunction<String> estimator) {
        if (contextTokens<4096 || safetyTokens<0 || toolReserveTokens<0 || safetyTokens+toolReserveTokens>=contextTokens) {
            throw new IllegalArgumentException("Invalid token budgets");
        }
        this.contextTokens=contextTokens;this.safetyTokens=safetyTokens;this.toolReserveTokens=toolReserveTokens;this.estimator=estimator;
    }
    public long estimate(String text) { return text==null?0:estimator.applyAsLong(text); }
    public long estimate(List<Message> messages,List<ToolCallback> tools) {
        long total=32;
        for (var m:messages) {
            total+=16+estimate(m.getText());
            if (m instanceof AssistantMessage a) {
                for (var c:a.getToolCalls()) { total+=32+estimate(c.id())+estimate(c.name())+estimate(c.type())+estimate(c.arguments()); }
            } else if (m instanceof ToolResponseMessage t) {
                for (var r:t.getResponses()) { total+=32+estimate(r.id())+estimate(r.name())+estimate(r.responseData()); }
            }
        }
        for (var tool:tools) {
            var d=tool.getToolDefinition();total+=64+estimate(d.name())+estimate(d.description())+estimate(d.inputSchema());
        }
        return total;
    }
    public void requireFits(List<Message> messages,List<ToolCallback> tools,int outputTokens,int extraReserve) {
        if (!fits(messages,tools,outputTokens,extraReserve)) { exceeded(); }
    }
    public boolean fits(List<Message> messages,List<ToolCallback> tools,int outputTokens,int extraReserve) {
        return estimate(messages,tools)+(long)outputTokens+safetyTokens+extraReserve<=contextTokens;
    }
    public void fit(List<Message> messages,List<ToolCallback> tools,int outputTokens,int maxChars) {
        int reserve=tools.isEmpty()?0:toolReserveTokens;
        while (estimate(messages,tools)+(long)outputTokens+safetyTokens+reserve>contextTokens || chars(messages)>maxChars) {
            Object group=messages.stream().map(m->m.getMetadata().get(ContextBuilder.HISTORY_GROUP))
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (group!=null) { messages.removeIf(m->group.equals(m.getMetadata().get(ContextBuilder.HISTORY_GROUP)));continue; }
            if (messages.removeIf(m->Boolean.TRUE.equals(m.getMetadata().get(ContextBuilder.SUMMARY)))) { continue; }
            exceeded();
        }
    }
    private long chars(List<Message> messages) {
        long total=0;
        for (var m:messages) {
            total+=m.getText()==null?0:m.getText().length();
            if (m instanceof AssistantMessage a) { for (var c:a.getToolCalls()) { total+=c.arguments()==null?0:c.arguments().length(); } }
            if (m instanceof ToolResponseMessage t) { for (var r:t.getResponses()) { total+=r.responseData()==null?0:r.responseData().length(); } }
        }
        return total;
    }
    private static void exceeded() { throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED); }
}
