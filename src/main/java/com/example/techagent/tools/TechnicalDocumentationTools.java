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

/**
 * Search in the Managed Knowledge Base, exposed to the agent. Same {@link VectorStore} as demo 1
 * ({@link ManagedKnowledgeBaseVectorStore}). The difference: the agent decides when to search
 * and with which wording.
 *
 * <p>The equipment model filter only comes from the intervention (trusted data). The language
 * model has no parameter to narrow the search.
 */
@Component
public class TechnicalDocumentationTools {

    static final String MODEL_ATTRIBUTE = "model";
    static final String ALL_MODELS = "ALL";

    private final VectorStore documentation;
    private final int maxResults;

    public TechnicalDocumentationTools(VectorStore documentation, @Value("${techagent.max-results:5}") int maxResults) {
        this.documentation = documentation;
        this.maxResults = maxResults;
    }

    public record DocumentationExtract(String document, String model, String text) { }

    @Tool(description = """
            Searches the manufacturers' technical documentation (manuals, fault codes, settings,
            safety procedures). Call it before any answer.""")
    public List<DocumentationExtract> searchTechnicalDocumentation(
            @ToolParam(description = "Precise search in French, for example 'code défaut F28 signification et actions'") String query,
            ToolContext toolContext) {
        ToolTrace trace = ToolTrace.from(toolContext);
        String model = trace.intervention() == null ? null : trace.intervention().model();
        trace.call("searchTechnicalDocumentation", "searchTechnicalDocumentation(\"" + query + "\""
                + (model == null ? "" : ", model=" + model) + ")");

        SearchRequest.Builder request = SearchRequest.builder().query(query).topK(maxResults);
        if (model != null) {
            // Documentation of this model + cross-model documents (safety, procedures).
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            request.filterExpression(b.or(b.eq(MODEL_ATTRIBUTE, model), b.eq(MODEL_ATTRIBUTE, ALL_MODELS)).build());
        }

        List<DocumentationExtract> extracts = documentation.similaritySearch(request.build()).stream()
                .map(TechnicalDocumentationTools::toExtract)
                .toList();
        // The equipment model is part of the grounding source: attributing a fact to the wrong
        // model is not a grounded answer.
        extracts.forEach(e -> trace.document(e.document(),
                "[" + e.document() + " | model: " + e.model() + "]\n" + e.text()));
        return extracts;
    }

    private static DocumentationExtract toExtract(Document d) {
        String uri = (String) d.getMetadata().get(ManagedKnowledgeBaseVectorStore.SOURCE_URI);
        String name = uri == null ? "unknown document" : uri.substring(uri.lastIndexOf('/') + 1);
        return new DocumentationExtract(name, (String) d.getMetadata().get(MODEL_ATTRIBUTE), d.getText());
    }
}
