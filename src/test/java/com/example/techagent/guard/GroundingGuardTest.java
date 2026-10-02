package com.example.techagent.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAssessment;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentQualifier;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentSource;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContextualGroundingFilter;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContextualGroundingFilterType;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContextualGroundingPolicyAssessment;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailUsage;

class GroundingGuardTest {

    private final BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
    private final GroundingGuard guard = new GroundingGuard(client, "gr-123", "1");

    @Test
    void qualifiesDocumentationQuestionAndAnswer() {
        when(client.applyGuardrail(any(ApplyGuardrailRequest.class))).thenReturn(response(GuardrailAction.NONE));

        guard.check("doc", "question", "réponse");

        ArgumentCaptor<ApplyGuardrailRequest> captor = ArgumentCaptor.forClass(ApplyGuardrailRequest.class);
        verify(client).applyGuardrail(captor.capture());
        ApplyGuardrailRequest sent = captor.getValue();
        assertThat(sent.guardrailIdentifier()).isEqualTo("gr-123");
        assertThat(sent.guardrailVersion()).isEqualTo("1");
        assertThat(sent.source()).isEqualTo(GuardrailContentSource.OUTPUT);
        assertThat(sent.content()).hasSize(3);
        assertThat(sent.content().get(0).text().qualifiers()).containsExactly(GuardrailContentQualifier.GROUNDING_SOURCE);
        assertThat(sent.content().get(1).text().qualifiers()).containsExactly(GuardrailContentQualifier.QUERY);
        assertThat(sent.content().get(2).text().qualifiers()).containsExactly(GuardrailContentQualifier.GUARD_CONTENT);
        assertThat(sent.content().get(2).text().text()).isEqualTo("réponse");
    }

    @Test
    void returnsScoresAndUnits() {
        when(client.applyGuardrail(any(ApplyGuardrailRequest.class))).thenReturn(response(GuardrailAction.NONE));

        GroundingGuard.Verdict verdict = guard.check("doc", "question", "réponse");

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.grounding()).isEqualTo(0.91);
        assertThat(verdict.relevance()).isEqualTo(0.77);
        assertThat(verdict.textUnits()).isEqualTo(3);
    }

    @Test
    void reportsAnIntervention() {
        when(client.applyGuardrail(any(ApplyGuardrailRequest.class)))
                .thenReturn(response(GuardrailAction.GUARDRAIL_INTERVENED));

        assertThat(guard.check("doc", "question", "réponse").blocked()).isTrue();
    }

    @Test
    void blocksWhenTheServiceReturnsNoScores() {
        when(client.applyGuardrail(any(ApplyGuardrailRequest.class))).thenReturn(ApplyGuardrailResponse.builder()
                .action(GuardrailAction.NONE).build());

        GroundingGuard.Verdict verdict = guard.check("doc", "question", "réponse");

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.grounding()).isNull();
    }

    @Test
    void blocksWhenOneScoreIsMissing() {
        when(client.applyGuardrail(any(ApplyGuardrailRequest.class))).thenReturn(ApplyGuardrailResponse.builder()
                .action(GuardrailAction.NONE)
                .assessments(GuardrailAssessment.builder()
                        .contextualGroundingPolicy(GuardrailContextualGroundingPolicyAssessment.builder()
                                .filters(filter(GuardrailContextualGroundingFilterType.GROUNDING, 0.95)).build())
                        .build())
                .build());

        assertThat(guard.check("doc", "question", "réponse").blocked()).isTrue();
    }

    @Test
    void blocksWithoutCallingTheServiceWhenTheQueryIsTooLong() {
        GroundingGuard.Verdict verdict = guard.check("doc", "q".repeat(GroundingGuard.MAX_QUERY_CHARS + 1), "réponse");

        assertThat(verdict.blocked()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private static ApplyGuardrailResponse response(GuardrailAction action) {
        return ApplyGuardrailResponse.builder()
                .action(action)
                .usage(GuardrailUsage.builder().contextualGroundingPolicyUnits(3).build())
                .assessments(GuardrailAssessment.builder()
                        .contextualGroundingPolicy(GuardrailContextualGroundingPolicyAssessment.builder()
                                .filters(
                                        filter(GuardrailContextualGroundingFilterType.GROUNDING, 0.91),
                                        filter(GuardrailContextualGroundingFilterType.RELEVANCE, 0.77))
                                .build())
                        .build())
                .build();
    }

    private static GuardrailContextualGroundingFilter filter(GuardrailContextualGroundingFilterType type, double score) {
        return GuardrailContextualGroundingFilter.builder().type(type).score(score).threshold(0.7).build();
    }
}
