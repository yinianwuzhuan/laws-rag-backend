package org.example.lawsrag.service;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import org.example.lawsrag.util.DocumentIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QdrantContentHashIndexTest {

    @Test
    void sameContentIsDuplicateOnlyWithinSameSource() {
        QdrantPointStore pointStore = mock(QdrantPointStore.class);
        String hash = DocumentIdentity.contentHash("完全相同的法条正文");
        when(pointStore.scrollAllPayloads()).thenReturn(List.of(point("法律甲", hash)));
        when(pointStore.collectionName()).thenReturn("laws");
        QdrantContentHashIndex index = new QdrantContentHashIndex(pointStore);

        index.refresh();

        assertFalse(index.tryReserve(DocumentIdentity.contentDedupKey("法律甲", hash)));
        assertTrue(index.tryReserve(DocumentIdentity.contentDedupKey("法律乙", hash)));
    }

    @Test
    void refreshesMetadataOnlyWhenTheSameBusinessPointReallyExists() {
        QdrantPointStore pointStore = mock(QdrantPointStore.class);
        String hash = DocumentIdentity.contentHash("相同正文");
        when(pointStore.scrollAllPayloads()).thenReturn(List.of(
                point("法律甲", hash, "law_法律甲_1_0")));
        when(pointStore.collectionName()).thenReturn("laws");
        QdrantContentHashIndex index = new QdrantContentHashIndex(pointStore);
        index.refresh();

        assertTrue(index.requiresMetadataRefresh(document("law_法律甲_1_0", hash)));
        assertFalse(index.requiresMetadataRefresh(document("law_法律甲_1-variant-1_0", hash)));
    }

    private QdrantPointStore.StoredPoint point(String sourceName, String hash) {
        return point(sourceName, hash, null);
    }

    private QdrantPointStore.StoredPoint point(String sourceName, String hash, String businessId) {
        Map<String, JsonWithInt.Value> payload = new LinkedHashMap<>();
        payload.put("source_name", ValueFactory.value(sourceName));
        payload.put(DocumentIdentity.CONTENT_HASH_FIELD, ValueFactory.value(hash));
        if (businessId != null) payload.put("business_id", ValueFactory.value(businessId));
        return new QdrantPointStore.StoredPoint(
                Common.PointId.newBuilder()
                        .setUuid("00000000-0000-0000-0000-000000000001")
                        .build(),
                payload);
    }

    private Document document(String businessId, String hash) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("business_id", businessId);
        metadata.put("content_hash", hash);
        metadata.put("official_id", "official-1");
        metadata.put("parser_version", "official-markdown-v2");
        return new Document(DocumentIdentity.pointId(businessId), "相同正文", metadata);
    }
}
