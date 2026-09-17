package org.example.lawsrag.service;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.fusion;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

/**
 * Qdrant原生Dense + BM25存储与检索。
 * Spring AI当前的VectorStore抽象只覆盖Dense检索，因此混合Query API直接使用官方Java Client。
 */
@Slf4j
@Component
public class QdrantHybridStore {

    public static final String DENSE_VECTOR = "dense";
    public static final String BM25_VECTOR = "bm25";
    private static final String BM25_MODEL = "qdrant/bm25";
    private static final Duration RPC_TIMEOUT = Duration.ofMinutes(5);

    private final QdrantClient client;
    private final TimedEmbeddingModel embeddingModel;
    private final String collectionName;
    private final int denseDimensions;
    private final int channelTopK;
    private volatile boolean payloadIndexesEnsured;

    public QdrantHybridStore(
            QdrantClient client,
            TimedEmbeddingModel embeddingModel,
            @Value("${laws.retrieval.hybrid.collection-name:laws_hybrid_v1}") String collectionName,
            @Value("${laws.retrieval.hybrid.dense-dimensions:3072}") int denseDimensions,
            @Value("${laws.retrieval.hybrid.channel-top-k:20}") int channelTopK) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.collectionName = collectionName;
        this.denseDimensions = denseDimensions;
        this.channelTopK = channelTopK;
    }

    public synchronized boolean ensureCollection() {
        boolean exists = await(client.collectionExistsAsync(collectionName, RPC_TIMEOUT));
        if (exists) {
            ensurePayloadIndexes();
            return false;
        }

        Collections.VectorParams dense = Collections.VectorParams.newBuilder()
                .setSize(denseDimensions)
                .setDistance(Collections.Distance.Cosine)
                .build();
        Collections.VectorsConfig vectors = Collections.VectorsConfig.newBuilder()
                .setParamsMap(Collections.VectorParamsMap.newBuilder()
                        .putMap(DENSE_VECTOR, dense)
                        .build())
                .build();
        Collections.SparseVectorConfig sparse = Collections.SparseVectorConfig.newBuilder()
                .putMap(BM25_VECTOR, Collections.SparseVectorParams.newBuilder()
                        .setModifier(Collections.Modifier.Idf)
                        .build())
                .build();
        Collections.CreateCollection request = Collections.CreateCollection.newBuilder()
                .setCollectionName(collectionName)
                .setVectorsConfig(vectors)
                .setSparseVectorsConfig(sparse)
                .setOnDiskPayload(true)
                .build();
        await(client.createCollectionAsync(request, RPC_TIMEOUT));
        log.info("[HybridStore] 已创建集合 {}, dense={}维, sparse=BM25/IDF", collectionName, denseDimensions);
        ensurePayloadIndexes();
        return true;
    }

    /** 高频过滤字段建立关键词索引；重复创建由Qdrant按同一字段配置幂等处理。 */
    private void ensurePayloadIndexes() {
        if (payloadIndexesEnsured) return;
        for (String field : List.of("source_type", "source_name", "document_type",
                "official_id", "official_category", "issuer", "status", "source_scope", "chunk_type")) {
            try {
                await(client.createPayloadIndexAsync(collectionName, field,
                        Collections.PayloadSchemaType.Keyword, null, true, null, RPC_TIMEOUT));
            } catch (RuntimeException exception) {
                log.warn("[HybridStore] payload索引创建失败，field={}, reason={}", field, exception.getMessage());
            }
        }
        payloadIndexesEnsured = true;
    }

    public void addDocuments(List<Document> documents) {
        ensureCollection();
        if (documents == null || documents.isEmpty()) return;
        List<float[]> denseVectors = embeddingModel.embed(
                documents.stream().map(Document::getText).toList());
        if (denseVectors.size() != documents.size()) {
            throw new IllegalStateException("Embedding批量响应数量不匹配: expected="
                    + documents.size() + ", actual=" + denseVectors.size());
        }
        List<Points.PointStruct> points = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            points.add(toPoint(documents.get(i), denseVectors.get(i)));
        }
        await(client.upsertAsync(collectionName, points, RPC_TIMEOUT));
    }

    /** 只更新已有Point的payload，不重复调用Embedding，也不改动Dense/BM25向量。 */
    public void refreshPayloads(List<Document> documents) {
        ensureCollection();
        if (documents == null || documents.isEmpty()) return;
        for (Document document : documents) {
            await(client.setPayloadAsync(collectionName, payload(document),
                    id(UUID.fromString(document.getId())), true, null, RPC_TIMEOUT));
        }
        log.info("[HybridStore] 已刷新 {} 个存量Point的官方元数据", documents.size());
    }

    public RetrievalChannels search(String query, int limit,
                                    Map<String, String> filters, RetrievalMode mode) {
        return search(query, query, limit, filters, mode);
    }

    public RetrievalChannels search(String query, String enhancedBm25Query, int limit,
                                    Map<String, String> filters, RetrievalMode mode) {
        ensureCollection();
        Common.Filter filter = buildFilter(filters);
        return switch (mode) {
            case DENSE -> {
                List<Map<String, Object>> dense = denseSearch(query, limit, filter);
                yield new RetrievalChannels(dense, List.of(), List.of(), dense);
            }
            case BM25 -> {
                List<Map<String, Object>> bm25 = bm25Search(enhancedBm25Query, limit, filter);
                yield new RetrievalChannels(List.of(), bm25, List.of(), bm25);
            }
            case HYBRID -> hybridSearch(query, enhancedBm25Query, limit, filter);
        };
    }

    /**
     * 原问题与重写问题共同检索。最终候选只由一次Qdrant RRF产生：
     * Dense/BM25模式为两路Prefetch，Hybrid模式为四路Prefetch。
     * 四组原始排名仅用于学习页面展示，不参与Java端二次融合。
     */
    public RewrittenRetrievalChannels searchWithRewrite(
            String originalQuery, String rewrittenQuery, int limit,
            Map<String, String> filters, RetrievalMode mode) {
        return searchWithRewrite(
                originalQuery, rewrittenQuery, rewrittenQuery, limit, filters, mode);
    }

    public RewrittenRetrievalChannels searchWithRewrite(
            String originalQuery, String rewrittenQuery, String enhancedBm25Query, int limit,
            Map<String, String> filters, RetrievalMode mode) {
        ensureCollection();
        Common.Filter filter = buildFilter(filters);
        int prefetchLimit = Math.max(limit, channelTopK);

        return switch (mode) {
            case DENSE -> {
                List<float[]> embeddings = embedBatch(originalQuery, rewrittenQuery);
                Points.Query originalDenseQuery = nearest(floatList(embeddings.get(0)));
                Points.Query rewrittenDenseQuery = nearest(floatList(embeddings.get(1)));
                List<Map<String, Object>> fused = fuse(
                        List.of(
                                prefetch(originalDenseQuery, DENSE_VECTOR, prefetchLimit, filter),
                                prefetch(rewrittenDenseQuery, DENSE_VECTOR, prefetchLimit, filter)),
                        limit, filter);
                yield new RewrittenRetrievalChannels(
                        queryWithVector(originalDenseQuery, DENSE_VECTOR, prefetchLimit, filter,
                                "original_dense"),
                        List.of(),
                        queryWithVector(rewrittenDenseQuery, DENSE_VECTOR, prefetchLimit, filter,
                                "rewritten_dense"),
                        List.of(),
                        fused);
            }
            case BM25 -> {
                Points.Query originalBm25Query = nearest(
                        bm25Document(Bm25TextBuilder.queryText(originalQuery)));
                Points.Query rewrittenBm25Query = nearest(
                        bm25Document(Bm25TextBuilder.queryText(enhancedBm25Query)));
                List<Map<String, Object>> fused = fuse(
                        List.of(
                                prefetch(originalBm25Query, BM25_VECTOR, prefetchLimit, filter),
                                prefetch(rewrittenBm25Query, BM25_VECTOR, prefetchLimit, filter)),
                        limit, filter);
                yield new RewrittenRetrievalChannels(
                        List.of(),
                        queryWithVector(originalBm25Query, BM25_VECTOR, prefetchLimit, filter,
                                "original_bm25"),
                        List.of(),
                        queryWithVector(rewrittenBm25Query, BM25_VECTOR, prefetchLimit, filter,
                                "rewritten_bm25"),
                        fused);
            }
            case HYBRID -> {
                List<float[]> embeddings = embedBatch(originalQuery, rewrittenQuery);
                Points.Query originalDenseQuery = nearest(floatList(embeddings.get(0)));
                Points.Query rewrittenDenseQuery = nearest(floatList(embeddings.get(1)));
                Points.Query originalBm25Query = nearest(
                        bm25Document(Bm25TextBuilder.queryText(originalQuery)));
                Points.Query rewrittenBm25Query = nearest(
                        bm25Document(Bm25TextBuilder.queryText(enhancedBm25Query)));
                List<Map<String, Object>> fused = fuse(
                        List.of(
                                prefetch(originalDenseQuery, DENSE_VECTOR, prefetchLimit, filter),
                                prefetch(originalBm25Query, BM25_VECTOR, prefetchLimit, filter),
                                prefetch(rewrittenDenseQuery, DENSE_VECTOR, prefetchLimit, filter),
                                prefetch(rewrittenBm25Query, BM25_VECTOR, prefetchLimit, filter)),
                        limit, filter);
                yield new RewrittenRetrievalChannels(
                        queryWithVector(originalDenseQuery, DENSE_VECTOR, prefetchLimit, filter,
                                "original_dense"),
                        queryWithVector(originalBm25Query, BM25_VECTOR, prefetchLimit, filter,
                                "original_bm25"),
                        queryWithVector(rewrittenDenseQuery, DENSE_VECTOR, prefetchLimit, filter,
                                "rewritten_dense"),
                        queryWithVector(rewrittenBm25Query, BM25_VECTOR, prefetchLimit, filter,
                                "rewritten_bm25"),
                        fused);
            }
        };
    }

    private RetrievalChannels hybridSearch(
            String query, String enhancedBm25Query, int limit, Common.Filter filter) {
        float[] denseVector = embed(query);
        int prefetchLimit = Math.max(limit, channelTopK);
        Points.PrefetchQuery densePrefetch = prefetch(
                nearest(floatList(denseVector)), DENSE_VECTOR, prefetchLimit, filter);
        Points.PrefetchQuery bm25Prefetch = prefetch(
                nearest(bm25Document(Bm25TextBuilder.queryText(enhancedBm25Query))),
                BM25_VECTOR, prefetchLimit, filter);

        List<Map<String, Object>> fused = fuse(
                List.of(densePrefetch, bm25Prefetch), limit, filter);

        // 学习/评测页面需要观察两条召回通道，因此额外保留各自的原始排名。
        List<Map<String, Object>> dense = queryWithVector(
                nearest(floatList(denseVector)), DENSE_VECTOR, prefetchLimit, filter, "dense");
        List<Map<String, Object>> bm25 = queryWithVector(
                nearest(bm25Document(Bm25TextBuilder.queryText(enhancedBm25Query))),
                BM25_VECTOR, prefetchLimit, filter, "bm25");
        return new RetrievalChannels(dense, bm25, fused, fused);
    }

    private List<Map<String, Object>> fuse(
            List<Points.PrefetchQuery> prefetches, int limit, Common.Filter filter) {
        Points.QueryPoints.Builder request = baseQuery(limit, filter)
                .setQuery(fusion(Points.Fusion.RRF));
        prefetches.forEach(request::addPrefetch);
        return mapResults(await(client.queryAsync(request.build(), RPC_TIMEOUT)), "fusion");
    }

    private List<Map<String, Object>> denseSearch(String query, int limit, Common.Filter filter) {
        return queryWithVector(nearest(floatList(embed(query))), DENSE_VECTOR, limit, filter, "dense");
    }

    private List<Map<String, Object>> bm25Search(String query, int limit, Common.Filter filter) {
        return queryWithVector(nearest(bm25Document(Bm25TextBuilder.queryText(query))), BM25_VECTOR, limit, filter, "bm25");
    }

    private List<Map<String, Object>> queryWithVector(
            Points.Query query, String using, int limit, Common.Filter filter, String scoreField) {
        Points.QueryPoints.Builder request = baseQuery(limit, filter)
                .setQuery(query)
                .setUsing(using);
        return mapResults(await(client.queryAsync(request.build(), RPC_TIMEOUT)), scoreField);
    }

    private Points.QueryPoints.Builder baseQuery(int limit, Common.Filter filter) {
        Points.QueryPoints.Builder request = Points.QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(limit)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false).build());
        if (filter != null) request.setFilter(filter);
        return request;
    }

    private Points.PrefetchQuery prefetch(
            Points.Query query, String using, int limit, Common.Filter filter) {
        Points.PrefetchQuery.Builder request = Points.PrefetchQuery.newBuilder()
                .setQuery(query)
                .setUsing(using)
                .setLimit(limit);
        if (filter != null) request.setFilter(filter);
        return request.build();
    }

    private Points.PointStruct toPoint(Document document, float[] dense) {
        Map<String, Points.Vector> vectors = Map.of(
                DENSE_VECTOR, vector(dense),
                BM25_VECTOR, vector(bm25Document(Bm25TextBuilder.documentText(document)))
        );
        return Points.PointStruct.newBuilder()
                .setId(id(UUID.fromString(document.getId())))
                .setVectors(namedVectors(vectors))
                .putAllPayload(payload(document))
                .build();
    }

    private Map<String, JsonWithInt.Value> payload(Document document) {
        Map<String, JsonWithInt.Value> payload = new LinkedHashMap<>();
        payload.put("doc_content", ValueFactory.value(document.getText()));
        document.getMetadata().forEach((key, value) -> {
            JsonWithInt.Value mapped = payloadValue(value);
            if (mapped != null) payload.put(key, mapped);
        });
        return payload;
    }

    private JsonWithInt.Value payloadValue(Object value) {
        if (value == null) return null;
        if (value instanceof String string) return ValueFactory.value(string);
        if (value instanceof Integer integer) return ValueFactory.value(integer.longValue());
        if (value instanceof Long number) return ValueFactory.value(number);
        if (value instanceof Float number) return ValueFactory.value(number.doubleValue());
        if (value instanceof Double number) return ValueFactory.value(number);
        if (value instanceof Boolean bool) return ValueFactory.value(bool);
        return ValueFactory.value(String.valueOf(value));
    }

    public Points.Document bm25Document(String text) {
        return Points.Document.newBuilder()
                .setText(text)
                .setModel(BM25_MODEL)
                .putOptions("tokenizer", ValueFactory.value("multilingual"))
                .build();
    }

    private float[] embed(String query) {
        return embeddingModel.embed(query);
    }

    private List<float[]> embedBatch(String originalQuery, String rewrittenQuery) {
        List<float[]> embeddings = embeddingModel.embed(List.of(originalQuery, rewrittenQuery));
        if (embeddings.size() != 2) {
            throw new IllegalStateException("Embedding批量响应数量不匹配: expected=2, actual="
                    + embeddings.size());
        }
        return embeddings;
    }

    private Common.Filter buildFilter(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) return null;
        Common.Filter.Builder filter = Common.Filter.newBuilder();
        filters.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            List<String> values = List.of(value.split(",")).stream()
                    .map(String::trim).filter(item -> !item.isEmpty()).toList();
            filter.addMust(values.size() > 1
                    ? matchKeywords(key, values)
                    : matchKeyword(key, values.getFirst()));
        });
        return filter.getMustCount() == 0 ? null : filter.build();
    }

    private List<Map<String, Object>> mapResults(
            List<Points.ScoredPoint> points, String scoreField) {
        List<Map<String, Object>> results = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Points.ScoredPoint point = points.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("score", (double) point.getScore());
            item.put(scoreField + "_rank", i + 1);
            item.put(scoreField + "_score", (double) point.getScore());
            point.getPayloadMap().forEach((key, value) -> item.put(key, javaValue(value)));
            item.put("text", item.getOrDefault("doc_content", ""));
            results.add(item);
        }
        return List.copyOf(results);
    }

    private Object javaValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case NULL_VALUE, KIND_NOT_SET -> null;
            default -> value.toString();
        };
    }

    private List<Float> floatList(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) result.add(value);
        return result;
    }

    public String collectionName() {
        return collectionName;
    }

    private <T> T await(ListenableFuture<T> future) {
        try {
            return future.get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant操作被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Qdrant操作失败: " + rootMessage(e), e);
        }
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record RetrievalChannels(
            List<Map<String, Object>> denseCandidates,
            List<Map<String, Object>> bm25Candidates,
            List<Map<String, Object>> fusedCandidates,
            List<Map<String, Object>> selectedCandidates
    ) {}

    public record RewrittenRetrievalChannels(
            List<Map<String, Object>> originalDenseCandidates,
            List<Map<String, Object>> originalBm25Candidates,
            List<Map<String, Object>> rewrittenDenseCandidates,
            List<Map<String, Object>> rewrittenBm25Candidates,
            List<Map<String, Object>> fusedCandidates
    ) {}
}
