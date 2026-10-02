package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class InterventionToolsTest {

    private final InterventionTools tools = new InterventionTools();

    @Test
    void returnsTheInterventionOfTheRequestOnly() {
        ToolTrace trace = new ToolTrace(tools.find(" int-2026-0412 ").orElseThrow());

        var intervention = tools.getIntervention(ctx(trace));

        assertThat(intervention.model()).isEqualTo("Condensa 24");
        assertThat(trace.toolCalls()).containsExactly("getIntervention()");
        assertThat(trace.groundingSource()).contains("Condensa 24");
    }

    @Test
    void refusesWhenNoInterventionIsInProgress() {
        assertThatThrownBy(() -> tools.getIntervention(ctx(new ToolTrace(null))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tools.find("INT-0000")).isEmpty();
    }

    @Test
    void anUnknownPartIsUnknownNotOutOfStock() {
        ToolTrace trace = new ToolTrace(null);

        var unknown = tools.checkSparePartStock("Le F28 se resout en coupant le gaz", ctx(trace));

        assertThat(unknown.depotQuantity()).isNull();
        assertThat(unknown.description()).contains("inconnue");
        // The reference invented by the model does not become a "grounded" source.
        assertThat(trace.groundingSource()).doesNotContain("coupant le gaz");
        assertThat(tools.checkSparePartStock("TH-PR-4018", ctx(trace)).depotQuantity()).isEqualTo(3);
        assertThat(tools.checkSparePartStock("TH-PR-1142", ctx(trace)).depotQuantity()).as("known stock-out").isZero();
    }

    private static ToolContext ctx(ToolTrace trace) {
        return new ToolContext(Map.of(ToolTrace.KEY, trace));
    }
}
