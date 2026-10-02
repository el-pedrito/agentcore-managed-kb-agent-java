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
     * Client bedrock-runtime unique : ApplyGuardrail pour {@link GroundingGuard}, et repris par
     * l'auto-configuration Spring AI pour Converse. Timeout dimensionne pour une generation.
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

    @Bean
    GroundingGuard groundingGuard(BedrockRuntimeClient bedrockRuntimeClient,
            @Value("${techagent.grounding-check:true}") boolean groundingCheck,
            @Value("${techagent.guardrail-id:}") String guardrailId,
            @Value("${techagent.guardrail-version:}") String guardrailVersion) {
        // Fail fast : un garde-fou annonce mais mal configure ne doit pas laisser passer de
        // reponses non controlees.
        if (groundingCheck && (guardrailId.isBlank() || guardrailVersion.isBlank())) {
            throw new IllegalStateException("Controle d'ancrage actif : GUARDRAIL_ID et GUARDRAIL_VERSION sont "
                    + "obligatoires (ou GROUNDING_CHECK=false pour le desactiver explicitement).");
        }
        return new GroundingGuard(bedrockRuntimeClient, guardrailId, guardrailVersion);
    }

    /** Recherche Spring AI au-dessus de la Knowledge Base managee (meme classe que la demo 1). */
    @Bean
    VectorStore managedKnowledgeBaseVectorStore(BedrockAgentRuntimeClient client,
            @Value("${techagent.knowledge-base-id}") String knowledgeBaseId) {
        return new ManagedKnowledgeBaseVectorStore(client, knowledgeBaseId);
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
