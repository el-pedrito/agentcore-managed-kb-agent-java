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
 * {@link VectorStore} Spring AI au-dessus d'une Knowledge Base Bedrock <b>managee</b>.
 *
 * <p>Pourquoi cette classe : le vector store Bedrock Knowledge Base fourni par Spring AI 2.0.1
 * envoie toujours un {@code vectorSearchConfiguration}, que refuse une Knowledge Base managee.
 * On garde donc l'abstraction Spring AI ({@code VectorStore}, {@code SearchRequest}, expressions
 * de filtre portables) et on appelle {@code Retrieve} avec {@code managedSearchConfiguration} via
 * le SDK AWS. C'est le meme contournement que le spike Dak Tech, rendu reutilisable et teste.
 *
 * <p>Lecture seule : l'alimentation de la Knowledge Base passe par la synchronisation de sa source
 * de donnees (S3), pas par {@link #add(List)}.
 */
public class ManagedKnowledgeBaseVectorStore implements VectorStore {

    /** Cle de metadonnee qui porte l'URI du document source dans les {@link Document} renvoyes. */
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
     * Traduit une expression de filtre Spring AI en filtre Knowledge Base.
     * Operateurs supportes : {@code ==}, {@code !=}, {@code in}, {@code &&}, {@code ||}.
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
            case NE -> RetrievalFilter.fromNotEquals(attribute(expression));
            case IN -> RetrievalFilter.fromIn(attribute(expression));
            default -> throw new IllegalArgumentException(
                    "Operateur de filtre non supporte par la Knowledge Base managee : " + expression.type());
        };
    }

    private static Filter.Expression asExpression(Filter.Operand operand) {
        if (operand instanceof Filter.Expression e) {
            return e;
        }
        if (operand instanceof Filter.Group g) {
            return g.content();
        }
        throw new IllegalArgumentException("Operande de filtre inattendue : " + operand);
    }

    private static FilterAttribute attribute(Filter.Expression expression) {
        String key = ((Filter.Key) expression.left()).key();
        Object value = ((Filter.Value) expression.right()).value();
        return FilterAttribute.builder().key(key).value(toSdkDocument(value)).build();
    }

    private static software.amazon.awssdk.core.document.Document toSdkDocument(Object value) {
        if (value instanceof List<?> list) {
            return software.amazon.awssdk.core.document.Document.fromList(
                    list.stream().map(ManagedKnowledgeBaseVectorStore::toSdkDocument).toList());
        }
        if (value instanceof Number n) {
            return software.amazon.awssdk.core.document.Document.fromNumber(n.toString());
        }
        if (value instanceof Boolean b) {
            return software.amazon.awssdk.core.document.Document.fromBoolean(b);
        }
        return software.amazon.awssdk.core.document.Document.fromString(String.valueOf(value));
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
                "Knowledge Base managee : alimenter la source de donnees S3 puis lancer une synchronisation");
    }

    @Override
    public void delete(List<String> idList) {
        throw new UnsupportedOperationException("Suppression via la source de donnees S3 uniquement");
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException("Suppression via la source de donnees S3 uniquement");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getNativeClient() {
        return Optional.of((T) client);
    }
}
