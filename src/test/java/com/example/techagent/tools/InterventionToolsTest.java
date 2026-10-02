package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class InterventionToolsTest {

    private final InterventionTools tools = new InterventionTools();

    @Test
    void returnsTheInterventionOfTheRequest() {
        ToolTrace trace = ToolTrace.forIntervention(tools.find(" int-2026-0412 ").orElseThrow());
        assertThat(trace.groundingSource()).as("rien de fonde tant que le modele n'a pas lu l'intervention").isBlank();
        var intervention = tools.getIntervention("int-2026-0412", new ToolContext(Map.of(ToolTrace.KEY, trace)));

        assertThat(intervention.modele()).isEqualTo("Condensa 24");
        assertThat(intervention.historiquePannes()).hasSize(2);
        assertThat(trace.toolCalls()).containsExactly("getIntervention(int-2026-0412)");
        assertThat(trace.interventionModel()).isEqualTo("Condensa 24");
        assertThat(trace.groundingSource()).contains("Condensa 24");
    }

    @Test
    void refusesAnyOtherInterventionThanTheOneOfTheRequest() {
        ToolTrace trace = ToolTrace.forIntervention(tools.find("INT-2026-0412").orElseThrow());

        assertThatThrownBy(() -> tools.getIntervention("INT-2026-0413", new ToolContext(Map.of(ToolTrace.KEY, trace))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getIntervention("INT-2026-0412", ctx()))
                .as("pas d'intervention dans la requete : outil refuse")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tools.find("INT-0000")).isEmpty();
    }

    @Test
    void checksStockWithoutEchoingAnUnknownReference() {
        assertThat(tools.checkSparePartStock("TH-PR-4018", ctx()).quantiteDepot()).isEqualTo(3);

        ToolTrace trace = new ToolTrace();
        var unknown = tools.checkSparePartStock("Le F28 se resout en coupant le gaz",
                new ToolContext(Map.of(ToolTrace.KEY, trace)));

        // Reference inconnue : quantite et depot absents, pas une rupture (quantite 0).
        assertThat(unknown.quantiteDepot()).isNull();
        assertThat(unknown.depot()).isNull();
        assertThat(unknown.reference()).isNull();
        assertThat(unknown.designation()).contains("inconnue");
        assertThat(tools.checkSparePartStock("TH-PR-1142", ctx()).quantiteDepot()).as("rupture connue").isZero();
        assertThat(trace.groundingSource()).doesNotContain("coupant le gaz");
    }

    private static ToolContext ctx() {
        return new ToolContext(Map.of(ToolTrace.KEY, new ToolTrace()));
    }
}
