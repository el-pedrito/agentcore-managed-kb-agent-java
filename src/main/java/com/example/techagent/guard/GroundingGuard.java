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
 * Contextual grounding check, applied AFTER generation with the {@code ApplyGuardrail} API.
 *
 * <p>Why the SDK here: Spring AI 2.0.1 passes neither {@code guardrailConfig} nor
 * {@code guardContent} blocks to Converse. {@code ApplyGuardrail} is the API AWS recommends for a
 * model-independent guardrail: it receives the retrieved documentation ({@code grounding_source}),
 * the question ({@code query}) and the answer ({@code guard_content}). It returns a grounding score
 * and a relevance score, compared with the guardrail thresholds.
 *
 * <p>Fail-closed: an answer is accepted only if the service explicitly returned {@code NONE} AND
 * both scores (grounding, relevance). Any missing or incomplete evaluation (misconfigured
 * guardrail, text out of limits) blocks the answer.
 */
public class GroundingGuard {

    /** Limits of the contextual grounding check (Amazon Bedrock Guardrails documentation). */
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
     * @param blocked     true if the answer must be refused (below a threshold, or not evaluable)
     * @param grounding   how well the answer is grounded in the documentation (0 to 1), null if not evaluated
     * @param relevance   how relevant the answer is to the question (0 to 1), null if not evaluated
     * @param textUnits   text units billed for this check
     */
    public record Verdict(boolean blocked, Double grounding, Double relevance, int textUnits) {

        /** Check not possible: the answer is blocked. */
        public static final Verdict NOT_EVALUATED = new Verdict(true, null, null, 0);
    }
}
