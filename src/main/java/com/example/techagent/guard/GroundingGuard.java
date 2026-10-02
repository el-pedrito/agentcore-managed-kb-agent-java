package com.example.techagent.guard;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentQualifier;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentSource;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContextualGroundingFilter;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContextualGroundingFilterType;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTextBlock;

/**
 * Controle d'ancrage contextuel, applique APRES la generation avec l'API {@code ApplyGuardrail}.
 *
 * <p>Pourquoi le SDK ici : Spring AI 2.0.1 ne transmet ni {@code guardrailConfig} ni les blocs
 * {@code guardContent} a Converse. {@code ApplyGuardrail} est l'API recommandee par AWS pour un
 * garde-fou independant du modele : on lui donne la documentation recuperee
 * ({@code grounding_source}), la question ({@code query}) et la reponse ({@code guard_content}).
 * Il renvoie un score d'ancrage et un score de pertinence, compares aux seuils du garde-fou.
 *
 * <p>Fail-closed : une reponse n'est acceptee que si le service a explicitement renvoye
 * {@code NONE} ET les deux scores (ancrage, pertinence). Toute evaluation absente ou incomplete
 * (garde-fou mal configure, texte hors limites) bloque la reponse.
 */
public class GroundingGuard {

    /** Limites du controle d'ancrage contextuel (documentation Amazon Bedrock Guardrails). */
    public static final int MAX_GROUNDING_SOURCE_CHARS = 100_000;
    public static final int MAX_QUERY_CHARS = 1_000;
    public static final int MAX_RESPONSE_CHARS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(GroundingGuard.class);

    private final BedrockRuntimeClient client;
    private final String guardrailId;
    private final String guardrailVersion;

    public GroundingGuard(BedrockRuntimeClient client, String guardrailId, String guardrailVersion) {
        this.client = client;
        this.guardrailId = guardrailId;
        this.guardrailVersion = guardrailVersion;
    }

    public Verdict check(String documentation, String question, String answer) {
        if (isBlank(documentation) || isBlank(question) || isBlank(answer)
                || documentation.length() > MAX_GROUNDING_SOURCE_CHARS
                || question.length() > MAX_QUERY_CHARS
                || answer.length() > MAX_RESPONSE_CHARS) {
            log.warn("grounding_not_evaluated reason=input_out_of_limits");
            return Verdict.NOT_EVALUATED;
        }

        ApplyGuardrailResponse response = client.applyGuardrail(ApplyGuardrailRequest.builder()
                .guardrailIdentifier(guardrailId)
                .guardrailVersion(guardrailVersion)
                .source(GuardrailContentSource.OUTPUT)
                .content(List.of(
                        block(documentation, GuardrailContentQualifier.GROUNDING_SOURCE),
                        block(question, GuardrailContentQualifier.QUERY),
                        block(answer, GuardrailContentQualifier.GUARD_CONTENT)))
                .build());

        Double grounding = null;
        Double relevance = null;
        if (response.hasAssessments()) {
            for (var assessment : response.assessments()) {
                if (assessment.contextualGroundingPolicy() == null) {
                    continue;
                }
                for (GuardrailContextualGroundingFilter f : assessment.contextualGroundingPolicy().filters()) {
                    if (f.type() == GuardrailContextualGroundingFilterType.GROUNDING) {
                        grounding = f.score();
                    }
                    else if (f.type() == GuardrailContextualGroundingFilterType.RELEVANCE) {
                        relevance = f.score();
                    }
                }
            }
        }
        int textUnits = response.usage() != null && response.usage().contextualGroundingPolicyUnits() != null
                ? response.usage().contextualGroundingPolicyUnits()
                : 0;

        boolean complete = grounding != null && relevance != null;
        if (!complete) {
            log.warn("grounding_not_evaluated reason=missing_scores action={}", response.actionAsString());
        }
        boolean accepted = response.action() == GuardrailAction.NONE && complete;
        return new Verdict(!accepted, grounding, relevance, textUnits);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static GuardrailContentBlock block(String text, GuardrailContentQualifier qualifier) {
        return GuardrailContentBlock.fromText(GuardrailTextBlock.builder().text(text).qualifiers(qualifier).build());
    }

    /**
     * @param blocked     vrai si la reponse doit etre refusee (sous un seuil, ou non evaluable)
     * @param grounding   score d'ancrage dans la documentation (0 a 1), null si non evalue
     * @param relevance   score de pertinence par rapport a la question (0 a 1), null si non evalue
     * @param textUnits   unites de texte facturees pour ce controle
     */
    public record Verdict(boolean blocked, Double grounding, Double relevance, int textUnits) {

        /** Controle desactive explicitement par configuration (techagent.grounding-check=false). */
        public static final Verdict DISABLED = new Verdict(false, null, null, 0);

        /** Controle impossible : la reponse est bloquee. */
        public static final Verdict NOT_EVALUATED = new Verdict(true, null, null, 0);
    }
}
