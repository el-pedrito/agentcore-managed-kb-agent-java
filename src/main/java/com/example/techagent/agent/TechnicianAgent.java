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
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Agent technicien. Le modele choisit lui-meme quels outils appeler et dans quel ordre
 * (contexte d'intervention, recherche documentaire, stock), puis repond. La reponse finale passe
 * ensuite le controle d'ancrage, avec comme source de verite tout ce que les outils ont renvoye.
 *
 * <p>Regles de securite, appliquees en code et pas seulement dans le prompt :
 * <ul>
 *   <li>Fail-closed : une reponse sans contenu d'outil, ou non evaluable par le garde-fou, est
 *       bloquee. Une recherche documentaire vide donne une reponse NOT_FOUND deterministe.</li>
 *   <li>Memoire geree explicitement : seuls la question et la reponse effectivement montree au
 *       technicien sont enregistres. Une reponse rejetee et la consigne de relance n'entrent
 *       jamais dans l'historique de la session.</li>
 *   <li>Entrees validees avant tout appel au modele.</li>
 * </ul>
 *
 * <p>{@link AgentCoreInvocation} expose la methode sur POST /invocations et fournit /ping :
 * c'est le contrat attendu par AgentCore Runtime. Le meme jar tourne en local.
 */
@Service
public class TechnicianAgent {

    static final String NOT_FOUND_MESSAGE = "Je ne trouve pas cette information dans la documentation disponible.";
    static final String BLOCKED_MESSAGE = "Je ne peux pas donner de réponse fiable à partir de la documentation disponible. "
            + "Précisez le modèle de l'équipement ou le numéro d'intervention.";
    static final String INVALID_MESSAGE = "Requête invalide : question de 1 à " + GroundingGuard.MAX_QUERY_CHARS
            + " caractères, numéro d'intervention au format INT-AAAA-NNNN.";

    static final String UNKNOWN_INTERVENTION_MESSAGE = "Numéro d'intervention inconnu.";
    static final String UNAVAILABLE_MESSAGE = "Le service est momentanément indisponible. Réessayez dans quelques instants.";

    static final String RETRY_INSTRUCTION = "Ta réponse précédente n'est pas fondée sur la documentation. "
            + "Appelle searchTechnicalDocumentation pour la question posée, puis réponds uniquement à partir "
            + "des extraits obtenus. Si la documentation ne contient pas la réponse, dis-le.";

    private static final Pattern INTERVENTION_ID = Pattern.compile("INT-\\d{4}-\\d{4}");
    private static final Logger log = LoggerFactory.getLogger(TechnicianAgent.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final InterventionTools interventionTools;
    private final GroundingGuard guard;
    private final boolean guardEnabled;

    public TechnicianAgent(ChatClient.Builder builder, ChatMemory chatMemory,
            TechnicalDocumentationTools documentationTools, InterventionTools interventionTools,
            GroundingGuard guard, @Value("${techagent.grounding-check:true}") boolean groundingCheck,
            @Value("classpath:prompts/agent-system-prompt.md") Resource systemPrompt) {
        this.chatClient = builder
                .defaultSystem(read(systemPrompt))
                .defaultTools(documentationTools, interventionTools)
                .build();
        this.chatMemory = chatMemory;
        this.interventionTools = interventionTools;
        this.guard = guard;
        this.guardEnabled = groundingCheck;
    }

    /**
     * @param request  question du technicien, numero d'intervention optionnel
     * @param context  en-tetes AgentCore (identifiant de session pour la memoire)
     */
    @AgentCoreInvocation
    public AgentResponse invoke(AgentRequest request, AgentCoreContext context) {
        long start = System.currentTimeMillis();
        if (!isValid(request)) {
            log.info("agent_invocation status=INVALID_REQUEST");
            return new AgentResponse(INVALID_MESSAGE, Status.INVALID_REQUEST, List.of(), List.of(),
                    new AgentResponse.Usage(0, 0, 0, System.currentTimeMillis() - start), null);
        }

        // L'intervention est resolue ici, par l'application, et non par le modele de langage.
        InterventionTools.Intervention intervention = null;
        if (request.interventionId() != null && !request.interventionId().isBlank()) {
            intervention = interventionTools.find(request.interventionId()).orElse(null);
            if (intervention == null) {
                log.info("agent_invocation status=INVALID_REQUEST reason=unknown_intervention");
                return new AgentResponse(UNKNOWN_INTERVENTION_MESSAGE, Status.INVALID_REQUEST, List.of(), List.of(),
                        new AgentResponse.Usage(0, 0, 0, System.currentTimeMillis() - start), null);
            }
        }

        String sessionId = conversationId(sessionId(context), intervention);
        String question = request.prompt().strip();
        String userMessage = userMessage(request);
        List<Message> firstTurn = new ArrayList<>(chatMemory.get(sessionId));
        firstTurn.add(new UserMessage(userMessage));

        ToolTrace trace = newTrace(intervention);
        List<String> toolCalls = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        int guardrailUnits = 0;
        int[] tokens = {0, 0};
        boolean retried = false;
        Outcome outcome;
        try {
            ChatResponse response = call(firstTurn, trace);
            String firstAnswer = text(response);
            outcome = evaluate(trace, question, firstAnswer);
            collect(trace, toolCalls, documents);
            guardrailUnits = outcome.verdict().textUnits();
            tokens = tokens(response);

            // Reponse non fondee (typiquement : le modele a repondu de memoire sans consulter la
            // documentation) : une seule relance, avec une consigne explicite et une trace neuve,
            // pour que seule une nouvelle recherche puisse fonder la nouvelle reponse. Ce tour de
            // relance n'est pas memorise.
            if (outcome.retriable()) {
                retried = true;
                List<Message> retryTurn = new ArrayList<>(firstTurn);
                retryTurn.add(new AssistantMessage(firstAnswer == null ? "" : firstAnswer));
                retryTurn.add(new UserMessage(RETRY_INSTRUCTION));
                ToolTrace retryTrace = trace.forRetry();
                ChatResponse retry = call(retryTurn, retryTrace);
                outcome = evaluate(retryTrace, question, text(retry));
                collect(retryTrace, toolCalls, documents);
                guardrailUnits += outcome.verdict().textUnits();
                int[] retryTokens = tokens(retry);
                tokens = new int[] {tokens[0] + retryTokens[0], tokens[1] + retryTokens[1]};
            }
        }
        catch (RuntimeException e) {
            // Converse en throttling ou timeout, echec d'outil remonte par Spring AI... Reponse
            // deterministe, seul le type d'erreur est journalise, rien n'est memorise.
            long latency = System.currentTimeMillis() - start;
            log.warn("agent_invocation status=UNAVAILABLE error={} latencyMs={}", e.getClass().getSimpleName(), latency);
            return new AgentResponse(UNAVAILABLE_MESSAGE, Status.UNAVAILABLE, List.copyOf(toolCalls), List.copyOf(documents),
                    new AgentResponse.Usage(tokens[0], tokens[1], guardrailUnits, latency), null);
        }

        // Seul ce qui a ete montre au technicien entre dans l'historique de la session, et pas
        // les pannes AWS (la question pourra etre reposee telle quelle).
        if (outcome.status() != Status.UNAVAILABLE) {
            chatMemory.add(sessionId, List.of(new UserMessage(userMessage), new AssistantMessage(outcome.answer())));
        }

        long latency = System.currentTimeMillis() - start;
        GroundingGuard.Verdict verdict = outcome.verdict();
        log.info("agent_invocation status={} retried={} toolCalls={} documents={} inputTokens={} "
                        + "outputTokens={} groundingScore={} relevanceScore={} guardrailUnits={} latencyMs={}",
                outcome.status(), retried, toolCalls.size(), documents.size(), tokens[0], tokens[1],
                verdict.grounding(), verdict.relevance(), guardrailUnits, latency);

        // Pas de bloc d'ancrage quand l'evaluation n'a pas pu avoir lieu (panne AWS).
        AgentResponse.Grounding grounding = guardEnabled && outcome.status() != Status.UNAVAILABLE
                ? new AgentResponse.Grounding(verdict.grounding(), verdict.relevance(),
                        outcome.status() == Status.BLOCKED, retried)
                : null;
        return new AgentResponse(outcome.answer(), outcome.status(), List.copyOf(toolCalls), List.copyOf(documents),
                new AgentResponse.Usage(tokens[0], tokens[1], guardrailUnits, latency), grounding);
    }

    private static ToolTrace newTrace(InterventionTools.Intervention intervention) {
        return intervention == null ? new ToolTrace() : ToolTrace.forIntervention(intervention);
    }

    private static void collect(ToolTrace trace, List<String> toolCalls, List<String> documents) {
        toolCalls.addAll(trace.toolCalls());
        trace.documents().stream().filter(d -> !documents.contains(d)).forEach(documents::add);
    }

    /** Decide ce qui est montre au technicien pour une reponse candidate. */
    private Outcome evaluate(ToolTrace trace, String question, String answer) {
        // Panne AWS pendant un outil (Retrieve...) : ni relance ni message sur la saisie.
        if (trace.isUnavailable()) {
            return Outcome.unavailable();
        }
        if (answer == null || answer.isBlank()) {
            return Outcome.blocked(GroundingGuard.Verdict.NOT_EVALUATED, true);
        }
        // Les regles documentaires valent aussi quand le garde-fou est desactive : seul l'appel
        // ApplyGuardrail est saute par GROUNDING_CHECK=false.
        // Refus exact : accepte seulement si une recherche documentaire a vraiment eu lieu.
        if (answer.strip().equals(NOT_FOUND_MESSAGE)) {
            return trace.wasDocumentationSearched()
                    ? new Outcome(NOT_FOUND_MESSAGE, Status.NOT_FOUND, GroundingGuard.Verdict.DISABLED, false)
                    : Outcome.blocked(GroundingGuard.Verdict.NOT_EVALUATED, true);
        }
        // Recherche documentaire faite, aucun extrait : refus deterministe, meme si l'intervention
        // ou le stock ont renvoye du contenu (ils ne remplacent pas la documentation).
        if (trace.wasDocumentationSearched() && trace.documents().isEmpty()) {
            return new Outcome(NOT_FOUND_MESSAGE, Status.NOT_FOUND, GroundingGuard.Verdict.DISABLED, false);
        }
        // Aucun extrait de documentation : rien ne fonde une reponse technique. L'intervention et
        // le stock donnent du contexte, pas la reponse. Relance avec consigne de chercher.
        if (trace.documents().isEmpty() || trace.groundingSource().isBlank()) {
            return Outcome.blocked(GroundingGuard.Verdict.NOT_EVALUATED, true);
        }
        if (!guardEnabled) {
            return new Outcome(answer, Status.ANSWERED, GroundingGuard.Verdict.DISABLED, false);
        }

        GroundingGuard.Verdict verdict;
        try {
            // Query = la question seule : limite de 1 000 caracteres, et le controle d'ancrage
            // n'est pas concu pour un historique conversationnel.
            verdict = guard.check(trace.groundingSource(), question, answer);
        }
        catch (SdkException e) {
            // Fail-closed : la reponse n'est pas montree. Statut UNAVAILABLE (panne AWS), pas BLOCKED.
            log.warn("grounding_check_failed error={}", e.getClass().getSimpleName());
            return Outcome.unavailable();
        }
        return verdict.blocked()
                ? Outcome.blocked(verdict, true)
                : new Outcome(answer, Status.ANSWERED, verdict, false);
    }

    private ChatResponse call(List<Message> messages, ToolTrace trace) {
        return chatClient.prompt()
                .messages(messages)
                .toolContext(Map.of(ToolTrace.KEY, trace))
                .call()
                .chatResponse();
    }

    private static String text(ChatResponse response) {
        return response != null && response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
    }

    private static int[] tokens(ChatResponse response) {
        Usage usage = response != null && response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        int in = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int out = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new int[] {in, out};
    }

    static boolean isValid(AgentRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()
                || request.prompt().strip().length() > GroundingGuard.MAX_QUERY_CHARS) {
            return false;
        }
        String id = request.interventionId();
        return id == null || id.isBlank() || INTERVENTION_ID.matcher(id.strip().toUpperCase()).matches();
    }

    static String userMessage(AgentRequest request) {
        if (request.interventionId() == null || request.interventionId().isBlank()) {
            return request.prompt().strip();
        }
        return "Intervention : " + request.interventionId().strip().toUpperCase() + "\n" + request.prompt().strip();
    }

    /**
     * Historique propre a la session ET a l'intervention : reutiliser une session pour une autre
     * intervention (ou sans intervention) repart d'un historique vide, sans melanger deux dossiers.
     */
    static String conversationId(String sessionId, InterventionTools.Intervention intervention) {
        return sessionId + "|" + (intervention == null ? "sans-intervention" : intervention.id());
    }

    /** Sans en-tete de session, chaque appel a sa propre session : aucun historique partage. */
    private static String sessionId(AgentCoreContext context) {
        String id = context != null ? context.getHeader(AgentCoreHeaders.SESSION_ID) : null;
        return id == null || id.isBlank() ? "ephemeral-" + UUID.randomUUID() : id;
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("System prompt introuvable", e);
        }
    }

    private record Outcome(String answer, Status status, GroundingGuard.Verdict verdict, boolean retriable) {

        static Outcome blocked(GroundingGuard.Verdict verdict, boolean retriable) {
            return new Outcome(BLOCKED_MESSAGE, Status.BLOCKED, verdict, retriable);
        }

        static Outcome unavailable() {
            return new Outcome(UNAVAILABLE_MESSAGE, Status.UNAVAILABLE, GroundingGuard.Verdict.NOT_EVALUATED, false);
        }
    }

    public enum Status { ANSWERED, NOT_FOUND, BLOCKED, INVALID_REQUEST, UNAVAILABLE }

    public record AgentRequest(String prompt, String interventionId) { }

    /**
     * @param answer     reponse montree au technicien
     * @param status     ANSWERED, NOT_FOUND, BLOCKED (garde-fou), INVALID_REQUEST ou UNAVAILABLE (erreur AWS)
     * @param toolCalls  outils appeles, dans l'ordre (utile en demo et pour le debug)
     * @param documents  documents consultes
     * @param usage      consommation cumulee sur toute la boucle agent
     * @param grounding  resultat du controle d'ancrage (null si desactive)
     */
    public record AgentResponse(String answer, Status status, List<String> toolCalls, List<String> documents,
            Usage usage, Grounding grounding) {

        public record Usage(int inputTokens, int outputTokens, int guardrailUnits, long latencyMs) { }

        /** @param retried vrai si une premiere reponse non fondee a ete rejetee puis regeneree */
        public record Grounding(Double groundingScore, Double relevanceScore, boolean blocked, boolean retried) { }
    }
}
