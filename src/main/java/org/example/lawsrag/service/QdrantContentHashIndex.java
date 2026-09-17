package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.util.DocumentIdentity;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.example.lawsrag.service.QdrantPointStore.payloadString;

/**
 * Qdrant source_name + content_hash 的内存索引。
 * Qdrant是持久化真相源，内存集合只负责O(1)去重判断和并发写入预留。
 */
@Slf4j
@Component
public class QdrantContentHashIndex {

    private final QdrantPointStore pointStore;
    private final Set<String> persistedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> reservedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> persistedBusinessIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> parserVersionsByBusinessId = new ConcurrentHashMap<>();
    private volatile boolean initialized;

    public QdrantContentHashIndex(QdrantPointStore pointStore) {
        this.pointStore = pointStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        refresh();
    }

    public synchronized void refresh() {
        Set<String> loaded = ConcurrentHashMap.newKeySet();
        Map<String, String> loadedVersions = new ConcurrentHashMap<>();
        Set<String> loadedBusinessIds = ConcurrentHashMap.newKeySet();
        int computedFromLegacyContent = 0;
        for (QdrantPointStore.StoredPoint point : pointStore.scrollAllPayloads()) {
            String sourceName = payloadString(point.payload(), "source_name");
            if (sourceName == null || sourceName.isBlank()) continue;
            String hash = payloadString(point.payload(), DocumentIdentity.CONTENT_HASH_FIELD);
            if (hash == null || hash.isBlank()) {
                String content = payloadString(point.payload(), "doc_content");
                if (content == null) continue;
                hash = DocumentIdentity.contentHash(content);
                computedFromLegacyContent++;
            }
            loaded.add(DocumentIdentity.contentDedupKey(sourceName, hash));
            String businessId = payloadString(point.payload(), DocumentIdentity.BUSINESS_ID_FIELD);
            String parserVersion = payloadString(point.payload(), "parser_version");
            if (businessId != null && !businessId.isBlank()) {
                loadedBusinessIds.add(businessId);
                if (parserVersion != null) loadedVersions.put(businessId, parserVersion);
            }
        }
        persistedKeys.clear();
        persistedKeys.addAll(loaded);
        parserVersionsByBusinessId.clear();
        parserVersionsByBusinessId.putAll(loadedVersions);
        persistedBusinessIds.clear();
        persistedBusinessIds.addAll(loadedBusinessIds);
        reservedKeys.clear();
        initialized = true;
        log.info("[Dedup] 从Qdrant集合 {} 加载 {} 个唯一(source_name, content_hash)，{} 条存量记录尚未持久化content_hash",
                pointStore.collectionName(), loaded.size(), computedFromLegacyContent);
    }

    /**
     * 原子预留哈希，避免两个并发上传在写入完成前同时通过去重检查。
     */
    public boolean tryReserve(String dedupKey) {
        ensureInitialized();
        if (persistedKeys.contains(dedupKey)) return false;
        return reservedKeys.add(dedupKey);
    }

    public void confirmPersisted(Collection<String> dedupKeys) {
        persistedKeys.addAll(dedupKeys);
        reservedKeys.removeAll(dedupKeys);
    }

    public void releaseReservations(Collection<String> dedupKeys) {
        reservedKeys.removeAll(dedupKeys);
    }

    public boolean requiresMetadataRefresh(org.springframework.ai.document.Document document) {
        String officialId = String.valueOf(document.getMetadata().getOrDefault("official_id", ""));
        if (officialId.isBlank()) return false;
        String businessId = String.valueOf(document.getMetadata().get(DocumentIdentity.BUSINESS_ID_FIELD));
        if (!persistedBusinessIds.contains(businessId)) return false;
        String parserVersion = String.valueOf(document.getMetadata().getOrDefault("parser_version", ""));
        return !parserVersion.isBlank() && !parserVersion.equals(parserVersionsByBusinessId.get(businessId));
    }

    public void confirmMetadataRefreshed(Collection<org.springframework.ai.document.Document> documents) {
        for (org.springframework.ai.document.Document document : documents) {
            String businessId = String.valueOf(document.getMetadata().get(DocumentIdentity.BUSINESS_ID_FIELD));
            String parserVersion = String.valueOf(document.getMetadata().getOrDefault("parser_version", ""));
            if (!businessId.isBlank()) {
                persistedBusinessIds.add(businessId);
                if (!parserVersion.isBlank()) parserVersionsByBusinessId.put(businessId, parserVersion);
            }
        }
    }

    public int size() {
        return persistedKeys.size();
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Qdrant去重索引尚未初始化，暂不能执行文档入库");
        }
    }
}
