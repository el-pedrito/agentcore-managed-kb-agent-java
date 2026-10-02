package com.example.techagent.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultLocation;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultS3Location;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

class ManagedKnowledgeBaseVectorStoreTest {

    private final BedrockAgentRuntimeClient client = mock(BedrockAgentRuntimeClient.class);
    private final ManagedKnowledgeBaseVectorStore store = new ManagedKnowledgeBaseVectorStore(client, "KB123");

    @Test
    void usesManagedSearchConfigurationAndNeverVectorSearch() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());

        store.similaritySearch(SearchRequest.builder().query("F28 sur la chaudière").topK(5).build());

        RetrieveRequest sent = captureRequest();
        assertThat(sent.knowledgeBaseId()).isEqualTo("KB123");
        assertThat(sent.retrievalQuery().text()).isEqualTo("F28 sur la chaudière");
        assertThat(sent.retrievalConfiguration().vectorSearchConfiguration()).isNull();
        assertThat(sent.retrievalConfiguration().managedSearchConfiguration().numberOfResults()).isEqualTo(5);
        assertThat(sent.retrievalConfiguration().managedSearchConfiguration().filter()).isNull();
    }

    @Test
    void translatesAPortableSpringAiFilter() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        store.similaritySearch(SearchRequest.builder().query("F28").topK(5)
                .filterExpression(b.or(b.eq("model", "Condensa 24"), b.eq("model", "ALL")).build())
                .build());

        RetrievalFilter filter = captureRequest().retrievalConfiguration().managedSearchConfiguration().filter();
        assertThat(filter.orAll()).hasSize(2);
        assertThat(filter.orAll().get(0).equalsValue().key()).isEqualTo("model");
        assertThat(filter.orAll().get(0).equalsValue().value().asString()).isEqualTo("Condensa 24");
        assertThat(filter.orAll().get(1).equalsValue().value().asString()).isEqualTo("ALL");
    }

    @Test
    void translatesTextFiltersWithGroups() {
        var expression = new FilterExpressionTextParser()
                .parse("(manufacturer == 'Thermalys' || manufacturer == 'Vaporis') && model == 'Condensa 24'");

        RetrievalFilter filter = ManagedKnowledgeBaseVectorStore.toRetrievalFilter(expression);

        assertThat(filter.andAll()).hasSize(2);
        assertThat(filter.andAll().get(0).orAll()).hasSize(2);
        assertThat(filter.andAll().get(1).equalsValue().value().asString()).isEqualTo("Condensa 24");
    }

    @Test
    void rejectsOperatorsTheKnowledgeBaseDoesNotSupportExplicitly() {
        var expression = new FilterExpressionTextParser().parse("year >= 2020");
        // Operator not translated by the demo: refuse rather than silently ignore the filter.
        assertThatThrownBy(() -> ManagedKnowledgeBaseVectorStore.toRetrievalFilter(expression))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsResultsToSpringAiDocuments() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder()
                .retrievalResults(result("F28 : pression trop basse", 0.82))
                .build());

        List<Document> docs = store.similaritySearch(SearchRequest.builder().query("F28").topK(5).build());

        assertThat(docs).singleElement().satisfies(d -> {
            assertThat(d.getText()).isEqualTo("F28 : pression trop basse");
            assertThat(d.getScore()).isEqualTo(0.82);
            assertThat(d.getMetadata())
                    .containsEntry("model", "Condensa 24")
                    .containsEntry("manufacturer", "Thermalys")
                    .containsEntry(ManagedKnowledgeBaseVectorStore.SOURCE_URI, "s3://bucket/thermalys/notice.md");
        });
    }

    @Test
    void appliesTheSimilarityThreshold() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder()
                .retrievalResults(result("pertinent", 0.82), result("bruit", 0.10))
                .build());

        List<Document> docs = store.similaritySearch(
                SearchRequest.builder().query("F28").topK(5).similarityThreshold(0.5).build());

        assertThat(docs).extracting(Document::getText).containsExactly("pertinent");
    }

    @Test
    void isReadOnly() {
        assertThatThrownBy(() -> store.add(List.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    private static KnowledgeBaseRetrievalResult result(String text, double score) {
        return KnowledgeBaseRetrievalResult.builder()
                .content(RetrievalResultContent.builder().text(text).build())
                .location(RetrievalResultLocation.builder()
                        .s3Location(RetrievalResultS3Location.builder().uri("s3://bucket/thermalys/notice.md").build())
                        .build())
                .metadata(Map.of(
                        "model", software.amazon.awssdk.core.document.Document.fromString("Condensa 24"),
                        "manufacturer", software.amazon.awssdk.core.document.Document.fromString("Thermalys")))
                .score(score)
                .build();
    }

    private RetrieveRequest captureRequest() {
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(client).retrieve(captor.capture());
        return captor.getValue();
    }
}
