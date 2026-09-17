package org.example.lawsrag.service;

import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.util.DocumentIdentity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.example.lawsrag.service.QdrantPointStore.payloadInt;
import static org.example.lawsrag.service.QdrantPointStore.payloadString;

/**
 * 一次性存量迁移：统一business_id、回填content_hash并清理完全重复的Point。
 */
@Slf4j
@Service
public class QdrantMetadataMigrationService {

    private final QdrantPointStore pointStore;
    private final QdrantContentHashIndex contentHashIndex;

    public QdrantMetadataMigrationService(QdrantPointStore pointStore,
                                          QdrantContentHashIndex contentHashIndex) {
        this.pointStore = pointStore;
        this.contentHashIndex = contentHashIndex;
    }

    public MigrationReport migrate(boolean apply) {
        MigrationPlan plan = plan(pointStore.scrollAllPayloads());
        log.info("[Qdrant迁移] dryRun={}, 总数={}, 有效={}, 无效={}, business_id变更={}, hash回填={}, "
                        + "重复组={}, 待删除重复Point={}",
                !apply, plan.totalPoints(), plan.validPoints(), plan.invalidPoints(),
                plan.businessIdChanges(), plan.contentHashBackfills(),
                plan.duplicateGroups(), plan.deletePointIds().size());

        if (apply) {
            pointStore.updatePayloads(plan.payloadUpdates());
            pointStore.deletePoints(plan.deletePointIds());
            contentHashIndex.refresh();
            log.info("[Qdrant迁移] 执行完成：更新 {} 个Point，删除 {} 个重复Point",
                    plan.payloadUpdates().size(), plan.deletePointIds().size());
        }

        return new MigrationReport(
                apply,
                plan.totalPoints(),
                plan.validPoints(),
                plan.invalidPoints(),
                plan.businessIdChanges(),
                plan.contentHashBackfills(),
                plan.duplicateGroups(),
                plan.payloadUpdates().size(),
                plan.deletePointIds().size()
        );
    }

    MigrationPlan plan(List<QdrantPointStore.StoredPoint> points) {
        List<Candidate> candidates = new ArrayList<>();
        int invalid = 0;
        int businessIdChanges = 0;
        int contentHashBackfills = 0;

        for (QdrantPointStore.StoredPoint point : points) {
            String sourceType = payloadString(point.payload(), "source_type");
            String sourceName = payloadString(point.payload(), "source_name");
            String articleNo = payloadString(point.payload(), "article_no_arabic");
            String content = payloadString(point.payload(), "doc_content");
            int chunkIndex = payloadInt(point.payload(), "chunk_index", 0);
            if (isBlank(sourceType) || isBlank(sourceName) || isBlank(articleNo) || content == null) {
                invalid++;
                continue;
            }

            String businessId = DocumentIdentity.businessId(sourceType, sourceName, articleNo, chunkIndex);
            String contentHash = DocumentIdentity.contentHash(content);
            String oldBusinessId = payloadString(point.payload(), DocumentIdentity.BUSINESS_ID_FIELD);
            String oldContentHash = payloadString(point.payload(), DocumentIdentity.CONTENT_HASH_FIELD);
            boolean businessIdChanged = !businessId.equals(oldBusinessId);
            boolean contentHashMissing = isBlank(oldContentHash);
            if (businessIdChanged) businessIdChanges++;
            if (contentHashMissing) contentHashBackfills++;
            candidates.add(new Candidate(point.pointId(), businessId, contentHash,
                    businessIdChanged || !contentHash.equals(oldContentHash)));
        }

        Map<DuplicateKey, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            groups.computeIfAbsent(new DuplicateKey(candidate.businessId(), candidate.contentHash()),
                    ignored -> new ArrayList<>()).add(candidate);
        }

        List<QdrantPointStore.PayloadUpdate> updates = new ArrayList<>();
        List<Common.PointId> deletes = new ArrayList<>();
        int duplicateGroups = 0;
        Comparator<Candidate> stableOrder = Comparator.comparing(item -> item.pointId().toString());
        for (List<Candidate> group : groups.values()) {
            group.sort(stableOrder);
            Candidate keeper = group.get(0);
            if (keeper.needsUpdate()) {
                updates.add(new QdrantPointStore.PayloadUpdate(
                        keeper.pointId(), keeper.businessId(), keeper.contentHash()));
            }
            if (group.size() > 1) {
                duplicateGroups++;
                group.stream().skip(1).map(Candidate::pointId).forEach(deletes::add);
            }
        }

        return new MigrationPlan(
                points.size(), candidates.size(), invalid, businessIdChanges, contentHashBackfills,
                duplicateGroups, List.copyOf(updates), List.copyOf(deletes));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Candidate(Common.PointId pointId, String businessId,
                             String contentHash, boolean needsUpdate) {}

    private record DuplicateKey(String businessId, String contentHash) {}

    record MigrationPlan(
            int totalPoints,
            int validPoints,
            int invalidPoints,
            int businessIdChanges,
            int contentHashBackfills,
            int duplicateGroups,
            List<QdrantPointStore.PayloadUpdate> payloadUpdates,
            List<Common.PointId> deletePointIds
    ) {}

    public record MigrationReport(
            boolean applied,
            int totalPoints,
            int validPoints,
            int invalidPoints,
            int businessIdChanges,
            int contentHashBackfills,
            int duplicateGroups,
            int payloadUpdates,
            int duplicatePointsDeleted
    ) {}
}
