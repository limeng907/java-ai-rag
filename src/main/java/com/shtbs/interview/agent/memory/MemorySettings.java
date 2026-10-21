package com.shtbs.interview.agent.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record MemorySettings(int triggerMessages,int recentMessages,int readMessages,int batchMessages,
                             int readConcurrency,int summaryInputTokens,int summaryOutputTokens,long summaryTimeoutMs) {
    public MemorySettings(
            @Value("${agent.memory.trigger-messages:20}") int triggerMessages,
            @Value("${agent.memory.recent-messages:10}") int recentMessages,
            @Value("${agent.memory.read-messages:100}") int readMessages,
            @Value("${agent.memory.batch-messages:100}") int batchMessages,
            @Value("${agent.memory.read-concurrency:8}") int readConcurrency,
            @Value("${agent.memory.summary-input-tokens:48000}") int summaryInputTokens,
            @Value("${agent.memory.summary-output-tokens:2048}") int summaryOutputTokens,
            @Value("${agent.memory.summary-timeout-ms:20000}") long summaryTimeoutMs) {
        if (recentMessages<2 || recentMessages%2!=0 || triggerMessages<=recentMessages || triggerMessages%2!=0
                || readMessages<triggerMessages || readMessages>1000 || readMessages%2!=0
                || batchMessages<triggerMessages || batchMessages>1000 || batchMessages%2!=0
                || readConcurrency<1 || readConcurrency>64 || summaryInputTokens<1024
                || summaryOutputTokens<256 || summaryTimeoutMs<1 || summaryTimeoutMs>120000) {
            throw new IllegalArgumentException("Invalid memory settings");
        }
        this.triggerMessages=triggerMessages;this.recentMessages=recentMessages;this.readMessages=readMessages;
        this.batchMessages=batchMessages;this.readConcurrency=readConcurrency;this.summaryInputTokens=summaryInputTokens;
        this.summaryOutputTokens=summaryOutputTokens;this.summaryTimeoutMs=summaryTimeoutMs;
    }
    public static MemorySettings defaults() { return new MemorySettings(20,10,100,100,8,48000,2048,20000); }
}
