package com.shtbs.interview.agent.runtime;

import com.shtbs.interview.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentExecutionRecovery {
    private static final Logger log=LoggerFactory.getLogger(AgentExecutionRecovery.class);
    private final AgentRepository repository;
    public AgentExecutionRecovery(AgentRepository repository) { this.repository=repository; }
    @Scheduled(initialDelayString="${agent.recovery-delay-ms:30000}",fixedDelayString="${agent.recovery-delay-ms:30000}")
    public void recover() {
        try {
            int count=repository.recoverExpired();
            if (count>0) { log.info("Recovered {} expired Agent executions",count); }
        } catch (RuntimeException ex) { log.warn("Agent execution recovery failed; retrying on next poll"); }
    }
}
