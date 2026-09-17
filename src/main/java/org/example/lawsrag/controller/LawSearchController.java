package org.example.lawsrag.controller;

import lombok.RequiredArgsConstructor;
import org.example.lawsrag.service.LawSearchService;
import org.example.lawsrag.service.RetrievalMode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/law")
@RequiredArgsConstructor
public class LawSearchController {

    private final LawSearchService lawSearchService;

    @GetMapping("/search/capabilities")
    public ResponseEntity<Map<String, Object>> searchCapabilities() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "rerankerAvailable", lawSearchService.isRerankerAvailable(),
                "queryRewriteAvailable", lawSearchService.isQueryRewriteAvailable(),
                "retrievalModes", List.of("dense", "bm25", "hybrid")
        ));
    }

    /**
     * 法律条文向量搜索
     * GET /api/law/search?query=xxx&topK=5&source_type=law,judicial_interpretation&chapter=第二章 结婚
     *
     * @param query             查询文本（必填）
     * @param topK              返回条数，默认 5
     * @param sourceType        过滤: source_type（支持多选，逗号分隔，如 "law,judicial_interpretation"）
     * @param sourceName        过滤: source_name
     * @param book              过滤: book
     * @param part              过滤: part（编）
     * @param chapter           过滤: chapter（章）
     * @param section           过滤: section（节）
     * @param articleNo         过滤: article_no（中文条号）
     * @param articleNoArabic   过滤: article_no_arabic（阿拉伯数字条号）
     * @param businessId        过滤: business_id
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK,
            @RequestParam(value = "candidateTopK", defaultValue = "10") int candidateTopK,
            @RequestParam(value = "rerank", defaultValue = "false") boolean rerank,
            @RequestParam(value = "queryRewrite", defaultValue = "false") boolean queryRewrite,
            @RequestParam(value = "retrievalMode", defaultValue = "dense") String retrievalModeValue,
            @RequestParam(value = "source_type", required = false) String sourceType,
            @RequestParam(value = "source_name", required = false) String sourceName,
            @RequestParam(value = "book", required = false) String book,
            @RequestParam(value = "part", required = false) String part,
            @RequestParam(value = "chapter", required = false) String chapter,
            @RequestParam(value = "section", required = false) String section,
            @RequestParam(value = "article_no", required = false) String articleNo,
            @RequestParam(value = "article_no_arabic", required = false) String articleNoArabic,
            @RequestParam(value = "business_id", required = false) String businessId
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "query 参数不能为空"
            ));
        }
        if (topK < 1 || topK > 50) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "topK 必须在1到50之间"));
        }
        if (candidateTopK < 1 || candidateTopK > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "candidateTopK 必须在1到100之间"));
        }
        if (rerank && candidateTopK < topK) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "开启重排时candidateTopK不能小于topK"));
        }
        if (rerank && !lawSearchService.isRerankerAvailable()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "Reranker模型未启用或未加载"));
        }

        // 收集非空的过滤条件
        Map<String, String> filters = new HashMap<>();
        putIfPresent(filters, "source_type", sourceType);
        putIfPresent(filters, "source_name", sourceName);
        putIfPresent(filters, "book", book);
        putIfPresent(filters, "part", part);
        putIfPresent(filters, "chapter", chapter);
        putIfPresent(filters, "section", section);
        putIfPresent(filters, "article_no", articleNo);
        putIfPresent(filters, "article_no_arabic", articleNoArabic);
        putIfPresent(filters, "business_id", businessId);

        RetrievalMode retrievalMode = RetrievalMode.parse(retrievalModeValue);
        LawSearchService.SearchExecution execution = lawSearchService.searchWithTrace(
                query, candidateTopK, topK, filters, retrievalMode, rerank, queryRewrite);
        List<Map<String, Object>> results = execution.finalResults();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("topK", topK);
        response.put("candidateTopK", rerank ? candidateTopK : topK);
        response.put("rerank", rerank);
        response.put("queryRewrite", queryRewrite);
        response.put("rewrite", execution.rewrite());
        response.put("rewrittenQuery", execution.rewrite().rewrittenQuery());
        response.put("retrievalHints", execution.retrievalHints());
        response.put("retrievalMode", retrievalMode.name().toLowerCase());
        response.put("filters", filters.isEmpty() ? "无" : filters);
        response.put("resultCount", results.size());
        response.put("vectorCandidates", execution.vectorCandidates());
        response.put("bm25Candidates", execution.bm25Candidates());
        response.put("fusedCandidates", execution.fusedCandidates());
        response.put("retrievalCandidates", execution.retrievalCandidates());
        response.put("originalDenseCandidates", execution.originalDenseCandidates());
        response.put("originalBm25Candidates", execution.originalBm25Candidates());
        response.put("rewrittenDenseCandidates", execution.rewrittenDenseCandidates());
        response.put("rewrittenBm25Candidates", execution.rewrittenBm25Candidates());
        response.put("rerankedCandidates", execution.rerankedCandidates());
        response.put("results", results);

        return ResponseEntity.ok(response);
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}

