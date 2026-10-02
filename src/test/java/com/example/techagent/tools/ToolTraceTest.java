package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class ToolTraceTest {

    @Test
    void stopsToolsBeyondTheCallLimit() {
        ToolTrace trace = new ToolTrace();
        for (int i = 0; i < ToolTrace.MAX_TOOL_CALLS; i++) {
            trace.requireCall("searchTechnicalDocumentation(" + i + ")");
        }

        assertThatThrownBy(() -> trace.requireCall("searchTechnicalDocumentation(again)"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ToolTrace.LIMIT_REACHED);
        assertThat(trace.toolCalls()).hasSize(ToolTrace.MAX_TOOL_CALLS + 1);
    }

    @Test
    void toolsRefuseToRunOnceTheLimitIsReached() {
        ToolTrace trace = new ToolTrace();
        for (int i = 0; i < ToolTrace.MAX_TOOL_CALLS; i++) {
            trace.requireCall("call " + i);
        }
        InterventionTools tools = new InterventionTools();

        assertThatThrownBy(() -> tools.checkSparePartStock("TH-PR-4018", new ToolContext(Map.of(ToolTrace.KEY, trace))))
                .hasMessage(ToolTrace.LIMIT_REACHED);
        assertThat(trace.groundingSource()).isEmpty();
    }

    @Test
    void refusesToRunAToolWithoutATrace() {
        assertThatThrownBy(() -> new InterventionTools().checkSparePartStock("TH-PR-4018", new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logsModelGeneratedTextOnOneBoundedLine() {
        assertThat(ToolTrace.forLog("a\nb\r\nc")).isEqualTo("a b c");
        assertThat(ToolTrace.forLog("x".repeat(500))).hasSize(123);
    }

    @Test
    void theRetrySharesTheCallBudgetButNotTheEvidence() {
        ToolTrace first = ToolTrace.forIntervention(new InterventionTools().find("INT-2026-0412").orElseThrow());
        for (int i = 0; i < ToolTrace.MAX_TOOL_CALLS; i++) {
            first.requireCall("call " + i);
        }
        first.document("notice.md");

        ToolTrace retry = first.forRetry();

        assertThatThrownBy(() -> retry.requireCall("one more")).hasMessage(ToolTrace.LIMIT_REACHED);
        assertThat(retry.documents()).isEmpty();
        assertThat(retry.interventionModel()).isEqualTo("Condensa 24");
        // Aucune preuve heritee : l'intervention ne redevient une source qu'a un nouvel appel de getIntervention.
        assertThat(retry.groundingSource()).isBlank();
    }

    @Test
    void theLimitReachesTheModelAsAToolResultNotAsAnInvocationFailure() {
        ToolTrace trace = new ToolTrace();
        for (int i = 0; i < ToolTrace.MAX_TOOL_CALLS; i++) {
            trace.requireCall("call " + i);
        }
        RuntimeException limit = org.assertj.core.api.Assertions.catchRuntimeException(() -> trace.requireCall("one more"));

        // Comportement par defaut de Spring AI (spring.ai.tools.throw-exception-on-error=false) :
        // l'exception d'outil devient le texte du resultat renvoye au modele, la boucle continue.
        var processor = org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor.builder().build();
        var definition = org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                .name("searchTechnicalDocumentation").description("d").inputSchema("{}").build();
        assertThat(processor.process(new org.springframework.ai.tool.execution.ToolExecutionException(definition, limit)))
                .isEqualTo(ToolTrace.LIMIT_REACHED);
    }

    @Test
    void logsOnlyTheToolNameNotItsArguments() {
        assertThat(ToolTrace.toolName("searchTechnicalDocumentation(\"odeur de gaz chez M. Dupont\")"))
                .isEqualTo("searchTechnicalDocumentation");
    }

    @Test
    void joinsGroundingSourcesAndIgnoresBlanks() {
        ToolTrace trace = new ToolTrace();
        trace.groundingSource("[a.md]\nF28");
        trace.groundingSource("  ");
        trace.groundingSource("Stock X");

        assertThat(trace.groundingSource()).isEqualTo("[a.md]\nF28\n\nStock X");
    }
}
