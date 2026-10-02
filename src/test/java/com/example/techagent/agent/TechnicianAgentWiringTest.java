package com.example.techagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Checks that the application starts and that the agent is registered, without calling AWS.
 */
@SpringBootTest(properties = {
        "techagent.knowledge-base-id=KB-TEST",
        "techagent.guardrail-id=gr-test",
        "techagent.guardrail-version=1",
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
}
