package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "laws.retrieval.reranker",
        name = "provider",
        havingValue = "dashscope")
public class DashScopeDocumentReranker implements DocumentReranker {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String instruct;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final boolean fallbackEnabled;
    private final boolean logDetails;

    @Autowired
    public DashScopeDocumentReranker(
            ObjectMapper objectMapper,
            @Value("${laws.retrieval.reranker.enabled:false}") boolean enabled,
            @Value("${laws.retrieval.reranker.dashscope.base-url}") String baseUrl,
            @Value("${laws.retrieval.reranker.dashscope.path:/compatible-api/v1/reranks}") String path,
            @Value("${laws.retrieval.reranker.dashscope.model:qwen3-rerank}") String model,
            @Value("${spring.ai.dashscope.api-key:disabled}") String apiKey,
            @Value("${laws.retrieval.reranker.dashscope.instruct:Given a legal research query, retrieve relevant legal provisions that answer the query.}") String instruct,
            @Value("${laws.retrieval.reranker.dashscope.timeout-seconds:15}") int timeoutSeconds,
            @Value("${laws.retrieval.reranker.dashscope.max-attempts:2}") int maxAttempts,
            @Value("${laws.retrieval.reranker.dashscope.initial-backoff-ms:300}") long initialBackoffMs,
            @Value("${laws.retrieval.reranker.dashscope.fallback-enabled:true}") boolean fallbackEnabled,
            @Value("${laws.retrieval.reranker.log-details:false}") boolean logDetails) {
        this(objectMapper, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build(),
                enabled, endpoint(baseUrl, path), apiKey, model, instruct,
                Duration.ofSeconds(Math.max(1, timeoutSeconds)), maxAttempts,
                initialBackoffMs, fallbackEnabled, logDetails);
    }

    DashScopeDocumentReranker(
            ObjectMapper objectMapper, HttpClient httpClient, boolean enabled,
            URI endpoint, String apiKey, String model, String instruct,
            Duration requestTimeout, int maxAttempts, long initialBackoffMs,
            boolean fallbackEnabled, boolean logDetails) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.instruct = instruct;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.fallbackEnabled = fallbackEnabled;
        this.logDetails = logDetails;
        if (enabled) {
            log.info("[RemoteReranker] 已启用DashScope重排, endpoint={}, model={}, fallback={}",
                    endpoint, model, fallbackEnabled);
        }
    }

    @Override
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && !"disabled".equalsIgnoreCase(apiKey);
    }

    @Override
    public List<RerankedDocument> rerank(
            String query, List<CandidateDocument> candidates, int finalTopK) {
        if (!isAvailable()) {
            throw new IllegalStateException("DashScope Reranker未启用或API Key未配置");
        }
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (finalTopK < 1) throw new IllegalArgumentException("finalTopK必须大于0");

        long startedAt = System.nanoTime();
        try {
            String requestBody = requestBody(query, candidates, finalTopK);
            if (logDetails) {
                log.info("[RemoteRerankerInput] endpoint={}, model={}, query={}, candidates={}",
                        endpoint, model, query, candidates.stream().map(CandidateDocument::passage).toList());
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode()
                        + ": " + abbreviate(response.body(), 800));
            }
            List<RerankedDocument> results = parseResults(
                    objectMapper.readTree(response.body()), candidates, finalTopK);
            log.info("[RemoteRerankerTiming] provider=dashscope, model={}, candidates={}, requestedTopK={}, returned={}, duration={}ms",
                    model, candidates.size(), Math.min(finalTopK, candidates.size()),
                    results.size(), elapsedMillis(startedAt));
            if (logDetails) log.info("[RemoteRerankerOutput] results={}", results);
            return results;
        } catch (Exception error) {
            if (!fallbackEnabled) {
                throw new IllegalStateException("DashScope重排失败: " + rootMessage(error), error);
            }
            log.warn("[RemoteReranker] 调用失败，降级为Hybrid融合顺序, reason={}",
                    rootMessage(error));
            return fallback(candidates, finalTopK);
        }
    }

    private String requestBody(
            String query, List<CandidateDocument> candidates, int finalTopK) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query);
        body.put("documents", candidates.stream().map(CandidateDocument::passage).toList());
        body.put("top_n", Math.min(finalTopK, candidates.size()));
        if (instruct != null && !instruct.isBlank()) body.put("instruct", instruct);
        return objectMapper.writeValueAsString(body);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (!retryable(response.statusCode()) || attempt == maxAttempts) return response;
                sleep(backoffMs(attempt));
            } catch (java.io.IOException error) {
                if (attempt == maxAttempts) throw error;
                sleep(backoffMs(attempt));
            }
        }
        throw new IllegalStateException("DashScope重试流程异常结束");
    }

    static List<RerankedDocument> parseResults(
            JsonNode root, List<CandidateDocument> candidates, int finalTopK) {
        JsonNode resultsNode = root.path("results");
        if (!resultsNode.isArray()) {
            String message = root.path("message").asText();
            throw new IllegalStateException(message.isBlank()
                    ? "DashScope响应缺少results数组" : message);
        }
        List<RerankedDocument> results = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                throw new IllegalStateException("DashScope返回非法文档索引: " + index);
            }
            double score = item.path("relevance_score").asDouble(Double.NaN);
            if (!Double.isFinite(score)) {
                throw new IllegalStateException("DashScope返回非法相关性分数");
            }
            results.add(new RerankedDocument(candidates.get(index), 0, score, score));
        }
        results.sort(Comparator.comparingDouble(RerankedDocument::rawScore).reversed()
                .thenComparingInt(item -> item.candidate().vectorRank()));
        List<RerankedDocument> ranked = new ArrayList<>();
        for (int index = 0; index < Math.min(finalTopK, results.size()); index++) {
            RerankedDocument item = results.get(index);
            ranked.add(new RerankedDocument(
                    item.candidate(), index + 1, item.rawScore(), item.normalizedScore()));
        }
        if (ranked.isEmpty() && !candidates.isEmpty()) {
            throw new IllegalStateException("DashScope未返回任何重排结果");
        }
        return List.copyOf(ranked);
    }

    private List<RerankedDocument> fallback(
            List<CandidateDocument> candidates, int finalTopK) {
        List<RerankedDocument> results = new ArrayList<>();
        for (int index = 0; index < Math.min(finalTopK, candidates.size()); index++) {
            CandidateDocument candidate = candidates.get(index);
            double score = candidate.vectorScore() == null ? 0.0 : candidate.vectorScore();
            results.add(new RerankedDocument(candidate, index + 1, score, score));
        }
        return List.copyOf(results);
    }

    private boolean retryable(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    private long backoffMs(int completedAttempt) {
        return initialBackoffMs * (1L << Math.min(completedAttempt - 1, 10));
    }

    private void sleep(long delayMs) throws InterruptedException {
        if (delayMs > 0) Thread.sleep(delayMs);
    }

    private static URI endpoint(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBase + normalizedPath);
    }

    private long elapsedMillis(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
