package com.example.techagent.config;

import java.time.Duration;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;

@Configuration
public class AgentConfig {

    /**
     * Client de la Knowledge Base. Credentials : role d'execution AgentCore Runtime en
     * production, profil local en developpement. Aucune cle statique.
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
     * Memoire de conversation courte (les 20 derniers messages), cle = identifiant de session
     * AgentCore. Chaque session AgentCore Runtime s'execute dans sa propre microVM : la memoire
     * en processus suffit pour enchainer les questions d'une meme intervention. Pour une memoire
     * durable (historique d'un site, preferences), remplacer par AgentCore Memory.
     */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
