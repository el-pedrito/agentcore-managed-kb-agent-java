package com.example.techagent.config;

import com.example.techagent.guard.GroundingGuard;
import com.example.techagent.kb.ManagedKnowledgeBaseVectorStore;
import java.time.Duration;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class AgentConfig {

    /**
     * Knowledge Base client. Credentials: AgentCore Runtime execution role in production, local
     * profile in development. No static key.
     */
    @Bean(destroyMethod = "close")
    BedrockAgentRuntimeClient bedrockAgentRuntimeClient(@Value("${spring.ai.bedrock.aws.region}") String region) {
        return BedrockAgentRuntimeClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofSeconds(15)))
                .build();
    }

    /**
     * Single bedrock-runtime client: ApplyGuardrail for {@link GroundingGuard}, and picked up by the
     * Spring AI auto-configuration for Converse. Timeout sized for a generation.
     */
    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${spring.ai.bedrock.aws.region}") String region) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }

    /** Mandatory guardrail: without GUARDRAIL_ID and GUARDRAIL_VERSION, the agent does not start. */
    @Bean
    GroundingGuard groundingGuard(BedrockRuntimeClient bedrockRuntimeClient,
            @Value("${techagent.guardrail-id}") String guardrailId,
            @Value("${techagent.guardrail-version}") String guardrailVersion) {
        if (guardrailId.isBlank() || guardrailVersion.isBlank()) {
            throw new IllegalStateException("GUARDRAIL_ID and GUARDRAIL_VERSION are required");
        }
        return new GroundingGuard(bedrockRuntimeClient, guardrailId, guardrailVersion);
    }

    /** Spring AI retrieval on top of the managed Knowledge Base (same class as demo 1). */
    @Bean
    VectorStore managedKnowledgeBaseVectorStore(BedrockAgentRuntimeClient client,
            @Value("${techagent.knowledge-base-id}") String knowledgeBaseId) {
        return new ManagedKnowledgeBaseVectorStore(client, knowledgeBaseId);
    }

    /**
     * Short conversation memory (last 20 messages), keyed by session and intervention. Each
     * AgentCore Runtime session runs in its own microVM: in-process memory is enough to chain the
     * questions of one intervention. For durable memory (site history, preferences), replace it
     * with AgentCore Memory.
     */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
