package org.example.lawsrag.service;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

/** 将旧Dense集合无损复制为Dense + BM25集合；源集合只读，不会删除或覆盖。 */
@Slf4j
@Service
public class QdrantHybridMigrationService {

    private static final int PAGE_SIZE = 64;
    private static final Duration RPC_TIMEOUT = Duration.ofMinutes(10);

    private final QdrantClient client;
    private final QdrantHybridStore hybridStore;
    private final String sourceCollection;

    public QdrantHybridMigrationService(
            QdrantClient client,
            QdrantHybridStore hybridStore,
            @Value("${spring.ai.vectorstore.qdrant.collection-name:laws}") String sourceCollection) {
        this.client = client;
        this.hybridStore = hybridStore;
        this.sourceCollection = sourceCollection;
    }

    public MigrationReport migrate() {
        if (sourceCollection.equals(hybridStore.collectionName())) {
            throw new IllegalStateException("混合检索目标集合不能与源集合同名");
        }
        boolean created = hybridStore.ensureCollection();
        Common.PointId offset = null;
        int scanned = 0;
        int migrated = 0;
        int skipped = 0;
        do {
            Points.ScrollPoints.Builder request = Points.ScrollPoints.newBuilder()
                    .setCollectionName(sourceCollection)
                    .setLimit(PAGE_SIZE)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(true).build());
            if (offset != null) request.setOffset(offset);
            Points.ScrollResponse response = await(client.scrollAsync(request.build(), RPC_TIMEOUT));
            List<Points.PointStruct> batch = new ArrayList<>();
            for (Points.RetrievedPoint point : response.getResultList()) {
                scanned++;
                Points.VectorOutput oldVector = unnamedDense(point.getVectors());
                List<Float> denseValues = denseValues(oldVector);
                String content = payloadString(point.getPayloadMap(), "doc_content");
                if (denseValues.isEmpty() || content == null) {
                    skipped++;
                    continue;
                }
                String searchable = Bm25TextBuilder.documentText(
                        valueOrEmpty(point.getPayloadMap(), "source_name"),
                        valueOrEmpty(point.getPayloadMap(), "article_no"),
                        valueOrEmpty(point.getPayloadMap(), "article_no_arabic"),
                        content);
                batch.add(Points.PointStruct.newBuilder()
                        .setId(point.getId())
                        .setVectors(namedVectors(Map.of(
                                QdrantHybridStore.DENSE_VECTOR, vector(denseValues),
                                QdrantHybridStore.BM25_VECTOR, vector(hybridStore.bm25Document(searchable))
                        )))
                        .putAllPayload(point.getPayloadMap())
                        .build());
            }
            if (!batch.isEmpty()) {
                await(client.upsertAsync(hybridStore.collectionName(), batch, RPC_TIMEOUT));
                migrated += batch.size();
                log.info("[HybridMigration] 已迁移 {}/{} 条", migrated, scanned);
            }
            offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
        } while (offset != null);

        MigrationReport report = new MigrationReport(
                sourceCollection, hybridStore.collectionName(), created, scanned, migrated, skipped);
        log.info("[HybridMigration] 完成: {}", report);
        return report;
    }

    private Points.VectorOutput unnamedDense(Points.VectorsOutput vectors) {
        if (vectors.hasVector()) return vectors.getVector();
        if (vectors.hasVectors()) {
            return vectors.getVectors().getVectorsMap().get("");
        }
        return null;
    }

    private List<Float> denseValues(Points.VectorOutput vector) {
        if (vector == null) return List.of();
        if (vector.hasDense()) return vector.getDense().getDataList();
        return vector.getDataList();
    }

    private String valueOrEmpty(Map<String, JsonWithInt.Value> payload, String key) {
        String value = payloadString(payload, key);
        return value == null ? "" : value;
    }

    private String payloadString(Map<String, JsonWithInt.Value> payload, String key) {
        JsonWithInt.Value value = payload.get(key);
        return value != null && value.getKindCase() == JsonWithInt.Value.KindCase.STRING_VALUE
                ? value.getStringValue() : null;
    }

    private <T> T await(ListenableFuture<T> future) {
        try {
            return future.get(RPC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant迁移被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Qdrant迁移失败: " + rootMessage(e), e);
        }
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record MigrationReport(
            String sourceCollection,
            String targetCollection,
            boolean targetCreated,
            int scanned,
            int migrated,
            int skipped
    ) {}
}
