package com.shtbs.interview.agent.runtime;

import com.shtbs.interview.agent.AgentRepository;
import com.shtbs.interview.agent.evidence.EvidenceRegistry;
import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public final class ToolExecutionAuditor {
    private final AgentRepository repository;
    private final UUID execution;
    private final ExecutionBudget budget;
    private final EvidenceRegistry registry;
    private final ExecutionToolCache cache;
    private final Map<String,Queue<AssistantMessage.ToolCall>> pending=new HashMap<>();
    public ToolExecutionAuditor(AgentRepository repository,UUID execution,ExecutionBudget budget,
                               List<AssistantMessage.ToolCall> calls,EvidenceRegistry registry) {
        this(repository,execution,budget,calls,registry,new ExecutionToolCache());
    }
    public ToolExecutionAuditor(AgentRepository repository,UUID execution,ExecutionBudget budget,
                               List<AssistantMessage.ToolCall> calls,EvidenceRegistry registry,ExecutionToolCache cache) {
        this.repository=repository;this.execution=execution;this.budget=budget;
        this.registry=registry;this.cache=cache;
        for (var call:calls) { pending.computeIfAbsent(call.name(),ignored->new ConcurrentLinkedQueue<>()).add(call); }
    }
    public ToolCallback wrap(ToolCallback delegate) {
        return new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            @Override public String call(String input) { return call(input,null); }
            @Override public String call(String input,ToolContext context) {
                budget.check();
                var queue=pending.get(getToolDefinition().name());var call=queue==null?null:queue.poll();
                if (call==null || !call.arguments().equals(input)) { throw new BusinessException(ErrorCode.AGENT_INVALID_OUTPUT); }
                long row=repository.startTool(execution,call.id(),call.name(),input),start=System.nanoTime();
                try {
                    budget.check();
                    String output=cache.read(call.name(),input,()->context==null?delegate.call(input):delegate.call(input,context));
                    budget.check();
                    if (output==null || output.length()>24000) { throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED); }
                    if (registry!=null) {
                        var prepared=registry.prepare(call.name(),call.id(),output);
                        budget.check();
                        repository.finishGroundedTool(execution,registry.userId(),row,prepared.output(),prepared.records(),budget::check);
                        budget.check();registry.accept(prepared);return prepared.output();
                    }
                    repository.finishTool(row,output,"SUCCEEDED",elapsed(start));return output;
                } catch (RuntimeException ex) {
                    repository.finishTool(row,null,ex instanceof BusinessException b && b.getCode().equals(6008)?"TIMED_OUT":"FAILED",elapsed(start));
                    throw ex;
                }
            }
        };
    }
    public void notExecuted() {
        pending.values().forEach(queue->queue.forEach(call->repository.tool(execution,call.id(),call.name(),call.arguments(),null,"NOT_EXECUTED",0)));
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }
}
