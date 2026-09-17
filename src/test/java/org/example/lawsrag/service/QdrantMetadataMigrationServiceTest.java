package org.example.lawsrag.service;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class QdrantMetadataMigrationServiceTest {

    @Test
    void plansCanonicalPayloadBackfillAndExactDuplicateRemoval() {
        var service = new QdrantMetadataMigrationService(
                mock(QdrantPointStore.class), mock(QdrantContentHashIndex.class));
        var first = storedPoint("00000000-0000-0000-0000-000000000001", validLegacyPayload());
        var duplicate = storedPoint("00000000-0000-0000-0000-000000000002", validLegacyPayload());
        var invalid = storedPoint("00000000-0000-0000-0000-000000000003",
                Map.of("source_type", ValueFactory.value("law")));

        var plan = service.plan(List.of(first, duplicate, invalid));

        assertEquals(3, plan.totalPoints());
        assertEquals(2, plan.validPoints());
        assertEquals(1, plan.invalidPoints());
        assertEquals(2, plan.businessIdChanges());
        assertEquals(2, plan.contentHashBackfills());
        assertEquals(1, plan.duplicateGroups());
        assertEquals(1, plan.payloadUpdates().size());
        assertEquals("law_中华人民共和国民法典_1047_0",
                plan.payloadUpdates().getFirst().businessId());
        assertEquals(1, plan.deletePointIds().size());
    }

    private QdrantPointStore.StoredPoint storedPoint(
            String id, Map<String, JsonWithInt.Value> payload) {
        return new QdrantPointStore.StoredPoint(
                Common.PointId.newBuilder().setUuid(id).build(), payload);
    }

    private Map<String, JsonWithInt.Value> validLegacyPayload() {
        Map<String, JsonWithInt.Value> payload = new LinkedHashMap<>();
        payload.put("source_type", ValueFactory.value("law"));
        payload.put("source_name", ValueFactory.value("中华人民共和国民法典"));
        payload.put("article_no_arabic", ValueFactory.value("1047"));
        payload.put("chunk_index", ValueFactory.value(0L));
        payload.put("doc_content", ValueFactory.value("结婚年龄，男不得早于二十二周岁。"));
        payload.put("business_id", ValueFactory.value("law_marriage_family_1047_0"));
        return payload;
    }
}
