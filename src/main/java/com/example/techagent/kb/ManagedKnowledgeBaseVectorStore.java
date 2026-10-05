package com.example.techagent.kb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseQuery;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.ManagedSearchConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;

/**
 * Spring AI {@link VectorStore} on top of a <b>managed</b> Bedrock Knowledge Base.
 *
 * <p>Why this class: the Bedrock Knowledge Base vector store shipped with Spring AI 2.0.1 always
 * sends a {@code vectorSearchConfiguration}, which a managed Knowledge Base rejects. So we keep the
 * Spring AI abstraction ({@code VectorStore}, {@code SearchRequest}, portable filter expressions)
 * and call {@code Retrieve} with {@code managedSearchConfiguration} through the AWS SDK, in a
 * reusable and tested class.
 *
 * <p>Read-only: the Knowledge Base is fed by syncing its data source (S3), not through
 * {@link #add(List)}.
 */
public class ManagedKnowledgeBaseVectorStore implements VectorStore {

    /** Metadata key that carries the source document URI in the returned {@link Document}s. */
    public static final String SOURCE_URI = "source_uri";

    private final BedrockAgentRuntimeClient client;
    private final String knowledgeBaseId;

    public ManagedKnowledgeBaseVectorStore(BedrockAgentRuntimeClient client, String knowledgeBaseId) {
        this.client = client;
        this.knowledgeBaseId = knowledgeBaseId;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        ManagedSearchConfiguration.Builder search = ManagedSearchConfiguration.builder()
                .numberOfResults(request.getTopK());
        if (request.getFilterExpression() != null) {
            search.filter(toRetrievalFilter(request.getFilterExpression()));
        }

        RetrieveRequest retrieve = RetrieveRequest.builder()
                .knowledgeBaseId(knowledgeBaseId)
                .retrievalQuery(KnowledgeBaseQuery.builder().text(request.getQuery()).build())
                .retrievalConfiguration(KnowledgeBaseRetrievalConfiguration.builder()
                        .managedSearchConfiguration(search.build())
                        .build())
                .build();

        return client.retrieve(retrieve).retrievalResults().stream()
                .filter(r -> r.content() != null && r.content().text() != null)
                .filter(r -> r.score() == null || r.score() >= request.getSimilarityThreshold())
                .map(ManagedKnowledgeBaseVectorStore::toDocument)
                .toList();
    }

    /**
     * Translates a Spring AI filter expression into a Knowledge Base filter.
     * Supported operators (the ones the demo needs): {@code ==}, {@code &&}, {@code ||}.
     */
    static RetrievalFilter toRetrievalFilter(Filter.Expression expression) {
        return switch (expression.type()) {
            case AND -> RetrievalFilter.fromAndAll(List.of(
                    toRetrievalFilter(asExpression(expression.left())),
                    toRetrievalFilter(asExpression(expression.right()))));
            case OR -> RetrievalFilter.fromOrAll(List.of(
                    toRetrievalFilter(asExpression(expression.left())),
                    toRetrievalFilter(asExpression(expression.right()))));
            case EQ -> RetrievalFilter.fromEqualsValue(attribute(expression));
            default -> throw new IllegalArgumentException(
                    "Filter operator not supported by the managed Knowledge Base: " + expression.type());
        };
    }

    private static Filter.Expression asExpression(Filter.Operand operand) {
        if (operand instanceof Filter.Expression e) {
            return e;
        }
        if (operand instanceof Filter.Group g) {
            return g.content();
        }
        throw new IllegalArgumentException("Unexpected filter operand: " + operand);
    }

    private static FilterAttribute attribute(Filter.Expression expression) {
        String key = ((Filter.Key) expression.left()).key();
        String value = String.valueOf(((Filter.Value) expression.right()).value());
        return FilterAttribute.builder().key(key)
                .value(software.amazon.awssdk.core.document.Document.fromString(value)).build();
    }

    private static Document toDocument(KnowledgeBaseRetrievalResult r) {
        Map<String, Object> metadata = new HashMap<>();
        if (r.hasMetadata()) {
            r.metadata().forEach((k, v) -> {
                if (v != null && v.isString()) {
                    metadata.put(k, v.asString());
                }
            });
        }
        if (r.location() != null && r.location().s3Location() != null) {
            metadata.put(SOURCE_URI, r.location().s3Location().uri());
        }
        return Document.builder()
                .text(r.content().text())
                .metadata(metadata)
                .score(r.score())
                .build();
    }

    @Override
    public void add(List<Document> documents) {
        throw new UnsupportedOperationException(
                "Managed Knowledge Base: upload to the S3 data source, then start a sync");
    }

    @Override
    public void delete(List<String> idList) {
        throw new UnsupportedOperationException("Deletion through the S3 data source only");
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException("Deletion through the S3 data source only");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getNativeClient() {
        return Optional.of((T) client);
    }
}
