package com.shtbs.interview.agent.runtime;

import com.shtbs.interview.common.ErrorCode;
import com.shtbs.interview.exception.BusinessException;
import org.springframework.ai.chat.messages.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutionBudget {
    private final int maxModels,maxTools,maxChars;
    private final long deadline,callTimeoutMs;
    private final java.time.Instant expiresAt;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile long callDeadline=Long.MAX_VALUE;
    private int models,tools;
    public ExecutionBudget(int maxModels,int maxTools,int maxChars,long timeoutMs,long callTimeoutMs) {
        this.maxModels=maxModels;this.maxTools=maxTools;this.maxChars=maxChars;
        this.deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);this.callTimeoutMs=callTimeoutMs;
        this.expiresAt=java.time.Instant.now().plusMillis(timeoutMs);
    }
    public int models() { return models; }
    public int tools() { return tools; }
    public java.time.Instant expiresAt() { return expiresAt; }
    public boolean hasModelCall() { return models<maxModels; }
    public boolean finishing() { return models>=maxModels-2 || tools>=maxTools; }
    public void nextModel() { check();if (!hasModelCall()) { limit(); } models++; }
    public void useTools(int count) { check();if (tools+count>maxTools) { limit(); } tools+=count; }
    public void cancel() { cancelled.set(true); }
    public void beginCall() { check();callDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(callTimeoutMs)); }
    public void endCall() { callDeadline=Long.MAX_VALUE; }
    public void check() {
        if (cancelled.get() || System.nanoTime()>=Math.min(deadline,callDeadline)) { throw new BusinessException(ErrorCode.AGENT_TIMEOUT); }
    }
    public long waitMillis() {
        check();return Math.max(1,TimeUnit.NANOSECONDS.toMillis(Math.min(deadline,callDeadline)-System.nanoTime()));
    }
    public void checkContext(List<Message> messages) {
        long length=messages.stream().mapToLong(m->m.getText()==null?0:m.getText().length()).sum();
        for (Message message:messages) {
            if (message instanceof AssistantMessage assistant) {
                length+=assistant.getToolCalls().stream().mapToLong(c->c.arguments()==null?0:c.arguments().length()).sum();
            } else if (message instanceof ToolResponseMessage responses) {
                length+=responses.getResponses().stream().mapToLong(r->r.responseData()==null?0:r.responseData().length()).sum();
            }
        }
        if (length>maxChars) { limit(); }
    }
    private static void limit() { throw new BusinessException(ErrorCode.AGENT_LIMIT_EXCEEDED); }
}
