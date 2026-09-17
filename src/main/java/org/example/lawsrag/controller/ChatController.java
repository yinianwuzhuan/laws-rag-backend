package org.example.lawsrag.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.service.LawChatService;
import org.example.lawsrag.service.LawChatService.AgentTraceEvent;
import org.example.lawsrag.service.LawChatService.RagResult;
import org.example.lawsrag.service.LawChatService.RagStreamResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatModel chatModel;
    private final LawChatService lawChatService;
    private final ObjectMapper objectMapper;

    public ChatController(@Qualifier("dashScopeChatModel") ChatModel chatModel,
                          LawChatService lawChatService,
                          ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.lawChatService = lawChatService;
        this.objectMapper = objectMapper;
    }

    // ==================== 普通聊天（无 RAG） ====================

    /**
     * 简单聊天接口（非流式）
     * POST /api/chat
     */
    @PostMapping
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        log.info("[Chat] 收到请求, message: {}", request.message());
        long start = System.currentTimeMillis();

        String response = ChatClient.create(chatModel)
                .prompt()
                .user(request.message())
                .call()
                .content();

        long cost = System.currentTimeMillis() - start;
        log.info("[Chat] 响应完成, 耗时: {}ms, 输出长度: {} 字符", cost, response != null ? response.length() : 0);
        log.debug("[Chat] 完整输出: {}", response);

        return Map.of(
                "success", true,
                "message", request.message(),
                "response", response
        );
    }

    /**
     * 流式聊天接口
     * POST /api/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        log.info("[ChatStream] 收到请求, message: {}", request.message());
        long start = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();

        return ChatClient.create(chatModel)
                .prompt()
                .user(request.message())
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    long cost = System.currentTimeMillis() - start;
                    log.info("[ChatStream] 流式响应完成, 耗时: {}ms, 输出长度: {} 字符", cost, fullResponse.length());
                    log.debug("[ChatStream] 完整输出: {}", fullResponse);
                })
                .doOnError(e -> log.error("[ChatStream] 流式响应异常, message: {}", request.message(), e));
    }

    // ==================== RAG 法律问答 ====================

    /**
     * RAG 法律问答（非流式）
     * POST /api/chat/law
     *
     * @param request question: 用户问题（必填）；topK: 返回条数（默认5）
     */
    @PostMapping("/law")
    public Map<String, Object> lawChat(@RequestBody LawChatRequest request) {
        int topK = (request.topK() != null && request.topK() >= 1) ? request.topK() : 5;
        String conversationId = normalizeConversationId(request.conversationId());
        RagResult result = lawChatService.chat(
                conversationId, request.question(), topK);

        return Map.of(
                "success", true,
                "conversationId", result.conversationId(),
                "question", result.question(),
                "answer", result.answer(),
                "references", result.references(),
                "agentDecision", result.agentDecision()
        );
    }

    /**
     * RAG 法律问答（流式）
     * POST /api/chat/law/stream
     * 返回 SSE 回答内容流
     *
     * @param request question: 用户问题（必填）；topK: 返回条数（默认5）
     */
    @PostMapping(value = "/law/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> lawChatStream(@RequestBody LawChatRequest request) {
        int topK = (request.topK() != null && request.topK() >= 1) ? request.topK() : 5;
        String conversationId = normalizeConversationId(request.conversationId());
        long startedAt = System.nanoTime();

        return Flux.create(sink -> {
            AtomicReference<Disposable> contentSubscription = new AtomicReference<>();
            Disposable preparationTask = Schedulers.boundedElastic().schedule(() -> {
                try {
                    RagStreamResult result = lawChatService.chatStream(
                            conversationId, request.question(), topK,
                            trace -> sink.next(traceEvent(trace)));

                    Map<String, Object> agentMetadata = Map.of(
                            "conversationId", result.conversationId(),
                            "decision", result.agentDecision()
                    );
                    sink.next(ServerSentEvent.<String>builder()
                            .event("agent")
                            .data(toJson(agentMetadata))
                            .build());

                    contentSubscription.set(result.contentFlux().subscribe(
                            chunk -> sink.next(ServerSentEvent.<String>builder()
                                    .event("content")
                                    .data(chunk)
                                    .build()),
                            error -> {
                                log.error("[RAGStream] 内容流异常, conversationId={}",
                                        conversationId, error);
                                sink.next(errorEvent(rootMessage(error)));
                                sink.complete();
                            },
                            () -> {
                                sink.next(ServerSentEvent.<String>builder()
                                        .event("done")
                                        .data(toJson(Map.of(
                                                "conversationId", result.conversationId(),
                                                "evidenceCount", result.references().size(),
                                                "durationMs", elapsedMillis(startedAt))))
                                        .build());
                                sink.complete();
                            }));
                } catch (Exception error) {
                    log.error("[RAGStream] Agent执行异常, conversationId={}", conversationId, error);
                    sink.next(errorEvent(rootMessage(error)));
                    sink.complete();
                }
            });
            sink.onDispose(() -> {
                preparationTask.dispose();
                Disposable subscription = contentSubscription.get();
                if (subscription != null) subscription.dispose();
            });
        });
    }

    private ServerSentEvent<String> traceEvent(AgentTraceEvent trace) {
        return ServerSentEvent.<String>builder()
                .event("trace")
                .data(toJson(trace))
                .build();
    }

    private ServerSentEvent<String> errorEvent(String message) {
        return ServerSentEvent.<String>builder()
                .event("error")
                .data(toJson(Map.of("success", false, "message", message)))
                .build();
    }

    private String normalizeConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? UUID.randomUUID().toString()
                : conversationId.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent事件序列化失败", e);
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    // ---- 请求体 ----

    public record ChatRequest(String message) {}

    public record LawChatRequest(
            String conversationId,
            String question,
            Integer topK) {}
}
