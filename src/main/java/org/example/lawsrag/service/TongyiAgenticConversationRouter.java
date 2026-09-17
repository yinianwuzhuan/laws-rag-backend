package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 使用通义模型执行受限的多轮意图判断与上下文补全。 */
@Slf4j
@Service
public class TongyiAgenticConversationRouter implements AgenticConversationRouter {

    private static final Pattern CONTEXT_HINT = Pattern.compile(
            "^(?:这|那|那么|这个|这种|上述|刚才|前面|如果|还有|以及)|(?:呢|怎么办|怎么算|什么意思|怎么理解|能举例吗)[？?]?$"
    );
    private static final Pattern EXPLANATION_HINT = Pattern.compile(
            ".*(什么意思|解释一下|简单说|通俗解释|举个例子|第二点|上一条|刚才说的).*"
    );
    private static final String SYSTEM_PROMPT = """
            你是法律咨询系统的多轮会话路由器。你只负责判断执行路径和消解上下文省略，不回答法律问题。

            只允许输出一个JSON对象，字段如下：
            {
              "action":"INDEPENDENT_RETRIEVAL|CONTEXTUAL_RETRIEVAL|ANSWER_FROM_CONTEXT|CLARIFY|OUT_OF_SCOPE",
              "standaloneQuestion":"可独立理解的问题",
              "clarificationQuestion":"需要追问时给用户的问题，否则为空字符串",
              "dependsOnHistory":true,
              "topicChanged":false,
              "reason":"一句简短、可向用户展示的路由原因",
              "confidence":0.0
            }

            决策规则：
            1. 当前问题自身完整且可以单独进行法律检索，选择INDEPENDENT_RETRIEVAL；standaloneQuestion必须保持原问题。
            2. 当前问题包含代词、省略主体、省略争议背景或“那三年半呢”一类追问，选择CONTEXTUAL_RETRIEVAL，并仅用历史中已经明确出现的事实补全为完整问题。
            3. 用户只是要求解释、简化或举例说明上一轮答案，且无需新增法律规则，选择ANSWER_FROM_CONTEXT。
            4. 即使结合历史仍缺少理解问题所必需的信息，选择CLARIFY并生成一个最小追问。
            5. 明确与法律无关的问题选择OUT_OF_SCOPE。
            6. 新法律话题选择INDEPENDENT_RETRIEVAL并设置topicChanged=true，不得带入旧话题事实。
            7. 严禁猜测或新增人物、行为、金额、日期、期限、法律概念、罪名和法律结论。
            8. 上下文补全只消解省略信息，不生成“专业关键词”，不优化检索措辞。
            9. reason只描述用户问题为何需要或不需要上下文，不得泄露推理过程。
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryService memoryService;
    private final boolean enabled;
    private final int maxHistoryTurns;

    public TongyiAgenticConversationRouter(
            @Qualifier("dashScopeChatModel") ChatModel chatModel,
            ObjectMapper objectMapper,
            ConversationMemoryService memoryService,
            @Value("${laws.agentic-rag.enabled:true}") boolean enabled,
            @Value("${laws.agentic-rag.max-history-turns:6}") int maxHistoryTurns) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.enabled = enabled;
        this.maxHistoryTurns = Math.max(1, maxHistoryTurns);
    }

    @Override
    public Decision route(String currentQuestion, List<ConversationMemoryService.ConversationTurn> history) {
        if (!enabled) {
            return Decision.independent(currentQuestion);
        }
        List<ConversationMemoryService.ConversationTurn> safeHistory =
                history == null ? List.of() : history;
        long startedAt = System.nanoTime();
        String historyText = memoryService.formatHistory(safeHistory, maxHistoryTurns);
        String userPrompt = "历史会话：\n" + historyText + "\n\n当前问题：\n" + currentQuestion;
        try {
            String response = ChatClient.create(chatModel)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            Decision decision = parse(currentQuestion, response, elapsedMillis(startedAt));
            log.info("[AgenticRouter] action={}, dependsOnHistory={}, topicChanged={}, confidence={}, "
                            + "duration={}ms, original='{}', standalone='{}', reason='{}'",
                    decision.action(), decision.dependsOnHistory(), decision.topicChanged(),
                    decision.confidence(), decision.durationMs(), currentQuestion,
                    decision.standaloneQuestion(), decision.reason());
            return decision;
        } catch (Exception e) {
            Decision fallback = fallback(currentQuestion, safeHistory, elapsedMillis(startedAt));
            log.warn("[AgenticRouter] 路由失败，使用安全降级, action={}, reason={}",
                    fallback.action(), rootMessage(e));
            return fallback;
        }
    }

    private Decision parse(String originalQuestion, String response, long durationMs) throws Exception {
        String json = extractJson(response);
        JsonNode root = objectMapper.readTree(json);
        Action action = Action.valueOf(root.path("action").asText("").toUpperCase(Locale.ROOT));
        String standalone = root.path("standaloneQuestion").asText("").trim();
        String clarification = root.path("clarificationQuestion").asText("").trim();
        boolean depends = root.path("dependsOnHistory").asBoolean(false);
        boolean topicChanged = root.path("topicChanged").asBoolean(false);
        String reason = root.path("reason").asText("").trim();
        double confidence = Math.max(0, Math.min(1, root.path("confidence").asDouble(0.5)));

        if ((action == Action.INDEPENDENT_RETRIEVAL || action == Action.CONTEXTUAL_RETRIEVAL)
                && standalone.isBlank()) {
            throw new IllegalArgumentException("standaloneQuestion不能为空");
        }
        if (action == Action.INDEPENDENT_RETRIEVAL) standalone = originalQuestion;
        if (action == Action.CLARIFY && clarification.isBlank()) {
            clarification = "请补充与该问题相关的具体事实，以便进一步判断。";
        }
        return new Decision(action, originalQuestion, standalone, clarification,
                depends, topicChanged, reason, confidence, durationMs, false);
    }

    private Decision fallback(String question,
                              List<ConversationMemoryService.ConversationTurn> history,
                              long durationMs) {
        String normalized = question == null ? "" : question.trim();
        if (history.isEmpty() && normalized.length() <= 24
                && CONTEXT_HINT.matcher(normalized).find()) {
            return new Decision(Action.CLARIFY, question, question,
                    "请补充具体发生了什么事情，以及您希望判断的法律问题。",
                    false, false, "当前问题缺少可供判断的具体事实", 0.55, durationMs, true);
        }
        if (EXPLANATION_HINT.matcher(normalized).matches()) {
            return new Decision(Action.ANSWER_FROM_CONTEXT, question, question, "",
                    true, false, "当前问题是在解释上一轮回答", 0.55, durationMs, true);
        }
        if (!history.isEmpty() && normalized.length() <= 24
                && CONTEXT_HINT.matcher(normalized).find()) {
            String previous = history.getLast().standaloneQuestion();
            String standalone = previous + "；用户继续追问：" + normalized;
            return new Decision(Action.CONTEXTUAL_RETRIEVAL, question, standalone, "",
                    true, false, "当前问题依赖上一轮语境", 0.5, durationMs, true);
        }
        return new Decision(Action.INDEPENDENT_RETRIEVAL, question, question, "",
                false, false, "路由不可用，按独立问题安全检索", 0.4, durationMs, true);
    }

    private String extractJson(String response) {
        if (response == null) return "";
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) return response.trim();
        return response.substring(start, end + 1);
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
