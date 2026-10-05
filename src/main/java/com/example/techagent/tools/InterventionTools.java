package com.example.techagent.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * SIMULATED business tools (hard-coded data, in French like the real systems). They stand for the
 * systems that already exist: work orders, fault history, spare part stock. In production, each
 * method calls the real system API, or becomes an AgentCore Gateway target without touching the
 * agent code.
 */
@Component
public class InterventionTools {

    public record Intervention(String id, String site, String manufacturer, String model, int installationYear,
            String reportedSymptom, List<String> faultHistory, String lastService) { }

    /** depotQuantity and depot are null when the reference is not tracked: unknown is not zero. */
    public record SparePartStock(String reference, String description, Integer depotQuantity, String depot) { }

    private static final Map<String, Intervention> INTERVENTIONS = Map.of(
            "INT-2026-0412", new Intervention("INT-2026-0412", "Résidence Les Tilleuls, Lyon 3e, logement 12",
                    "Thermalys", "Condensa 24", 2019, "Code F28 affiché, plus de chauffage",
                    List.of("2026-08-14 : code A28, pression remise à 1,4 bar",
                            "2026-09-02 : code F28, pression remise à 1,3 bar"),
                    "2025-10-06"),
            "INT-2026-0413", new Intervention("INT-2026-0413", "Chaufferie collective, Groupe scolaire Jean Macé, Villeurbanne",
                    "Vaporis", "Ecoline 35", 2021, "Code F28 sur la chaudière 2 de la cascade",
                    List.of("2026-01-20 : code F18, câble eBUS remplacé"), "2026-06-30"),
            "INT-2026-0414", new Intervention("INT-2026-0414", "Maison individuelle, Bron",
                    "Aerotherm", "Hydra 12", 2023, "Code E9 répété le matin",
                    List.of(), "2025-11-18"));

    private static final Map<String, SparePartStock> STOCK = Map.of(
            "TH-PR-4018", new SparePartStock("TH-PR-4018", "Vase d'expansion 8 litres Condensa 24", 3, "Dépôt Lyon Sud"),
            "TH-PR-1142", new SparePartStock("TH-PR-1142", "Électrode allumage et ionisation Condensa 24", 0, "Dépôt Lyon Sud"),
            "VP-SP-0620", new SparePartStock("VP-SP-0620", "Vanne gaz Ecoline 35", 1, "Dépôt Lyon Nord"),
            "VP-SP-0450", new SparePartStock("VP-SP-0450", "Capteur de pression d'eau Ecoline 35", 4, "Dépôt Lyon Nord"));

    // The reference typed by the model is never echoed back: an invented text must not become a
    // "grounded" source for the grounding check.
    static final SparePartStock UNKNOWN_PART = new SparePartStock(null,
            "Référence non suivie en stock : disponibilité inconnue (ce n'est pas une rupture).", null, null);

    @Tool(description = """
            Returns the current work order: site, manufacturer and exact equipment model,
            reported symptom, fault history, last service.
            Call it first when an intervention is in progress.""")
    public Intervention getIntervention(ToolContext toolContext) {
        // No parameter: the intervention is the one of the request, loaded by the application.
        // The language model does not choose which work order it reads (in production, this is
        // also where the technician's access control applies).
        ToolTrace trace = ToolTrace.from(toolContext);
        trace.call("getIntervention", "getIntervention()");
        Intervention intervention = trace.intervention();
        if (intervention == null) {
            throw new IllegalStateException("No intervention in progress for this question");
        }
        trace.source("Intervention " + intervention);
        return intervention;
    }

    /** Loads, on behalf of the application, the intervention number received in the request. */
    public Optional<Intervention> find(String interventionId) {
        return Optional.ofNullable(INTERVENTIONS.get(normalize(interventionId)));
    }

    @Tool(description = """
            Checks the availability of a spare part at the depot from its manufacturer
            reference (references found in the technical documentation).""")
    public SparePartStock checkSparePartStock(
            @ToolParam(description = "Manufacturer part reference, for example TH-PR-4018") String reference,
            ToolContext toolContext) {
        ToolTrace trace = ToolTrace.from(toolContext);
        trace.call("checkSparePartStock", "checkSparePartStock(" + reference + ")");
        SparePartStock part = STOCK.getOrDefault(normalize(reference), UNKNOWN_PART);
        trace.source("Stock " + part);
        return part;
    }

    private static String normalize(String id) {
        return id == null ? "" : id.strip().toUpperCase();
    }
}
