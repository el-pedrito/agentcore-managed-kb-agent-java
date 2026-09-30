package com.example.techagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultLocation;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultS3Location;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

class TechnicalDocumentationToolsTest {

    private final BedrockAgentRuntimeClient client = mock(BedrockAgentRuntimeClient.class);
    private final TechnicalDocumentationTools tools = new TechnicalDocumentationTools(client, "KB123", 5);

    @Test
    void searchesWithManagedConfigurationAndModelFilter() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());

        tools.searchTechnicalDocumentation("code F28", "Ecoline 35", null);

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(client).retrieve(captor.capture());
        var config = captor.getValue().retrievalConfiguration();
        assertThat(config.vectorSearchConfiguration()).isNull();
        assertThat(config.managedSearchConfiguration().numberOfResults()).isEqualTo(5);
        assertThat(config.managedSearchConfiguration().filter().orAll())
                .extracting(f -> f.equalsValue().value().asString())
                .containsExactly("Ecoline 35", "Tous");
    }

    @Test
    void noFilterWhenModelIsUnknown() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());

        tools.searchTechnicalDocumentation("code F28", "", null);

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(client).retrieve(captor.capture());
        assertThat(captor.getValue().retrievalConfiguration().managedSearchConfiguration().filter()).isNull();
    }

    @Test
    void returnsExtractsAndRecordsTrace() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder()
                .retrievalResults(KnowledgeBaseRetrievalResult.builder()
                        .content(RetrievalResultContent.builder().text("F28 : défaut d'allumage répété").build())
                        .location(RetrievalResultLocation.builder().s3Location(
                                RetrievalResultS3Location.builder().uri("s3://b/docs/vaporis/manuel.md").build()).build())
                        .metadata(Map.of("modele", Document.fromString("Ecoline 35")))
                        .score(0.7)
                        .build())
                .build());
        ToolTrace trace = new ToolTrace();

        List<TechnicalDocumentationTools.DocumentationExtract> extracts =
                tools.searchTechnicalDocumentation("F28", "Ecoline 35", new ToolContext(Map.of(ToolTrace.KEY, trace)));

        assertThat(extracts).singleElement().satisfies(e -> {
            assertThat(e.document()).isEqualTo("manuel.md");
            assertThat(e.modele()).isEqualTo("Ecoline 35");
        });
        assertThat(trace.toolCalls()).singleElement().asString().contains("F28").contains("Ecoline 35");
        assertThat(trace.documents()).containsExactly("manuel.md");
    }
}
