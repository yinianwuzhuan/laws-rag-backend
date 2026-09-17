package org.example.lawsrag.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 项目直接访问Qdrant payload的最小封装，用于启动索引和一次性数据迁移。
 */
@Component
public class QdrantPointStore {

    private static final int SCROLL_PAGE_SIZE = 256;
    private static final int UPDATE_BATCH_SIZE = 100;
    private static final Duration RPC_TIMEOUT = Duration.ofMinutes(2);

    private final QdrantClient client;
    private final String collectionName;
    private final QdrantHybridStore hybridStore;

    public QdrantPointStore(QdrantClient client,
                            QdrantHybridStore hybridStore,
                            @Value("${laws.retrieval.hybrid.collection-name:laws_hybrid_v1}") String collectionName) {
        this.client = client;
        this.hybridStore = hybridStore;
        this.collectionName = collectionName;
    }

    public List<StoredPoint> scrollAllPayloads() {
        hybridStore.ensureCollection();
        List<StoredPoint> all = new ArrayList<>();
        Common.PointId offset = null;
        do {
            Points.ScrollPoints.Builder request = Points.ScrollPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setLimit(SCROLL_PAGE_SIZE)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false).build());
            if (offset != null) {
                request.setOffset(offset);
            }
            Points.ScrollResponse response = await(client.scrollAsync(request.build(), RPC_TIMEOUT));
            for (Points.RetrievedPoint point : response.getResultList()) {
                all.add(new StoredPoint(point.getId(), point.getPayloadMap()));
            }
            offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
        } while (offset != null);
        return List.copyOf(all);
    }

    public void updatePayloads(List<PayloadUpdate> updates) {
        for (int start = 0; start < updates.size(); start += UPDATE_BATCH_SIZE) {
            List<PayloadUpdate> batch = updates.subList(start, Math.min(start + UPDATE_BATCH_SIZE, updates.size()));
            List<Points.PointsUpdateOperation> operations = batch.stream()
                    .map(this::payloadOperation)
                    .toList();
            await(client.batchUpdateAsync(
                    collectionName,
                    operations,
                    true,
                    Points.WriteOrdering.newBuilder().setType(Points.WriteOrderingType.Strong).build(),
                    RPC_TIMEOUT
            ));
        }
    }

    public void deletePoints(List<Common.PointId> pointIds) {
        for (int start = 0; start < pointIds.size(); start += UPDATE_BATCH_SIZE) {
            List<Common.PointId> batch = pointIds.subList(start, Math.min(start + UPDATE_BATCH_SIZE, pointIds.size()));
            await(client.deleteAsync(collectionName, batch, RPC_TIMEOUT));
        }
    }

    public String collectionName() {
        return collectionName;
    }

    private Points.PointsUpdateOperation payloadOperation(PayloadUpdate update) {
        Points.PointsSelector selector = Points.PointsSelector.newBuilder()
                .setPoints(Points.PointsIdsList.newBuilder().addIds(update.pointId()).build())
                .build();
        Points.PointsUpdateOperation.SetPayload setPayload = Points.PointsUpdateOperation.SetPayload.newBuilder()
                .setPointsSelector(selector)
                .putPayload("business_id", ValueFactory.value(update.businessId()))
                .putPayload("content_hash", ValueFactory.value(update.contentHash()))
                .build();
        return Points.PointsUpdateOperation.newBuilder().setSetPayload(setPayload).build();
    }

    private <T> T await(com.google.common.util.concurrent.ListenableFuture<T> future) {
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

    public static String payloadString(Map<String, JsonWithInt.Value> payload, String key) {
        JsonWithInt.Value value = payload.get(key);
        if (value == null) return null;
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> String.valueOf(value.getIntegerValue());
            case DOUBLE_VALUE -> String.valueOf(value.getDoubleValue());
            case BOOL_VALUE -> String.valueOf(value.getBoolValue());
            default -> null;
        };
    }

    public static int payloadInt(Map<String, JsonWithInt.Value> payload, String key, int fallback) {
        JsonWithInt.Value value = payload.get(key);
        if (value == null) return fallback;
        return switch (value.getKindCase()) {
            case INTEGER_VALUE -> Math.toIntExact(value.getIntegerValue());
            case DOUBLE_VALUE -> (int) value.getDoubleValue();
            case STRING_VALUE -> parseInt(value.getStringValue(), fallback);
            default -> fallback;
        };
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record StoredPoint(
            Common.PointId pointId,
            Map<String, JsonWithInt.Value> payload
    ) {}

    public record PayloadUpdate(
            Common.PointId pointId,
            String businessId,
            String contentHash
    ) {}
}
