package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class InterventionToolsTest {

    private final InterventionTools tools = new InterventionTools();

    @Test
    void returnsEquipmentModelForAnIntervention() {
        ToolTrace trace = new ToolTrace();
        var intervention = tools.getIntervention("int-2026-0412", new ToolContext(Map.of(ToolTrace.KEY, trace)));

        assertThat(intervention.modele()).isEqualTo("Condensa 24");
        assertThat(intervention.historiquePannes()).hasSize(2);
        assertThat(trace.toolCalls()).containsExactly("getIntervention(int-2026-0412)");
    }

    @Test
    void unknownInterventionIsAnError() {
        assertThatThrownBy(() -> tools.getIntervention("INT-0000", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checksStock() {
        assertThat(tools.checkSparePartStock("TH-PR-4018", null).quantiteDepot()).isEqualTo(3);
        assertThat(tools.checkSparePartStock("XX-UNKNOWN", null).quantiteDepot()).isZero();
    }
}
