package org.example.lawsrag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.enums.SourceType;
import org.example.lawsrag.util.DocumentIdentity;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 法律文档入库服务
 * 解析法律 Markdown 文件并写入向量数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LawDocumentService {

    private final QdrantHybridStore hybridStore;
    private final QdrantContentHashIndex contentHashIndex;

    /**
     * 从上传的文件流入库
     *
     * @param inputStream 文件输入流
     * @param fileName    原始文件名，仅用于日志记录
     * @param sourceType  来源类型枚举
     * @param sourceName  来源名称（用户填写），如 "中华人民共和国民法典"
     * @return 入库的文档数量
     */
    public int ingestFromStream(InputStream inputStream, String fileName,
                                SourceType sourceType, String sourceName) throws IOException {
        log.info("文件: {}, sourceType: {}, sourceName: {}",
                fileName, sourceType.getCode(), sourceName);

        LawMarkdownParser parser = new LawMarkdownParser(sourceType, sourceName);
        List<Document> documents = parser.parse(inputStream);
        return doIngest(documents);
    }

    /**
     * 从本地文件路径入库
     */
    public int ingestFromFile(Path filePath, SourceType sourceType,
                              String sourceName) throws IOException {
        LawMarkdownParser parser = new LawMarkdownParser(sourceType, sourceName);
        List<Document> documents = parser.parse(filePath);
        return doIngest(documents);
    }

    /** 兼容旧调用方；subdomain 已不再参与业务 ID。 */
    @Deprecated
    public int ingestFromFile(Path filePath, SourceType sourceType,
                              String sourceName, String subdomain) throws IOException {
        return ingestFromFile(filePath, sourceType, sourceName);
    }

    /**
     * 仅解析不入库（流式），用于预览验证
     */
    public List<Document> parseOnly(InputStream inputStream, String fileName,
                                    SourceType sourceType, String sourceName) throws IOException {
        LawMarkdownParser parser = new LawMarkdownParser(sourceType, sourceName);
        return parser.parse(inputStream);
    }

    /**
     * 仅解析不入库，用于预览验证
     */
    public List<Document> parseOnly(Path filePath, SourceType sourceType,
                                    String sourceName) throws IOException {
        LawMarkdownParser parser = new LawMarkdownParser(sourceType, sourceName);
        return parser.parse(filePath);
    }

    /**
     * 写入已经完成解析和跨文件校验的文档，供可信本地语料目录批量导入使用。
     */
    public int ingestDocuments(List<Document> documents) {
        return doIngest(List.copyOf(documents));
    }

    /** 兼容旧调用方；subdomain 已不再参与业务 ID。 */
    @Deprecated
    public List<Document> parseOnly(Path filePath, SourceType sourceType,
                                    String sourceName, String subdomain) throws IOException {
        return parseOnly(filePath, sourceType, sourceName);
    }

    private int doIngest(List<Document> documents) {
        log.info("解析完成，共 {} 条法条，开始去重过滤", documents.size());

        // content_hash 已持久化在Qdrant；内存索引负责O(1)判断与并发预留。
        List<Document> newDocuments = new ArrayList<>();
        List<Document> metadataRefreshes = new ArrayList<>();
        for (Document document : documents) {
            if (contentHashIndex.tryReserve(dedupKey(document))) {
                newDocuments.add(document);
            } else if (contentHashIndex.requiresMetadataRefresh(document)) {
                metadataRefreshes.add(document);
            }
        }

        if (!metadataRefreshes.isEmpty()) {
            hybridStore.refreshPayloads(metadataRefreshes);
            contentHashIndex.confirmMetadataRefreshed(metadataRefreshes);
        }

        int skipped = documents.size() - newDocuments.size();
        if (skipped > 0) {
            log.info("去重过滤：跳过 {} 条已入库 chunk，实际入库 {} 条", skipped, newDocuments.size());
        }
        if (newDocuments.isEmpty()) {
            log.info("全部 chunk 均已入库，无需重复写入");
            return 0;
        }

        log.info("准备写入向量数据库，共 {} 条", newDocuments.size());

        int batchSize = 20;
        int total = newDocuments.size();
        int batchCount = (total + batchSize - 1) / batchSize;

        // 分批
        List<List<Document>> batches = new ArrayList<>(batchCount);
        for (int i = 0; i < total; i += batchSize) {
            batches.add(newDocuments.subList(i, Math.min(i + batchSize, total)));
        }

        // 并发度：受 Embedding API 速率限制，并发太高会被限流，取合理值
        int concurrency = Math.min(batchCount, 5);
        Semaphore semaphore = new Semaphore(concurrency);
        AtomicInteger completed = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>(batchCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<Document> batch : batches) {
                futures.add(executor.submit(() -> {
                    semaphore.acquire();
                    List<String> dedupKeys = batch.stream().map(this::dedupKey).toList();
                    try {
                        hybridStore.addDocuments(batch);
                        contentHashIndex.confirmPersisted(dedupKeys);
                        contentHashIndex.confirmMetadataRefreshed(batch);
                        int done = completed.addAndGet(batch.size());
                        log.info("已写入 {}/{} 条", done, total);
                    } catch (RuntimeException e) {
                        contentHashIndex.releaseReservations(dedupKeys);
                        throw e;
                    } finally {
                        semaphore.release();
                    }
                    return null;
                }));
            }

            // 等待全部完成，收集异常
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            throw new RuntimeException("入库失败: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("入库被中断", e);
        }

        log.info("全部写入完成，共 {} 条（本次跳过 {} 条重复）", newDocuments.size(), skipped);
        return newDocuments.size();
    }

    private String contentHash(Document document) {
        Object existing = document.getMetadata().get(DocumentIdentity.CONTENT_HASH_FIELD);
        return existing instanceof String hash && !hash.isBlank()
                ? hash
                : DocumentIdentity.contentHash(document.getText());
    }

    private String dedupKey(Document document) {
        Object sourceName = document.getMetadata().get("source_name");
        return DocumentIdentity.contentDedupKey(
                sourceName == null ? null : String.valueOf(sourceName),
                contentHash(document));
    }

}

