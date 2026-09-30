package com.example.techagent.agent;

import com.example.techagent.tools.InterventionTools;
import com.example.techagent.tools.TechnicalDocumentationTools;
import com.example.techagent.tools.ToolTrace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Agent technicien. Le modele choisit lui-meme quels outils appeler et dans quel ordre
 * (contexte d'intervention, recherche documentaire, stock), puis repond.
 *
 * <p>{@link AgentCoreInvocation} expose la methode sur POST /invocations et fournit /ping :
 * c'est le contrat attendu par AgentCore Runtime. Le meme jar tourne en local.
 */
@Service
public class TechnicianAgent {

    private static final Logger log = LoggerFactory.getLogger(TechnicianAgent.class);
    private static final String LOCAL_SESSION = "local-session";

    private final ChatClient chatClient;

    public TechnicianAgent(ChatClient.Builder builder, ChatMemory chatMemory,
            TechnicalDocumentationTools documentationTools, InterventionTools interventionTools,
            @Value("classpath:prompts/agent-system-prompt.md") Resource systemPrompt) {
        this.chatClient = builder
                .defaultSystem(read(systemPrompt))
                .defaultTools(documentationTools, interventionTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * @param request      question du technicien, numero d'intervention optionnel
     * @param context      en-tetes AgentCore (identifiant de session pour la memoire)
     */
    @AgentCoreInvocation
    public AgentResponse invoke(AgentRequest request, AgentCoreContext context) {
        long start = System.currentTimeMillis();
        String sessionId = sessionId(context);
        ToolTrace trace = new ToolTrace();

        ChatResponse response = chatClient.prompt()
                .user(userMessage(request))
                .toolContext(Map.of(ToolTrace.KEY, trace))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .chatResponse();

        String answer = response != null && response.getResult() != null
                ? response.getResult().getOutput().getText()
                : "Je ne trouve pas cette information dans la documentation disponible.";
        long latency = System.currentTimeMillis() - start;
        Integer inTokens = response != null ? response.getMetadata().getUsage().getPromptTokens() : null;
        Integer outTokens = response != null ? response.getMetadata().getUsage().getCompletionTokens() : null;

        log.info("agent_invocation session={} toolCalls={} documents={} inputTokens={} outputTokens={} latencyMs={}",
                sessionId, trace.toolCalls().size(), trace.documents().size(), inTokens, outTokens, latency);

        return new AgentResponse(answer, trace.toolCalls(), trace.documents(),
                new AgentResponse.Usage(nz(inTokens), nz(outTokens), latency));
    }

    static String userMessage(AgentRequest request) {
        if (request.interventionId() == null || request.interventionId().isBlank()) {
            return request.prompt();
        }
        return "Intervention : " + request.interventionId().trim() + "\n" + request.prompt();
    }

    private static String sessionId(AgentCoreContext context) {
        String id = context != null ? context.getHeader(AgentCoreHeaders.SESSION_ID) : null;
        return id == null || id.isBlank() ? LOCAL_SESSION : id;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("System prompt introuvable", e);
        }
    }

    public record AgentRequest(String prompt, String interventionId) { }

    /**
     * @param answer     reponse de l'agent
     * @param toolCalls  outils appeles, dans l'ordre (utile en demo et pour le debug)
     * @param documents  documents consultes
     * @param usage      consommation cumulee sur toute la boucle agent
     */
    public record AgentResponse(String answer, List<String> toolCalls, List<String> documents, Usage usage) {
        public record Usage(int inputTokens, int outputTokens, long latencyMs) { }
    }
}
