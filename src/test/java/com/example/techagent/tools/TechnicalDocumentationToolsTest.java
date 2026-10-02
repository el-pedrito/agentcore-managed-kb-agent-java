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

        tools.searchTechnicalDocumentation("code F28", interventionCtx("INT-2026-0413"));

        SearchRequest sent = capture();
        assertThat(sent.getQuery()).isEqualTo("code F28");
        assertThat(sent.getTopK()).isEqualTo(5);
        assertThat(sent.getFilterExpression().toString()).contains("Ecoline 35").contains("Tous").contains("OR");
    }

    @Test
    void withoutInterventionTheSearchIsNeverFiltered() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        // Meme si la question cite un modele, le modele de langage n'a aucun moyen de restreindre
        // la recherche : seule une intervention resolue par l'application filtre.
        tools.searchTechnicalDocumentation("code F28 sur Ecoline 35", ctx());

        assertThat(capture().getFilterExpression()).isNull();
    }

    @Test
    void returnsExtractsAndRecordsTrace() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder()
                .text("F28 : défaut d'allumage répété")
                .metadata(Map.of(ManagedKnowledgeBaseVectorStore.SOURCE_URI, "s3://b/docs/vaporis/manuel.md",
                        "modele", "Ecoline 35"))
                .score(0.7)
                .build()));
        ToolTrace trace = ToolTrace.forIntervention(new InterventionTools().find("INT-2026-0413").orElseThrow());

        List<TechnicalDocumentationTools.DocumentationExtract> extracts =
                tools.searchTechnicalDocumentation("F28", new ToolContext(Map.of(ToolTrace.KEY, trace)));

        assertThat(extracts).singleElement().satisfies(e -> {
            assertThat(e.document()).isEqualTo("manuel.md");
            assertThat(e.modele()).isEqualTo("Ecoline 35");
            assertThat(e.score()).isEqualTo(0.7);
        });
        assertThat(trace.toolCalls()).singleElement().asString().contains("F28").contains("Ecoline 35");
        assertThat(trace.documents()).containsExactly("manuel.md");
        // Le modele d'equipement est dans la source du controle d'ancrage.
        assertThat(trace.groundingSource()).contains("[manuel.md | modele : Ecoline 35]");
    }

    @Test
    void anAwsFailureMarksTheTraceUnavailable() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("timeout"));
        ToolTrace trace = new ToolTrace();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.searchTechnicalDocumentation("F28",
                        new ToolContext(Map.of(ToolTrace.KEY, trace))))
                .hasMessageNotContaining("timeout");
        assertThat(trace.isUnavailable()).isTrue();
        assertThat(trace.wasDocumentationSearched()).isFalse();
    }

    private SearchRequest capture() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    private static ToolContext interventionCtx(String id) {
        return new ToolContext(Map.of(ToolTrace.KEY,
                ToolTrace.forIntervention(new InterventionTools().find(id).orElseThrow())));
    }

    private static ToolContext ctx() {
        return new ToolContext(java.util.Map.of(ToolTrace.KEY, new ToolTrace()));
    }
}
