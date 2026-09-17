package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成评测专用的 OpenAI Responses API 客户端。
 * 与 OpenRouter Embedding 完全分离，防止更换 Judge 中转站影响已有向量检索。
 */
@Component
@Slf4j
public class ResponsesApiJudgeClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final boolean logDetails;
    private final int maxAttempts;
    private final long initialBackoffMs;

    public ResponsesApiJudgeClient(
            ObjectMapper objectMapper,
            @Value("${spring.ai.judge-relay.api-key:disabled}") String apiKey,
            @Value("${spring.ai.judge-relay.base-url:https://cf.api.fan/v1/responses}") String baseUrl,
            @Value("${spring.ai.judge-relay.model:gpt-5.5}") String model,
            @Value("${laws.generation-evaluation.judge-log-details:true}") boolean logDetails,
            @Value("${laws.generation-evaluation.judge-max-attempts:3}") int maxAttempts,
            @Value("${laws.generation-evaluation.judge-initial-backoff-ms:1000}") long initialBackoffMs) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = URI.create(baseUrl);
        this.model = model;
        this.logDetails = logDetails;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public String generate(String instructions, String input) throws Exception {
        return generateWithUsage(instructions, input).content();
    }

    public JudgeResponse generateWithUsage(String instructions, String input) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("CF_API_FAN_API_KEY 未配置，无法调用生成评测 Judge");
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("instructions", instructions);
        requestBody.put("input", input);
        requestBody.put("max_output_tokens", 4000);
        String serializedRequest = objectMapper.writeValueAsString(requestBody);

        if (logDetails) {
            log.info("[JudgeInput] endpoint={}, model={}\n"
                            + "--- System Instructions 开始 ---\n{}\n--- System Instructions 结束 ---\n"
                            + "--- User Input 开始 ---\n{}\n--- User Input 结束 ---",
                    endpoint, model, instructions, input);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "laws-rag-generation-evaluation/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(serializedRequest))
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        if (logDetails) {
            log.info("[JudgeOutput] endpoint={}, model={}, status={}\n--- 完整响应开始 ---\n{}\n--- 完整响应结束 ---",
                    endpoint, model, response.statusCode(), response.body());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Judge Responses API 调用失败，HTTP "
                    + response.statusCode() + ": " + abbreviate(response.body(), 1000));
        }
        JsonNode root = objectMapper.readTree(response.body());
        return new JudgeResponse(extractOutputText(root), extractTokenUsage(root));
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (!isRetryable(response.statusCode()) || attempt == maxAttempts) {
                    return response;
                }
                long delayMs = backoffMs(attempt);
                log.warn("[JudgeRetry] attempt={}/{}, status={}, backoff={}ms, body={}",
                        attempt, maxAttempts, response.statusCode(), delayMs,
                        abbreviate(response.body(), 300));
                sleep(delayMs);
            } catch (java.io.IOException e) {
                if (attempt == maxAttempts) throw e;
                long delayMs = backoffMs(attempt);
                log.warn("[JudgeRetry] attempt={}/{}, ioError='{}', backoff={}ms",
                        attempt, maxAttempts, e.getMessage(), delayMs);
                sleep(delayMs);
            }
        }
        throw new IllegalStateException("Judge Responses API 重试流程异常结束");
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    private long backoffMs(int completedAttempt) {
        return initialBackoffMs * (1L << Math.min(completedAttempt - 1, 10));
    }

    private void sleep(long delayMs) throws InterruptedException {
        if (delayMs <= 0) return;
        Thread.sleep(delayMs);
    }

    /** 兼容标准 Responses API、带 output_text 的代理包装及 Chat Completions 包装。 */
    static String extractOutputText(JsonNode root) {
        String direct = text(root.path("output_text"));
        if (!direct.isBlank()) return direct;

        StringBuilder output = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            for (JsonNode content : item.path("content")) {
                String value = text(content.path("text"));
                if (!value.isBlank()) output.append(value);
            }
        }
        if (!output.isEmpty()) return output.toString();

        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String chatText = text(content);
        if (!chatText.isBlank()) return chatText;

        String error = text(root.path("error").path("message"));
        throw new IllegalStateException(error.isBlank()
                ? "Judge Responses API 响应中没有可识别的文本输出"
                : "Judge Responses API 返回错误: " + error);
    }

    /** 兼容 Responses API 与 Chat Completions 风格中转站的 Usage 字段。 */
    static ModelTokenUsage extractTokenUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return ModelTokenUsage.unavailable();
        }
        Number input = number(usage, "input_tokens", "prompt_tokens");
        Number output = number(usage, "output_tokens", "completion_tokens");
        Number total = number(usage, "total_tokens");
        return ModelTokenUsage.of(input, output, total);
    }

    private static Number number(JsonNode parent, String... names) {
        for (String name : names) {
            JsonNode node = parent.path(name);
            if (node.isNumber()) return node.longValue();
        }
        return null;
    }

    public record JudgeResponse(String content, ModelTokenUsage tokenUsage) {}

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && !"disabled".equalsIgnoreCase(apiKey);
    }

    public String model() {
        return model;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
