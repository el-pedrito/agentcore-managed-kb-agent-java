package com.example.techagent.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** quantiteDepot et depot sont null quand la reference n'est pas suivie : inconnu n'est pas zero. */
    public record StockPiece(String reference, String designation, Integer quantiteDepot, String depot) { }

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
        ToolTrace trace = ToolTrace.from(toolContext);
        trace.requireCall("getIntervention(" + interventionId + ")");
        // Seule l'intervention de la requete, resolue par l'application avant la boucle, est
        // consultable : le modele de langage ne choisit pas l'ordre de travail qu'il lit. En
        // production, c'est aussi la ou s'applique le controle d'acces du technicien.
        String trusted = trace.trustedInterventionId();
        if (trusted == null || !trusted.equals(normalize(interventionId))) {
            throw new IllegalArgumentException("Seule l'intervention en cours peut etre consultee");
        }
        Intervention intervention = INTERVENTIONS.get(trusted);
        // Le contenu entre dans la source du controle d'ancrage seulement maintenant, quand le
        // modele l'a effectivement recu : il ne peut pas etre "fonde" sur ce qu'il n'a pas lu.
        trace.groundingSource("Intervention " + intervention);
        return intervention;
    }

    /** Resolution par l'application (pas par le modele de langage) du numero recu dans la requete. */
    public Optional<Intervention> find(String interventionId) {
        return Optional.ofNullable(INTERVENTIONS.get(normalize(interventionId)));
    }

    private static String normalize(String id) {
        return id == null ? "" : id.strip().toUpperCase();
    }

    @Tool(description = """
            Verifie la disponibilite d'une piece de rechange au depot a partir de sa reference
            fabricant (references trouvees dans la documentation technique).""")
    public StockPiece checkSparePartStock(
            @ToolParam(description = "Reference fabricant de la piece, par exemple TH-PR-4018") String reference,
            ToolContext toolContext) {
        ToolTrace.from(toolContext).requireCall("checkSparePartStock(" + reference + ")");
        StockPiece piece = STOCK.get(normalize(reference));
        if (piece == null) {
            // Ne jamais renvoyer l'argument du modele comme donnee de reference : sinon un texte
            // invente par le modele deviendrait une source "fondee" pour le controle d'ancrage.
            String status = "Référence non suivie en stock : disponibilité inconnue (ce n'est pas une rupture).";
            ToolTrace.from(toolContext).groundingSource("Stock : " + status);
            return new StockPiece(null, status, null, null);
        }
        ToolTrace.from(toolContext).groundingSource("Stock " + piece);
        return piece;
    }
}
