package com.example.techagent.tools;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Outils metier SIMULES (donnees en dur). Ils representent les systemes qui existent deja
 * cote SI : ordres de travail, historique des pannes, stock de pieces. En production, chaque
 * methode appelle l'API du systeme reel, ou devient une cible AgentCore Gateway sans toucher
 * au code de l'agent.
 */
@Component
public class InterventionTools {

    public record Intervention(String id, String site, String fabricant, String modele, int anneeInstallation,
            String symptomeSignale, List<String> historiquePannes, String dernierEntretien) { }

    public record StockPiece(String reference, String designation, int quantiteDepot, String depot) { }

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

    private static final Map<String, StockPiece> STOCK = Map.of(
            "TH-PR-4018", new StockPiece("TH-PR-4018", "Vase d'expansion 8 litres Condensa 24", 3, "Dépôt Lyon Sud"),
            "TH-PR-1142", new StockPiece("TH-PR-1142", "Électrode allumage et ionisation Condensa 24", 0, "Dépôt Lyon Sud"),
            "VP-SP-0620", new StockPiece("VP-SP-0620", "Vanne gaz Ecoline 35", 1, "Dépôt Lyon Nord"),
            "VP-SP-0450", new StockPiece("VP-SP-0450", "Capteur de pression d'eau Ecoline 35", 4, "Dépôt Lyon Nord"));

    @Tool(description = """
            Renvoie le contexte d'un ordre d'intervention : site, fabricant et modele exact de
            l'equipement, symptome signale, historique des pannes et date du dernier entretien.
            A appeler en premier des qu'un numero d'intervention (format INT-AAAA-NNNN) est connu.""")
    public Intervention getIntervention(
            @ToolParam(description = "Numero d'intervention, par exemple INT-2026-0412") String interventionId,
            ToolContext toolContext) {
        ToolTrace.from(toolContext).call("getIntervention(" + interventionId + ")");
        Intervention intervention = INTERVENTIONS.get(interventionId == null ? "" : interventionId.trim().toUpperCase());
        if (intervention == null) {
            throw new IllegalArgumentException("Intervention inconnue : " + interventionId);
        }
        return intervention;
    }

    @Tool(description = """
            Verifie la disponibilite d'une piece de rechange au depot a partir de sa reference
            fabricant (references trouvees dans la documentation technique).""")
    public StockPiece checkSparePartStock(
            @ToolParam(description = "Reference fabricant de la piece, par exemple TH-PR-4018") String reference,
            ToolContext toolContext) {
        ToolTrace.from(toolContext).call("checkSparePartStock(" + reference + ")");
        StockPiece piece = STOCK.get(reference == null ? "" : reference.trim().toUpperCase());
        return piece != null ? piece : new StockPiece(reference, "Référence non suivie en stock", 0, "aucun");
    }
}
