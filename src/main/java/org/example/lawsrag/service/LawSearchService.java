package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.service.DocumentReranker.CandidateDocument;
import org.example.lawsrag.service.DocumentReranker.RerankedDocument;
import org.example.lawsrag.service.QdrantHybridStore.RetrievalChannels;
import org.example.lawsrag.service.QdrantHybridStore.RewrittenRetrievalChannels;
import org.example.lawsrag.service.RetrievalHintParser.RetrievalHintPlan;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LawSearchService {

    private final QdrantHybridStore hybridStore;
    private final DocumentReranker documentReranker;
    private final TimedEmbeddingModel timedEmbeddingModel;
    private final QueryRewriter queryRewriter;
    private final RetrievalHintParser retrievalHintParser;

    public LawSearchService(
            QdrantHybridStore hybridStore,
            DocumentReranker documentReranker,
            TimedEmbeddingModel timedEmbeddingModel,
            QueryRewriter queryRewriter,
            RetrievalHintParser retrievalHintParser) {
        this.hybridStore = hybridStore;
        this.documentReranker = documentReranker;
        this.timedEmbeddingModel = timedEmbeddingModel;
        this.queryRewriter = queryRewriter;
        this.retrievalHintParser = retrievalHintParser;
    }

    public List<Map<String, Object>> search(String query, int topK, Map<String, String> filters) {
        return search(query, topK, topK, filters, RetrievalMode.DENSE, false);
    }

    /** 保留旧调用签名，默认使用Dense召回。 */
    public List<Map<String, Object>> search(String query, int candidateTopK, int finalTopK,
                                            Map<String, String> filters, boolean rerankEnabled) {
        return search(query, candidateTopK, finalTopK, filters,
                RetrievalMode.DENSE, rerankEnabled);
    }

    public List<Map<String, Object>> search(String query, int candidateTopK, int finalTopK,
                                            Map<String, String> filters, RetrievalMode mode,
                                            boolean rerankEnabled) {
        return searchWithTrace(query, candidateTopK, finalTopK, filters, mode, rerankEnabled)
                .finalResults();
    }

    /** 保留旧调用签名，默认使用Dense召回。 */
    public SearchExecution searchWithTrace(String query, int candidateTopK, int finalTopK,
                                           Map<String, String> filters, boolean rerankEnabled) {
        return searchWithTrace(query, candidateTopK, finalTopK, filters,
                RetrievalMode.DENSE, rerankEnabled, false);
    }

    public SearchExecution searchWithTrace(String query, int candidateTopK, int finalTopK,
                                           Map<String, String> filters, RetrievalMode mode,
                                           boolean rerankEnabled) {
        return searchWithTrace(query, candidateTopK, finalTopK, filters,
                mode, rerankEnabled, false);
    }

    public SearchExecution searchWithTrace(String query, int candidateTopK, int finalTopK,
                                           Map<String, String> filters, RetrievalMode mode,
                                           boolean rerankEnabled, boolean queryRewriteEnabled) {
        return searchWithTrace(query, candidateTopK, finalTopK, filters, mode,
                rerankEnabled, queryRewriteEnabled, SearchProgressListener.noop());
    }

    public SearchExecution searchWithTrace(String query, int candidateTopK, int finalTopK,
                                           Map<String, String> filters, RetrievalMode mode,
                                           boolean rerankEnabled, boolean queryRewriteEnabled,
                                           SearchProgressListener progressListener) {
        return searchWithTrace(query, candidateTopK, finalTopK, filters, mode,
                rerankEnabled, queryRewriteEnabled, progressListener, "");
    }

    /**
     * 执行检索，并允许调用方仅向重排器追加整体问题等判别上下文。
     * 追加内容不会参与Dense/BM25召回，避免污染子问题自身的检索表达。
     */
    public SearchExecution searchWithTrace(String query, int candidateTopK, int finalTopK,
                                           Map<String, String> filters, RetrievalMode mode,
                                           boolean rerankEnabled, boolean queryRewriteEnabled,
                                           SearchProgressListener progressListener,
                                           String rerankerAdditionalContext) {
        long totalStartedAt = System.nanoTime();
        if (finalTopK < 1 || candidateTopK < 1) {
            throw new IllegalArgumentException("candidateTopK和finalTopK必须大于0");
        }
        if (rerankEnabled && candidateTopK < finalTopK) {
            throw new IllegalArgumentException("开启重排时candidateTopK不能小于finalTopK");
        }
        int retrievalTopK = rerankEnabled ? candidateTopK : finalTopK;
        progressListener.onQueryRewriteStarted(query, queryRewriteEnabled);
        QueryRewriter.RewriteResult rewrite = queryRewriteEnabled
                ? queryRewriter.rewrite(query)
                : QueryRewriter.RewriteResult.unchanged(query);
        progressListener.onQueryRewriteCompleted(rewrite);
        RetrievalHintPlan retrievalHints = retrievalHintParser.parse(
                query, rewrite.changed() ? rewrite.rewrittenQuery() : query);
        progressListener.onKeywordAnalysisCompleted(retrievalHints);
        log.info("搜索: query='{}', rewritten='{}', rewrite={}, mode={}, retrievalTopK={}, "
                        + "finalTopK={}, rerank={}, hints={}, enhancedBm25='{}', filters={}",
                query, rewrite.rewrittenQuery(), rewrite.changed(), mode, retrievalTopK,
                finalTopK, rerankEnabled, retrievalHints.hasHints(),
                retrievalHints.enhancedBm25Query(),
                filters == null || filters.isEmpty() ? "无" : filters);

        timedEmbeddingModel.clearLastCallTiming();
        progressListener.onHybridRetrievalStarted(mode, rewrite.changed());
        long retrievalStartedAt = System.nanoTime();
        RetrievalChannels originalChannels = rewrite.changed() ? null
                : hybridStore.search(query, retrievalHints.enhancedBm25Query(),
                        retrievalTopK, filters, mode);
        RewrittenRetrievalChannels rewrittenChannels = rewrite.changed()
                ? hybridStore.searchWithRewrite(
                        query, rewrite.rewrittenQuery(), retrievalHints.enhancedBm25Query(),
                        retrievalTopK, filters, mode)
                : null;
        long retrievalNanos = System.nanoTime() - retrievalStartedAt;
        long embeddingNanos = timedEmbeddingModel.consumeLastCallNanos();
        long qdrantNanos = Math.max(0L, retrievalNanos - embeddingNanos);

        List<Map<String, Object>> originalDenseCandidates;
        List<Map<String, Object>> originalBm25Candidates;
        List<Map<String, Object>> rewrittenDenseCandidates;
        List<Map<String, Object>> rewrittenBm25Candidates;
        List<Map<String, Object>> fusedCandidates;
        List<Map<String, Object>> retrievalCandidates;
        if (rewrittenChannels == null) {
            originalDenseCandidates = normalize(
                    originalChannels.denseCandidates(), RetrievalMode.DENSE);
            originalBm25Candidates = normalize(
                    originalChannels.bm25Candidates(), RetrievalMode.BM25);
            rewrittenDenseCandidates = List.of();
            rewrittenBm25Candidates = List.of();
            fusedCandidates = normalize(originalChannels.fusedCandidates(), RetrievalMode.HYBRID);
            retrievalCandidates = normalize(originalChannels.selectedCandidates(), mode);
        } else {
            originalDenseCandidates = normalize(
                    rewrittenChannels.originalDenseCandidates(), RetrievalMode.DENSE);
            originalBm25Candidates = normalize(
                    rewrittenChannels.originalBm25Candidates(), RetrievalMode.BM25);
            rewrittenDenseCandidates = normalize(
                    rewrittenChannels.rewrittenDenseCandidates(), RetrievalMode.DENSE);
            rewrittenBm25Candidates = normalize(
                    rewrittenChannels.rewrittenBm25Candidates(), RetrievalMode.BM25);
            retrievalCandidates = normalize(rewrittenChannels.fusedCandidates(), mode);
            fusedCandidates = retrievalCandidates;
        }

        int denseCandidateCount = originalDenseCandidates.size()
                + rewrittenDenseCandidates.size();
        int bm25CandidateCount = originalBm25Candidates.size()
                + rewrittenBm25Candidates.size();
        progressListener.onHybridRetrievalCompleted(new SearchChannelSummary(
                denseCandidateCount,
                bm25CandidateCount,
                retrievalCandidates.size(),
                elapsedMillis(embeddingNanos),
                elapsedMillis(qdrantNanos),
                elapsedMillis(retrievalNanos),
                rewrite.changed()));

        if (!rerankEnabled) {
            List<Map<String, Object>> finalResults = head(retrievalCandidates, finalTopK);
            long totalNanos = System.nanoTime() - totalStartedAt;
            logRetrievalTiming(query, mode, retrievalCandidates.size(), false,
                    embeddingNanos, qdrantNanos, retrievalNanos, 0L,
                    rewrite.durationMs(), totalNanos);
            return new SearchExecution(mode, rewrite, retrievalHints, originalDenseCandidates,
                    originalBm25Candidates, rewrittenDenseCandidates, rewrittenBm25Candidates,
                    fusedCandidates,
                    retrievalCandidates, List.of(), finalResults,
                    elapsedMillis(embeddingNanos), elapsedMillis(qdrantNanos),
                    elapsedMillis(retrievalNanos), 0, elapsedMillis(totalNanos));
        }
        if (!documentReranker.isAvailable()) {
            throw new IllegalStateException("请求开启重排，但Reranker模型未启用或未加载");
        }

        long rerankStartedAt = System.nanoTime();
        long candidatePreparationStartedAt = System.nanoTime();
        List<CandidateDocument> candidates = retrievalCandidates.stream()
                .map(item -> new CandidateDocument(
                        numberValue(item.get("retrieval_rank"), 0),
                        doubleValue(item.get("retrieval_score")),
                        stringValue(item.get("source_name")),
                        stringValue(item.get("article_no")),
                        stringValue(item.get("text")),
                        item))
                .toList();
        String rerankerQuery = rewrite.changed()
                ? "用户原始问题：" + query + "\n规范化法律问题：" + rewrite.rewrittenQuery()
                : query;
        if (retrievalHints.hasHints()) {
            rerankerQuery += "\n规则检索提示（仅作相关性参考）：\n"
                    + retrievalHints.rerankerContext();
        }
        if (rerankerAdditionalContext != null && !rerankerAdditionalContext.isBlank()) {
            rerankerQuery += "\n整体咨询背景（仅用于判断当前子问题相关性）：\n"
                    + rerankerAdditionalContext.trim();
        }
        long candidatePreparationNanos = System.nanoTime() - candidatePreparationStartedAt;

        long rerankerCallStartedAt = System.nanoTime();
        progressListener.onRerankStarted(candidates.size());
        List<RerankedDocument> reranked = documentReranker.rerank(
                rerankerQuery, candidates, finalTopK);
        long rerankerCallNanos = System.nanoTime() - rerankerCallStartedAt;

        long resultMappingStartedAt = System.nanoTime();
        List<Map<String, Object>> rerankedResults = reranked.stream().map(item -> {
            Map<String, Object> result = new LinkedHashMap<>(item.candidate().attributes());
            result.put("rank", item.rerankRank());
            result.put("rerank_rank", item.rerankRank());
            result.put("rerank_score", item.normalizedScore());
            result.put("rerank_raw_score", item.rawScore());
            return result;
        }).toList();
        List<Map<String, Object>> finalResults = head(rerankedResults, finalTopK);
        long resultMappingNanos = System.nanoTime() - resultMappingStartedAt;
        long rerankNanos = System.nanoTime() - rerankStartedAt;
        long totalNanos = System.nanoTime() - totalStartedAt;
        progressListener.onRerankCompleted(
                candidates.size(), finalResults.size(), elapsedMillis(rerankNanos));
        log.info("[RerankerPipelineTiming] query='{}', candidates={}, finalTopK={}, "
                        + "candidatePreparation={}ms, rerankerCall={}ms, resultMapping={}ms, total={}ms",
                query, candidates.size(), finalTopK,
                millis(candidatePreparationNanos), millis(rerankerCallNanos),
                millis(resultMappingNanos), millis(rerankNanos));
        logRetrievalTiming(query, mode, retrievalCandidates.size(), true,
                embeddingNanos, qdrantNanos, retrievalNanos, rerankNanos,
                rewrite.durationMs(), totalNanos);
        return new SearchExecution(mode, rewrite, retrievalHints, originalDenseCandidates,
                originalBm25Candidates, rewrittenDenseCandidates, rewrittenBm25Candidates,
                fusedCandidates,
                retrievalCandidates, rerankedResults, finalResults,
                elapsedMillis(embeddingNanos), elapsedMillis(qdrantNanos),
                elapsedMillis(retrievalNanos), elapsedMillis(rerankNanos),
                elapsedMillis(totalNanos));
    }

    private List<Map<String, Object>> normalize(
            List<Map<String, Object>> source, RetrievalMode mode) {
        return source.stream().map(raw -> {
            Map<String, Object> item = new LinkedHashMap<>(raw);
            int rank = numberValue(raw.get("rank"), 0);
            Double score = doubleValue(raw.get("score"));
            item.put("rank", rank);
            item.put("score", score);
            item.put("retrieval_rank", rank);
            item.put("retrieval_score", score);
            item.putIfAbsent("dense_rank", null);
            item.putIfAbsent("dense_score", null);
            item.putIfAbsent("bm25_rank", null);
            item.putIfAbsent("bm25_score", null);
            item.putIfAbsent("fusion_rank", null);
            item.putIfAbsent("fusion_score", null);
            // 兼容已有评测和页面字段。对混合/BM25而言它表示“重排前候选排名”。
            item.put("vector_rank", rank);
            item.put("vector_score", score);
            item.put("rerank_rank", null);
            item.put("rerank_score", null);
            item.put("rerank_raw_score", null);
            item.put("retrieval_mode", mode.name().toLowerCase());
            return item;
        }).toList();
    }

    private List<Map<String, Object>> head(List<Map<String, Object>> results, int limit) {
        return List.copyOf(results.subList(0, Math.min(limit, results.size())));
    }

    public boolean isRerankerAvailable() {
        return documentReranker.isAvailable();
    }

    public boolean isQueryRewriteAvailable() {
        return queryRewriter.isAvailable();
    }

    public List<Document> searchDocuments(String query, int candidateTopK, int finalTopK,
                                          Map<String, String> filters, boolean rerankEnabled) {
        return searchDocuments(query, candidateTopK, finalTopK, filters,
                RetrievalMode.DENSE, rerankEnabled);
    }

    public List<Document> searchDocuments(String query, int candidateTopK, int finalTopK,
                                          Map<String, String> filters, RetrievalMode mode,
                                          boolean rerankEnabled) {
        return searchDocuments(query, candidateTopK, finalTopK, filters, mode,
                rerankEnabled, false);
    }

    public List<Document> searchDocuments(String query, int candidateTopK, int finalTopK,
                                          Map<String, String> filters, RetrievalMode mode,
                                          boolean rerankEnabled, boolean queryRewriteEnabled) {
        return searchDocumentsWithTrace(query, candidateTopK, finalTopK, filters, mode,
                rerankEnabled, queryRewriteEnabled).documents();
    }

    public DocumentSearchExecution searchDocumentsWithTrace(
            String query, int candidateTopK, int finalTopK,
            Map<String, String> filters, RetrievalMode mode,
            boolean rerankEnabled, boolean queryRewriteEnabled) {
        return searchDocumentsWithTrace(query, candidateTopK, finalTopK, filters,
                mode, rerankEnabled, queryRewriteEnabled, SearchProgressListener.noop());
    }

    public DocumentSearchExecution searchDocumentsWithTrace(
            String query, int candidateTopK, int finalTopK,
            Map<String, String> filters, RetrievalMode mode,
            boolean rerankEnabled, boolean queryRewriteEnabled,
            SearchProgressListener progressListener) {
        return searchDocumentsWithTrace(query, candidateTopK, finalTopK, filters, mode,
                rerankEnabled, queryRewriteEnabled, progressListener, "");
    }

    public DocumentSearchExecution searchDocumentsWithTrace(
            String query, int candidateTopK, int finalTopK,
            Map<String, String> filters, RetrievalMode mode,
            boolean rerankEnabled, boolean queryRewriteEnabled,
            SearchProgressListener progressListener,
            String rerankerAdditionalContext) {
        SearchExecution execution = searchWithTrace(query, candidateTopK, finalTopK,
                filters, mode, rerankEnabled, queryRewriteEnabled, progressListener,
                rerankerAdditionalContext);
        List<Document> documents = execution.finalResults().stream()
                .map(item -> {
                    Map<String, Object> metadata = new LinkedHashMap<>(item);
                    metadata.remove("text");
                    metadata.remove("score");
                    // 检索结果 Map 同时服务于前端展示，其中未执行的阶段会用 null
                    // 表示（例如未开启重排时的 rerank_score）。Spring AI Document
                    // 明确禁止 metadata 包含 null，因此在领域对象边界统一移除占位字段。
                    metadata.entrySet().removeIf(entry -> entry.getValue() == null);
                    Double finalScore = rerankEnabled
                            ? doubleValue(item.get("rerank_score"))
                            : doubleValue(item.get("retrieval_score"));
                    return Document.builder()
                            .text(stringValue(item.get("text")))
                            .metadata(metadata)
                            .score(finalScore)
                            .build();
                }).toList();
        return new DocumentSearchExecution(execution, documents);
    }

    private int numberValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void logRetrievalTiming(String query, RetrievalMode mode, int candidateCount,
                                    boolean rerank, long embeddingNanos, long qdrantNanos,
                                    long retrievalNanos, long rerankNanos, long rewriteMs,
                                    long totalNanos) {
        log.info("[RetrievalTiming] query='{}', mode={}, candidates={}, rerank={}, "
                        + "queryRewrite={}ms, embeddingApi={}ms, qdrantSearch={}ms, retrievalTotal={}ms, "
                        + "rerankPipeline={}ms, total={}ms",
                query, mode, candidateCount, rerank,
                rewriteMs, millis(embeddingNanos), millis(qdrantNanos), millis(retrievalNanos),
                millis(rerankNanos), millis(totalNanos));
    }

    private double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private long elapsedMillis(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    public record SearchExecution(
            RetrievalMode retrievalMode,
            QueryRewriter.RewriteResult rewrite,
            RetrievalHintPlan retrievalHints,
            List<Map<String, Object>> originalDenseCandidates,
            List<Map<String, Object>> originalBm25Candidates,
            List<Map<String, Object>> rewrittenDenseCandidates,
            List<Map<String, Object>> rewrittenBm25Candidates,
            List<Map<String, Object>> fusedCandidates,
            List<Map<String, Object>> retrievalCandidates,
            List<Map<String, Object>> rerankedCandidates,
            List<Map<String, Object>> finalResults,
            long embeddingDurationMs,
            long qdrantDurationMs,
            long retrievalDurationMs,
            long rerankDurationMs,
            long totalDurationMs
    ) {
        public List<Map<String, Object>> vectorCandidates() {
            return originalDenseCandidates;
        }

        public List<Map<String, Object>> bm25Candidates() {
            return originalBm25Candidates;
        }

        /** 兼容已有Dense单元测试与调用代码。 */
        public SearchExecution(List<Map<String, Object>> vectorCandidates,
                               List<Map<String, Object>> rerankedCandidates,
                               List<Map<String, Object>> finalResults) {
            this(RetrievalMode.DENSE, QueryRewriter.RewriteResult.unchanged(""),
                    RetrievalHintPlan.empty(""), vectorCandidates, List.of(), List.of(), List.of(), List.of(),
                    vectorCandidates, rerankedCandidates, finalResults,
                    0, 0, 0, 0, 0);
        }
    }

    public record DocumentSearchExecution(
            SearchExecution trace,
            List<Document> documents) {}

    public record SearchChannelSummary(
            int denseCandidateCount,
            int bm25CandidateCount,
            int fusedCandidateCount,
            long embeddingDurationMs,
            long qdrantDurationMs,
            long retrievalDurationMs,
            boolean rewritten) {}

    public interface SearchProgressListener {
        default void onQueryRewriteStarted(String query, boolean enabled) {}

        default void onQueryRewriteCompleted(QueryRewriter.RewriteResult rewrite) {}

        default void onKeywordAnalysisCompleted(RetrievalHintPlan hints) {}

        default void onHybridRetrievalStarted(RetrievalMode mode, boolean rewritten) {}

        default void onHybridRetrievalCompleted(SearchChannelSummary summary) {}

        default void onRerankStarted(int candidateCount) {}

        default void onRerankCompleted(
                int candidateCount, int finalCount, long durationMs) {}

        static SearchProgressListener noop() {
            return new SearchProgressListener() {};
        }
    }
}
