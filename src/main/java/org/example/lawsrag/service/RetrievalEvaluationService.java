package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.service.EvaluationDatasetService.EvaluationCase;
import org.example.lawsrag.service.EvaluationDatasetService.GoldDocument;
import org.example.lawsrag.service.LawSearchService.SearchExecution;
import org.example.lawsrag.service.RetrievalMetrics.CutoffMetric;
import org.example.lawsrag.service.RetrievalMetrics.MetricsResult;
import org.example.lawsrag.service.RetrievalHintParser.RetrievalHintPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class RetrievalEvaluationService {

    private final EvaluationDatasetService datasetService;
    private final LawSearchService lawSearchService;

    public RetrievalEvaluationService(EvaluationDatasetService datasetService,
                                      LawSearchService lawSearchService) {
        this.datasetService = datasetService;
        this.lawSearchService = lawSearchService;
    }

    public EvaluationSummary run(String datasetId,
                                 String split,
                                 int candidateTopK,
                                 int topK,
                                 RetrievalMode retrievalMode,
                                 boolean queryRewrite,
                                 boolean rerank,
                                 Consumer<CaseResult> onCaseCompleted) {
        List<EvaluationCase> selectedCases = datasetService.loadCases(datasetId).stream()
                .filter(item -> "all".equals(split) || split.equals(item.split()))
                .toList();
        if (selectedCases.isEmpty()) {
            throw new IllegalArgumentException("评测集分组没有可执行的问题: " + split);
        }

        long runStartedAt = System.currentTimeMillis();
        List<CaseResult> caseResults = new ArrayList<>();
        for (int i = 0; i < selectedCases.size(); i++) {
            EvaluationCase evaluationCase = selectedCases.get(i);
            CaseResult result = evaluateCase(
                    evaluationCase, i + 1, selectedCases.size(), candidateTopK, topK,
                    retrievalMode, queryRewrite, rerank);
            caseResults.add(result);
            onCaseCompleted.accept(result);
        }
        return summarize(datasetId, split, candidateTopK, topK, retrievalMode, queryRewrite, rerank,
                caseResults, System.currentTimeMillis() - runStartedAt);
    }

    private CaseResult evaluateCase(EvaluationCase evaluationCase,
                                    int sequence,
                                    int total,
                                    int candidateTopK,
                                    int topK,
                                    RetrievalMode retrievalMode,
                                    boolean queryRewrite,
                                    boolean rerank) {
        long startedAt = System.currentTimeMillis();
        try {
            SearchExecution execution = lawSearchService.searchWithTrace(
                    evaluationCase.question(), candidateTopK, topK, Map.of(),
                    retrievalMode, rerank, queryRewrite);
            Map<String, Integer> relevanceById = new HashMap<>();
            for (GoldDocument gold : evaluationCase.goldDocuments()) {
                relevanceById.put(gold.businessId(), gold.relevance());
            }

            List<RetrievedDocument> vectorCandidates = mapDocuments(
                    execution.retrievalCandidates(), relevanceById);
            List<RetrievedDocument> originalDenseCandidates = mapDocuments(
                    execution.originalDenseCandidates(), relevanceById);
            List<RetrievedDocument> originalBm25Candidates = mapDocuments(
                    execution.originalBm25Candidates(), relevanceById);
            List<RetrievedDocument> rewrittenDenseCandidates = mapDocuments(
                    execution.rewrittenDenseCandidates(), relevanceById);
            List<RetrievedDocument> rewrittenBm25Candidates = mapDocuments(
                    execution.rewrittenBm25Candidates(), relevanceById);
            List<RetrievedDocument> rerankedCandidates = mapDocuments(
                    execution.rerankedCandidates(), relevanceById);
            List<RetrievedDocument> retrievedDocuments = mapDocuments(
                    execution.finalResults(), relevanceById);

            long durationMs = System.currentTimeMillis() - startedAt;
            if (!evaluationCase.answerable()) {
                return baseResult(evaluationCase, sequence, total, candidateTopK, topK,
                        retrievalMode, rerank,
                        execution.rewrite(), execution.retrievalHints(),
                        retrievedDocuments.size(), durationMs,
                        "NOT_SCORED", null, originalDenseCandidates, originalBm25Candidates,
                        rewrittenDenseCandidates, rewrittenBm25Candidates,
                        vectorCandidates, rerankedCandidates,
                        retrievedDocuments, null);
            }

            List<String> retrievedIds = retrievedDocuments.stream()
                    .map(RetrievedDocument::businessId)
                    .toList();
            MetricsResult metrics = RetrievalMetrics.calculate(
                    evaluationCase.goldDocuments(), retrievedIds, topK);
            return baseResult(evaluationCase, sequence, total, candidateTopK, topK,
                    retrievalMode, rerank,
                    execution.rewrite(), execution.retrievalHints(),
                    retrievedDocuments.size(), durationMs,
                    "SCORED", null, originalDenseCandidates, originalBm25Candidates,
                    rewrittenDenseCandidates, rewrittenBm25Candidates,
                    vectorCandidates, rerankedCandidates,
                    retrievedDocuments, metrics);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.error("评测题执行失败, id={}, question={}", evaluationCase.id(), evaluationCase.question(), e);
            return baseResult(evaluationCase, sequence, total, candidateTopK, topK,
                    retrievalMode, rerank,
                    QueryRewriter.RewriteResult.unchanged(evaluationCase.question()),
                    RetrievalHintPlan.empty(evaluationCase.question()),
                    0, durationMs,
                    "ERROR", rootMessage(e), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), null);
        }
    }

    private CaseResult baseResult(EvaluationCase item,
                                  int sequence,
                                  int total,
                                  int candidateTopK,
                                  int topK,
                                  RetrievalMode retrievalMode,
                                  boolean rerank,
                                  QueryRewriter.RewriteResult rewrite,
                                  RetrievalHintPlan retrievalHints,
                                  int resultCount,
                                  long durationMs,
                                  String status,
                                  String error,
                                  List<RetrievedDocument> originalDenseCandidates,
                                  List<RetrievedDocument> originalBm25Candidates,
                                  List<RetrievedDocument> rewrittenDenseCandidates,
                                  List<RetrievedDocument> rewrittenBm25Candidates,
                                  List<RetrievedDocument> vectorCandidates,
                                  List<RetrievedDocument> rerankedCandidates,
                                  List<RetrievedDocument> retrievedDocuments,
                                  MetricsResult metrics) {
        return new CaseResult(
                sequence,
                total,
                item.id(),
                item.split(),
                item.category(),
                item.difficulty(),
                item.domain(),
                item.queryType(),
                item.challengeTags(),
                item.question(),
                item.answerable(),
                topK,
                rerank ? candidateTopK : topK,
                retrievalMode.name().toLowerCase(),
                rewrite.changed() || rewrite.fallback() || rewrite.durationMs() > 0,
                rewrite.rewrittenQuery(),
                rewrite.changed(),
                rewrite.fallback(),
                rewrite.durationMs(),
                retrievalHints,
                rerank,
                resultCount,
                durationMs,
                status,
                error,
                item.goldDocuments(),
                item.hardNegativeDocuments(),
                item.expectedBehavior(),
                originalDenseCandidates,
                originalBm25Candidates,
                rewrittenDenseCandidates,
                rewrittenBm25Candidates,
                vectorCandidates,
                rerankedCandidates,
                retrievedDocuments,
                metrics == null ? null : metrics.firstRelevantRank(),
                metrics == null ? null : metrics.reciprocalRank(),
                metrics == null ? List.of() : metrics.cutoffMetrics()
        );
    }

    private EvaluationSummary summarize(String datasetId,
                                        String split,
                                        int candidateTopK,
                                        int topK,
                                        RetrievalMode retrievalMode,
                                        boolean queryRewrite,
                                        boolean rerank,
                                        List<CaseResult> results,
                                        long durationMs) {
        List<CaseResult> scored = results.stream()
                .filter(item -> "SCORED".equals(item.status()))
                .toList();
        List<AggregateCutoffMetric> aggregates = new ArrayList<>();
        for (int cutoff : RetrievalMetrics.cutoffs(topK)) {
            List<CutoffMetric> metrics = scored.stream()
                    .map(item -> metricAt(item.cutoffMetrics(), cutoff))
                    .filter(metric -> metric != null)
                    .toList();
            aggregates.add(new AggregateCutoffMetric(
                    cutoff,
                    average(metrics.stream().map(metric -> metric.hit() ? 1.0 : 0.0).toList()),
                    average(metrics.stream().map(CutoffMetric::recall).toList()),
                    average(metrics.stream().map(CutoffMetric::precision).toList()),
                    average(metrics.stream().map(CutoffMetric::ndcg).toList())
            ));
        }

        List<Long> latencies = results.stream()
                .filter(item -> !"ERROR".equals(item.status()))
                .map(CaseResult::durationMs)
                .sorted()
                .toList();
        return new EvaluationSummary(
                datasetId,
                split,
                topK,
                rerank ? candidateTopK : topK,
                retrievalMode.name().toLowerCase(),
                queryRewrite,
                rerank,
                results.size(),
                scored.size(),
                results.stream().filter(item -> "NOT_SCORED".equals(item.status())).count(),
                results.stream().filter(item -> "ERROR".equals(item.status())).count(),
                RetrievalMetrics.round(scored.stream()
                        .map(CaseResult::reciprocalRank)
                        .filter(value -> value != null)
                        .mapToDouble(Double::doubleValue)
                        .average().orElse(0.0)),
                List.copyOf(aggregates),
                durationMs,
                latencies.isEmpty() ? 0.0 : RetrievalMetrics.round(
                        latencies.stream().mapToLong(Long::longValue).average().orElse(0.0)),
                percentile95(latencies)
        );
    }

    private CutoffMetric metricAt(List<CutoffMetric> metrics, int cutoff) {
        return metrics.stream().filter(item -> item.cutoff() == cutoff).findFirst().orElse(null);
    }

    private double average(List<Double> values) {
        return RetrievalMetrics.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    private long percentile95(List<Long> sortedValues) {
        if (sortedValues.isEmpty()) return 0L;
        int index = (int) Math.ceil(sortedValues.size() * 0.95) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private int numberValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private List<RetrievedDocument> mapDocuments(
            List<Map<String, Object>> documents, Map<String, Integer> relevanceById) {
        List<RetrievedDocument> mapped = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> raw = documents.get(i);
            String businessId = stringValue(raw.get("business_id"));
            Integer relevance = relevanceById.get(businessId);
            mapped.add(new RetrievedDocument(
                    numberValue(raw.get("rank"), i + 1),
                    doubleValue(raw.get("score")),
                    numberValue(raw.get("vector_rank"), i + 1),
                    doubleValue(raw.get("vector_score")),
                    nullableNumberValue(raw.get("rerank_rank")),
                    doubleValue(raw.get("rerank_score")),
                    businessId,
                    stringValue(raw.get("source_name")),
                    stringValue(raw.get("article_no")),
                    stringValue(raw.get("part")),
                    stringValue(raw.get("chapter")),
                    stringValue(raw.get("text")),
                    relevance != null,
                    relevance
            ));
        }
        return List.copyOf(mapped);
    }

    private Integer nullableNumberValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    public boolean isRerankerAvailable() {
        return lawSearchService.isRerankerAvailable();
    }

    public boolean isQueryRewriteAvailable() {
        return lawSearchService.isQueryRewriteAvailable();
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? e.getClass().getSimpleName() : current.getMessage();
    }

    public record RetrievedDocument(
            int rank,
            Double score,
            int vectorRank,
            Double vectorScore,
            Integer rerankRank,
            Double rerankScore,
            String businessId,
            String sourceName,
            String articleNo,
            String part,
            String chapter,
            String text,
            boolean matched,
            Integer relevance
    ) {}

    public record CaseResult(
            int sequence,
            int total,
            String id,
            String split,
            String category,
            String difficulty,
            String domain,
            String queryType,
            List<String> challengeTags,
            String question,
            boolean answerable,
            int topK,
            int candidateTopK,
            String retrievalMode,
            boolean queryRewrite,
            String rewrittenQuery,
            boolean rewriteChanged,
            boolean rewriteFallback,
            long rewriteDurationMs,
            RetrievalHintPlan retrievalHints,
            boolean rerank,
            int resultCount,
            long durationMs,
            String status,
            String error,
            List<GoldDocument> goldDocuments,
            List<EvaluationDatasetService.HardNegativeDocument> hardNegativeDocuments,
            String expectedBehavior,
            List<RetrievedDocument> originalDenseCandidates,
            List<RetrievedDocument> originalBm25Candidates,
            List<RetrievedDocument> rewrittenDenseCandidates,
            List<RetrievedDocument> rewrittenBm25Candidates,
            List<RetrievedDocument> vectorCandidates,
            List<RetrievedDocument> rerankedCandidates,
            List<RetrievedDocument> retrievedDocuments,
            Integer firstRelevantRank,
            Double reciprocalRank,
            List<CutoffMetric> cutoffMetrics
    ) {}

    public record AggregateCutoffMetric(
            int cutoff,
            double hitRate,
            double recall,
            double precision,
            double ndcg
    ) {}

    public record EvaluationSummary(
            String datasetId,
            String split,
            int topK,
            int candidateTopK,
            String retrievalMode,
            boolean queryRewrite,
            boolean rerank,
            int totalCases,
            int scoredCases,
            long unanswerableCases,
            long failedCases,
            double mrr,
            List<AggregateCutoffMetric> cutoffMetrics,
            long durationMs,
            double averageLatencyMs,
            long p95LatencyMs
    ) {}
}
