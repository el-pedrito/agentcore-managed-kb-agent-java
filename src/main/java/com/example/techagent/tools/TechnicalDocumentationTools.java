package com.example.techagent.tools;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.ManagedSearchConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;

/**
 * Outil de recherche dans la Managed Knowledge Base.
 *
 * <p>Une Knowledge Base managee n'accepte ni {@code vectorSearchConfiguration} ni
 * RetrieveAndGenerate : on appelle {@code Retrieve} avec {@code managedSearchConfiguration}.
 * C'est l'agent qui decide quand chercher, avec quelle formulation, et sur quel modele filtrer.
 */
@Component
public class TechnicalDocumentationTools {

    static final String MODEL_ATTRIBUTE = "modele";
    static final String ALL_MODELS = "Tous";

    private final BedrockAgentRuntimeClient client;
    private final String knowledgeBaseId;
    private final int maxResults;

    public TechnicalDocumentationTools(BedrockAgentRuntimeClient client,
            @Value("${techagent.knowledge-base-id}") String knowledgeBaseId,
            @Value("${techagent.max-results:5}") int maxResults) {
        this.client = client;
        this.knowledgeBaseId = knowledgeBaseId;
        this.maxResults = maxResults;
    }

    public record DocumentationExtract(String document, String modele, Double score, String text) { }

    @Tool(description = """
            Recherche dans la documentation technique des fabricants (notices, manuels de service,
            codes defauts, valeurs de reglage, procedures de securite). A appeler avant toute
            reponse technique. Renvoie des extraits avec le nom du document source.""")
    public List<DocumentationExtract> searchTechnicalDocumentation(
            @ToolParam(description = "Recherche formulee en francais, precise, par exemple 'code defaut F28 signification et actions'") String query,
            @ToolParam(description = "Modele exact de l'equipement, par exemple 'Condensa 24'. Vide si inconnu.", required = false) String equipmentModel,
            ToolContext toolContext) {

        ToolTrace trace = ToolTrace.from(toolContext);
        trace.call("searchTechnicalDocumentation(\"" + query + "\""
                + (hasText(equipmentModel) ? ", modele=" + equipmentModel : "") + ")");

        ManagedSearchConfiguration.Builder search = ManagedSearchConfiguration.builder().numberOfResults(maxResults);
        if (hasText(equipmentModel)) {
            search.filter(RetrievalFilter.fromOrAll(List.of(
                    equalsModel(equipmentModel.trim()), equalsModel(ALL_MODELS))));
        }

        List<DocumentationExtract> extracts = client.retrieve(RetrieveRequest.builder()
                        .knowledgeBaseId(knowledgeBaseId)
                        .retrievalQuery(q -> q.text(query))
                        .retrievalConfiguration(rc -> rc.managedSearchConfiguration(search.build()))
                        .build())
                .retrievalResults().stream()
                .filter(r -> r.content() != null && r.content().text() != null)
                .map(TechnicalDocumentationTools::toExtract)
                .toList();

        extracts.forEach(e -> trace.document(e.document()));
        return extracts;
    }

    private static DocumentationExtract toExtract(KnowledgeBaseRetrievalResult r) {
        String uri = r.location() != null && r.location().s3Location() != null ? r.location().s3Location().uri() : null;
        String name = uri == null ? "document inconnu" : uri.substring(uri.lastIndexOf('/') + 1);
        Map<String, Document> meta = r.hasMetadata() ? r.metadata() : Map.of();
        Document model = meta.get(MODEL_ATTRIBUTE);
        return new DocumentationExtract(name, model != null && model.isString() ? model.asString() : null,
                r.score(), r.content().text());
    }

    private static RetrievalFilter equalsModel(String model) {
        return RetrievalFilter.fromEqualsValue(FilterAttribute.builder()
                .key(MODEL_ATTRIBUTE).value(Document.fromString(model)).build());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
