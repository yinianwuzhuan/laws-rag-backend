package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** 使用通义大模型进行受控法律问题重写。 */
@Slf4j
@Service
public class TongyiQueryRewriter implements QueryRewriter {

    private static final int MAX_REWRITTEN_LENGTH = 600;
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:重写(?:后的)?问题|规范化(?:后的)?问题|检索问题|输出)\\s*[：:]\\s*");
    private static final String SYSTEM_PROMPT = """
            你是法律RAG系统的查询重写器。请把用户的口语问题改写为适合检索中国法律条文的规范问题。

            规则：
            1. 只输出一行重写后的问题，不要回答，不要解释，不要使用Markdown或引号。
            2. 必须保留原问题中的人物关系、行为、金额、日期、期限、数字、法律名称和条文号。
            3. 必须保留否定、例外、先后顺序、主观状态等会改变法律结论的条件。
            4. 可以补充原问题已明确表达的规范法律术语，但不得猜测法律结论，不得编造事实。
            5. 如果问题已经清晰专业，原样输出。
            6. 输出应当是一条可独立理解的检索问题。
            """;

    private final ChatModel chatModel;
    private final boolean enabled;

    public TongyiQueryRewriter(
            @Qualifier("dashScopeChatModel") ChatModel chatModel,
            @Value("${laws.retrieval.query-rewrite.enabled:true}") boolean enabled) {
        this.chatModel = chatModel;
        this.enabled = enabled;
    }

    @Override
    public RewriteResult rewrite(String originalQuery) {
        if (!enabled || originalQuery == null || originalQuery.isBlank()) {
            return RewriteResult.unchanged(originalQuery);
        }
        long startedAt = System.nanoTime();
        try {
            String response = ChatClient.create(chatModel)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(originalQuery)
                    .call()
                    .content();
            String rewritten = sanitize(response);
            long durationMs = elapsedMillis(startedAt);
            if (rewritten.isBlank() || rewritten.length() > MAX_REWRITTEN_LENGTH) {
                log.warn("[QueryRewrite] 模型输出无效，降级使用原问题, length={}", rewritten.length());
                return new RewriteResult(originalQuery, originalQuery, false, true, durationMs);
            }
            boolean changed = !normalize(originalQuery).equals(normalize(rewritten));
            log.info("[QueryRewrite] changed={}, duration={}ms, original='{}', rewritten='{}'",
                    changed, durationMs, originalQuery, rewritten);
            return new RewriteResult(originalQuery, changed ? rewritten : originalQuery,
                    changed, false, durationMs);
        } catch (Exception e) {
            long durationMs = elapsedMillis(startedAt);
            log.warn("[QueryRewrite] 调用失败，降级使用原问题, duration={}ms, reason={}",
                    durationMs, rootMessage(e));
            return new RewriteResult(originalQuery, originalQuery, false, true, durationMs);
        }
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    private String sanitize(String response) {
        if (response == null) return "";
        String result = response.trim()
                .replace("```text", "")
                .replace("```", "")
                .trim();
        result = PREFIX.matcher(result).replaceFirst("");
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '“' && last == '”')) {
                result = result.substring(1, result.length() - 1);
            }
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private long elapsedMillis(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
