package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.techagent.kb.ManagedKnowledgeBaseVectorStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class TechnicalDocumentationToolsTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final TechnicalDocumentationTools tools = new TechnicalDocumentationTools(vectorStore, 5);

    @Test
    void theInterventionModelIsTheOnlyFilterAndKeepsCrossModelDocuments() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        tools.searchTechnicalDocumentation("code F28", ctx(new ToolTrace(new InterventionTools().find("INT-2026-0413").orElseThrow())));

        SearchRequest sent = capture();
        assertThat(sent.getQuery()).isEqualTo("code F28");
        assertThat(sent.getTopK()).isEqualTo(5);
        assertThat(sent.getFilterExpression().toString()).contains("Ecoline 35").contains("ALL").contains("OR");
    }

    @Test
    void withoutInterventionTheSearchIsNeverFiltered() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        // The language model has no parameter to narrow the search.
        tools.searchTechnicalDocumentation("code F28 sur Ecoline 35", ctx(new ToolTrace(null)));

        assertThat(capture().getFilterExpression()).isNull();
    }

    @Test
    void returnsExtractsAndFeedsTheGroundingSource() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder()
                .text("F28 : défaut d'allumage répété")
                .metadata(Map.of(ManagedKnowledgeBaseVectorStore.SOURCE_URI, "s3://b/docs/vaporis/manuel.md",
                        "model", "Ecoline 35"))
                .score(0.7)
                .build()));
        ToolTrace trace = new ToolTrace(null);

        var extracts = tools.searchTechnicalDocumentation("F28", ctx(trace));

        assertThat(extracts).singleElement().satisfies(e -> {
            assertThat(e.document()).isEqualTo("manuel.md");
            assertThat(e.model()).isEqualTo("Ecoline 35");
        });
        assertThat(trace.documents()).containsExactly("manuel.md");
        // The equipment model is part of the grounding check source.
        assertThat(trace.groundingSource()).contains("[manuel.md | model: Ecoline 35]");
    }

    private SearchRequest capture() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    private static ToolContext ctx(ToolTrace trace) {
        return new ToolContext(Map.of(ToolTrace.KEY, trace));
    }
}
