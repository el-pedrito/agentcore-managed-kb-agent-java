package com.example.techagent.tools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Trace d'une invocation : outils appeles, documents consultes et contenu renvoye par les outils.
 * Transmise aux outils via le {@link ToolContext} Spring AI, donc propre a chaque requete (pas
 * d'etat partage). Sure en cas d'appels d'outils paralleles.
 *
 * <p>Deux usages en plus du debug :
 * <ul>
 *   <li>le contenu renvoye par les outils est la source de verite du controle d'ancrage de la
 *       reponse finale ;</li>
 *   <li>le nombre d'appels est plafonne : au-dela de {@link #MAX_TOOL_CALLS}, les outils ne
 *       s'executent plus et demandent au modele de conclure.</li>
 * </ul>
 */
public class ToolTrace {

    public static final String KEY = "toolTrace";
    /** Une question terrain demande 2 a 4 appels ; au-dela, l'agent tourne en rond. */
    public static final int MAX_TOOL_CALLS = 8;
    public static final String LIMIT_REACHED = "Limite d'appels d'outils atteinte pour cette question. "
            + "Reponds maintenant avec les seules informations deja obtenues, ou indique que tu ne trouves pas.";

    private static final Logger log = LoggerFactory.getLogger(ToolTrace.class);
    private static final int MAX_LOGGED_CHARS = 120;

    /** Partage entre la premiere tentative et la relance : le plafond vaut pour toute la question. */
    private final AtomicInteger callCount;
    private final AtomicBoolean documentationSearched = new AtomicBoolean();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final List<String> toolCalls = new CopyOnWriteArrayList<>();
    private final List<String> documents = new CopyOnWriteArrayList<>();
    private final List<String> groundingSources = new CopyOnWriteArrayList<>();
    private final InterventionTools.Intervention intervention;
    private final String interventionModel;
    private final String trustedInterventionId;

    /** Trace vide : aucune intervention dans la requete, l'outil getIntervention est refuse. */
    public ToolTrace() {
        this(null, new AtomicInteger());
    }

    private ToolTrace(InterventionTools.Intervention intervention, AtomicInteger callCount) {
        this.intervention = intervention;
        this.callCount = callCount;
        this.trustedInterventionId = intervention == null ? null : intervention.id();
        this.interventionModel = intervention == null ? null : intervention.modele();
    }

    /**
     * Trace d'une requete portant un numero d'intervention, resolu par l'application avant la
     * boucle du modele (systeme de reference, pas un argument d'outil). Le modele d'equipement et
     * l'identifiant autorise sont donc connus d'office (le contenu n'entre dans
     * la source d'ancrage qu'a l'appel de getIntervention) ; le modele de langage ne peut
     * consulter que cette intervention-la.
     */
    public static ToolTrace forIntervention(InterventionTools.Intervention intervention) {
        return new ToolTrace(intervention, new AtomicInteger());
    }

    /**
     * Trace de la relance : preuves neuves (seule une nouvelle recherche peut fonder la nouvelle
     * reponse), meme intervention, et meme compteur d'appels (le plafond vaut pour la question).
     */
    public ToolTrace forRetry() {
        return new ToolTrace(intervention, callCount);
    }

    /** Un outil a echoue cote AWS (Retrieve, etc.) : la reponse sera UNAVAILABLE, pas BLOCKED. */
    public void unavailable() {
        unavailable.set(true);
    }

    public boolean isUnavailable() {
        return unavailable.get();
    }

    public String trustedInterventionId() {
        return trustedInterventionId;
    }

    /**
     * Enregistre un appel d'outil.
     *
     * @return false si le plafond d'appels est depasse : l'outil doit alors renvoyer {@link #LIMIT_REACHED}
     */
    public boolean call(String description) {
        int count = callCount.incrementAndGet();
        toolCalls.add(description);
        // Nom de l'outil seulement : les arguments (requete, reference) derivent de la saisie du
        // technicien et n'ont pas a finir dans CloudWatch. Ils restent dans la reponse (toolCalls).
        log.info("tool_call n={} tool={}", count, toolName(description));
        if (count > MAX_TOOL_CALLS) {
            log.warn("tool_call_limit_reached n={} max={}", count, MAX_TOOL_CALLS);
            return false;
        }
        return true;
    }

    /**
     * Enregistre l'appel et interrompt l'outil si le plafond est depasse. L'exception est renvoyee
     * au modele comme resultat d'outil (comportement par defaut de Spring AI), avec la consigne de
     * conclure.
     */
    public void requireCall(String description) {
        if (!call(description)) {
            throw new IllegalStateException(LIMIT_REACHED);
        }
    }

    /** Une recherche documentaire a ete executee (meme si elle n'a rien renvoye). */
    public void documentationSearched() {
        documentationSearched.set(true);
    }

    public boolean wasDocumentationSearched() {
        return documentationSearched.get();
    }

    public void document(String name) {
        if (!documents.contains(name)) {
            documents.add(name);
        }
    }

    /** Contenu factuel renvoye par un outil, sur lequel la reponse doit etre fondee. */
    public void groundingSource(String content) {
        if (content != null && !content.isBlank()) {
            groundingSources.add(content);
        }
    }

    /**
     * Modele d'equipement de l'intervention en cours (systeme de reference), pas choisi par le
     * modele de langage. S'il est connu, il s'impose au filtre de la recherche documentaire.
     */
    public String interventionModel() {
        return interventionModel;
    }

    public List<String> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<String> documents() {
        return List.copyOf(documents);
    }

    public String groundingSource() {
        return String.join("\n\n", groundingSources);
    }

    static String toolName(String description) {
        int paren = description.indexOf('(');
        return forLog(paren > 0 ? description.substring(0, paren) : description);
    }

    static String forLog(String text) {
        String oneLine = text.replaceAll("[\\r\\n\\t]+", " ");
        return oneLine.length() > MAX_LOGGED_CHARS ? oneLine.substring(0, MAX_LOGGED_CHARS) + "..." : oneLine;
    }

    /**
     * La trace est obligatoire : sans elle, le plafond d'appels et la source du controle
     * d'ancrage seraient silencieusement desactives.
     */
    static ToolTrace from(ToolContext context) {
        if (context != null && context.getContext().get(KEY) instanceof ToolTrace trace) {
            return trace;
        }
        throw new IllegalStateException("ToolTrace absente du ToolContext : appel d'outil hors de TechnicianAgent");
    }
}
