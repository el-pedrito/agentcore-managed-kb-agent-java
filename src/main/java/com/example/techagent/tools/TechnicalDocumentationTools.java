package com.example.techagent.tools;

import com.example.techagent.kb.ManagedKnowledgeBaseVectorStore;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Outil de recherche dans la Managed Knowledge Base, expose a l'agent.
 *
 * <p>Meme chemin de recherche que la demo 1 : {@link VectorStore} Spring AI implemente par
 * {@link ManagedKnowledgeBaseVectorStore} ({@code Retrieve} + {@code managedSearchConfiguration}).
 * La difference : c'est l'agent qui decide quand chercher, avec quelle formulation, et sur quel
 * modele d'equipement filtrer.
 */
@Component
public class TechnicalDocumentationTools {

    static final String MODEL_ATTRIBUTE = "modele";
    static final String ALL_MODELS = "Tous";
    static final int MAX_QUERY_CHARS = 500;

    private final VectorStore documentation;
    private final int maxResults;

    public TechnicalDocumentationTools(VectorStore documentation, @Value("${techagent.max-results:5}") int maxResults) {
        this.documentation = documentation;
        this.maxResults = maxResults;
    }

    public record DocumentationExtract(String document, String modele, Double score, String text) { }

    @Tool(description = """
            Recherche dans la documentation technique des fabricants (notices, manuels de service,
            codes defauts, valeurs de reglage, procedures de securite). A appeler avant toute
            reponse technique. Renvoie des extraits avec le nom du document source.""")
    public List<DocumentationExtract> searchTechnicalDocumentation(
            @ToolParam(description = "Recherche formulee en francais, precise, par exemple 'code defaut F28 signification et actions'") String query,
            ToolContext toolContext) {

        ToolTrace trace = ToolTrace.from(toolContext);
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("Recherche vide ou trop longue (" + MAX_QUERY_CHARS + " caracteres max)");
        }
        // Le filtre de modele ne vient que d'une donnee de confiance : l'intervention resolue par
        // l'application. Le modele de langage n'a aucun parametre pour restreindre la recherche
        // (injection, hallucination). Sans intervention : recherche sans filtre, et la reponse doit
        // distinguer les modeles.
        String model = trace.interventionModel();
        trace.requireCall("searchTechnicalDocumentation(\"" + query + "\""
                + (hasText(model) ? ", modele=" + model : "") + ")");

        SearchRequest.Builder request = SearchRequest.builder().query(query).topK(maxResults);
        if (hasText(model)) {
            // Documentation du modele + documents transverses (securite, procedures).
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            request.filterExpression(b.or(
                    b.eq(MODEL_ATTRIBUTE, model.trim()),
                    b.eq(MODEL_ATTRIBUTE, ALL_MODELS)).build());
        }

        List<DocumentationExtract> extracts;
        try {
            extracts = documentation.similaritySearch(request.build()).stream()
                    .map(TechnicalDocumentationTools::toExtract)
                    .toList();
        }
        catch (SdkException e) {
            // L'agent repondra UNAVAILABLE (panne AWS), pas BLOCKED (qui orienterait le technicien
            // vers sa saisie). Message generique pour le modele : pas d'ARN ni de detail AWS.
            trace.unavailable();
            throw new IllegalStateException("Recherche documentaire momentanement indisponible", e);
        }
        trace.documentationSearched();

        extracts.forEach(e -> {
            trace.document(e.document());
            // Le modele d'equipement fait partie de la source : une reponse qui attribue un fait
            // au mauvais modele n'est pas fondee.
            trace.groundingSource("[" + e.document() + " | modele : "
                    + (e.modele() == null ? "non precise" : e.modele()) + "]\n" + e.text());
        });
        return extracts;
    }

    private static DocumentationExtract toExtract(Document d) {
        String uri = (String) d.getMetadata().get(ManagedKnowledgeBaseVectorStore.SOURCE_URI);
        String name = uri == null ? "document inconnu" : uri.substring(uri.lastIndexOf('/') + 1);
        return new DocumentationExtract(name, (String) d.getMetadata().get(MODEL_ATTRIBUTE), d.getScore(), d.getText());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
