package com.example.techagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Verifie que l'application demarre et que l'agent est enregistre, sans appeler AWS.
 */
@SpringBootTest(properties = {
        "techagent.knowledge-base-id=KB-TEST",
        "spring.ai.bedrock.aws.region=eu-west-1",
        "spring.ai.bedrock.aws.access-key=test",
        "spring.ai.bedrock.aws.secret-key=test"
})
class TechnicianAgentWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void agentIsWired() {
        assertThat(context.getBean(TechnicianAgent.class)).isNotNull();
    }

    @Test
    void interventionIdIsPrependedToThePrompt() {
        String message = TechnicianAgent.userMessage(new TechnicianAgent.AgentRequest("Que faire ?", " INT-2026-0412 "));
        assertThat(message).isEqualTo("Intervention : INT-2026-0412\nQue faire ?");
        assertThat(TechnicianAgent.userMessage(new TechnicianAgent.AgentRequest("Que faire ?", null))).isEqualTo("Que faire ?");
    }
}
