package com.example.techagent.agent;

import com.example.techagent.guard.GroundingGuard;
import com.example.techagent.tools.InterventionTools;
import com.example.techagent.tools.TechnicalDocumentationTools;
import com.example.techagent.tools.ToolTrace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Technician agent. The model chooses which tools to call (intervention, documentation, stock),
 * then answers. The answer is only shown if it is grounded in what the tools returned.
 *
 * <p>Three rules, enforced in code:
 * <ol>
 *   <li>The intervention is loaded by the application, not chosen by the model.</li>
 *   <li>No documentation excerpt, or an ungrounded answer: the answer is blocked.</li>
 *   <li>Memory only keeps the question and the answer shown, per session AND per intervention.</li>
 * </ol>
 *
 * <p>{@link AgentCoreInvocation} exposes the method on POST /invocations (and /ping): this is the
 * contract expected by AgentCore Runtime. The same jar runs locally.
 */
@Service
public class TechnicianAgent {

    // User-facing messages stay in French: the technicians are French speakers.
    static final String BLOCKED_MESSAGE = "Je ne peux pas donner de réponse fiable à partir de la documentation disponible. "
            + "Précisez le modèle de l'équipement ou le numéro d'intervention.";
    static final String INVALID_MESSAGE = "Requête invalide : question de 1 à " + GroundingGuard.MAX_QUERY_CHARS
            + " caractères, numéro d'intervention connu au format INT-AAAA-NNNN.";
    static final String ERROR_MESSAGE = "Le service est momentanément indisponible. Réessayez dans quelques instants.";

    /** Marker read by the system prompt (rule 1) to decide whether to call getIntervention. */
    static final String INTERVENTION_PREFIX = "Current intervention: ";

    private static final Pattern INTERVENTION_ID = Pattern.compile("INT-\\d{4}-\\d{4}");
    private static final Logger log = LoggerFactory.getLogger(TechnicianAgent.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final InterventionTools interventionTools;
    private final GroundingGuard guard;

    public TechnicianAgent(ChatClient.Builder builder, ChatMemory chatMemory,
            TechnicalDocumentationTools documentationTools, InterventionTools interventionTools, GroundingGuard guard,
            @Value("classpath:prompts/agent-system-prompt.md") Resource systemPrompt) {
        this.chatClient = builder
                .defaultSystem(read(systemPrompt))
                .defaultTools(documentationTools, interventionTools)
                .build();
        this.chatMemory = chatMemory;
        this.interventionTools = interventionTools;
        this.guard = guard;
    }

    @AgentCoreInvocation
    public AgentResponse invoke(AgentRequest request, AgentCoreContext context) {
        long start = System.currentTimeMillis();

        // 1. Validation, and the application loads the intervention.
        InterventionTools.Intervention intervention = null;
        if (request != null && hasText(request.interventionId())) {
            String id = request.interventionId().strip().toUpperCase();
            intervention = INTERVENTION_ID.matcher(id).matches() ? interventionTools.find(id).orElse(null) : null;
            if (intervention == null) {
                return done(AgentResponse.of(INVALID_MESSAGE, Status.INVALID_REQUEST, start));
            }
        }
        if (request == null || !hasText(request.prompt()) || request.prompt().strip().length() > GroundingGuard.MAX_QUERY_CHARS) {
            return done(AgentResponse.of(INVALID_MESSAGE, Status.INVALID_REQUEST, start));
        }
        String question = request.prompt().strip();
        String userMessage = intervention == null ? question : INTERVENTION_PREFIX + intervention.id() + "\n" + question;

        // 2. Agent loop: Spring AI calls the tools requested by the model, then returns the answer.
        String conversationId = conversationId(sessionId(context), intervention);
        List<Message> messages = new ArrayList<>(chatMemory.get(conversationId));
        messages.add(new UserMessage(userMessage));
        ToolTrace trace = new ToolTrace(intervention);
        ChatResponse response;
        GroundingGuard.Verdict verdict;
        try {
            response = chatClient.prompt()
                    .messages(messages)
                    .toolContext(Map.of(ToolTrace.KEY, trace))
                    .call()
                    .chatResponse();
            String text = response.getResult().getOutput().getText();

            // 3. Grounding check: without a documentation excerpt nothing grounds a technical
            // answer (intervention and stock give context, not the answer).
            verdict = trace.documents().isEmpty()
                    ? GroundingGuard.Verdict.NOT_EVALUATED
                    : guard.check(trace.groundingSource(), question, text);
            if (!verdict.blocked()) {
                return remember(conversationId, userMessage,
                        new AgentResponse(text, Status.ANSWERED, trace, usage(response, verdict, trace, start), verdict));
            }
        }
        catch (RuntimeException e) {
            // AWS error (throttling, timeout...): neutral message, only the error type is logged.
            log.warn("agent_invocation_failed error={}", e.getClass().getSimpleName());
            return done(new AgentResponse(ERROR_MESSAGE, Status.ERROR, trace, AgentResponse.Usage.none(start), null));
        }
        return remember(conversationId, userMessage,
                new AgentResponse(BLOCKED_MESSAGE, Status.BLOCKED, trace, usage(response, verdict, trace, start), verdict));
    }

    /** Only the question and the answer actually shown go into the history. */
    private AgentResponse remember(String conversationId, String userMessage, AgentResponse response) {
        chatMemory.add(conversationId, List.of(new UserMessage(userMessage), new AssistantMessage(response.answer())));
        return done(response);
    }

    /**
     * History specific to the session AND the intervention: reusing a session for another
     * intervention starts from an empty history, without mixing two work orders.
     */
    static String conversationId(String sessionId, InterventionTools.Intervention intervention) {
        return sessionId + "|" + (intervention == null ? "no-intervention" : intervention.id());
    }

    /** Without a session header, each call has its own session: no shared history. */
    private static String sessionId(AgentCoreContext context) {
        String id = context == null ? null : context.getHeader(AgentCoreHeaders.SESSION_ID);
        return hasText(id) ? id : "ephemeral-" + UUID.randomUUID();
    }

    /** Consumption accumulated over the whole agent loop: basis of the cost per question. */
    private static AgentResponse.Usage usage(ChatResponse response, GroundingGuard.Verdict verdict, ToolTrace trace,
            long start) {
        Usage usage = response.getMetadata().getUsage();
        int in = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int out = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        long retrieves = trace.toolCalls().stream().filter(c -> c.startsWith("searchTechnicalDocumentation")).count();
        return new AgentResponse.Usage(in, out, verdict.textUnits(), (int) retrieves, System.currentTimeMillis() - start);
    }

    /** One log line per question: status, tools, consumption, scores (never the technician's text). */
    private static AgentResponse done(AgentResponse r) {
        log.info("agent_invocation status={} toolCalls={} inputTokens={} outputTokens={} guardrailUnits={} "
                        + "retrieveCalls={} groundingScore={} latencyMs={}",
                r.status(), r.toolCalls().size(), r.usage().inputTokens(), r.usage().outputTokens(),
                r.usage().guardrailUnits(), r.usage().retrieveCalls(),
                r.grounding() == null ? null : r.grounding().groundingScore(), r.usage().latencyMs());
        return r;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("System prompt not found", e);
        }
    }

    public enum Status { ANSWERED, BLOCKED, INVALID_REQUEST, ERROR }

    public record AgentRequest(String prompt, String interventionId) { }

    /**
     * @param answer     answer shown to the technician
     * @param status     ANSWERED, BLOCKED (grounding check), INVALID_REQUEST or ERROR (AWS error)
     * @param toolCalls  tools called, in order (shown during the demo)
     * @param documents  documents read
     * @param usage      consumption of the question, for the cost calculation
     * @param grounding  grounding check scores (null if it did not run)
     */
    public record AgentResponse(String answer, Status status, List<String> toolCalls, List<String> documents,
            Usage usage, Grounding grounding) {

        AgentResponse(String answer, Status status, ToolTrace trace, Usage usage, GroundingGuard.Verdict verdict) {
            this(answer, status, trace.toolCalls(), trace.documents(), usage,
                    verdict == null || verdict.grounding() == null ? null
                            : new Grounding(verdict.grounding(), verdict.relevance()));
        }

        static AgentResponse of(String answer, Status status, long start) {
            return new AgentResponse(answer, status, List.of(), List.of(), Usage.none(start), null);
        }

        public record Usage(int inputTokens, int outputTokens, int guardrailUnits, int retrieveCalls, long latencyMs) {
            static Usage none(long start) {
                return new Usage(0, 0, 0, 0, System.currentTimeMillis() - start);
            }
        }

        public record Grounding(Double groundingScore, Double relevanceScore) { }
    }
}
