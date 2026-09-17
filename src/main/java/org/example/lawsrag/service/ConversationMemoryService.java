package org.example.lawsrag.service;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第一版会话记忆。使用有界、带过期时间的内存实现，接口可平滑替换为 Redis/MySQL。
 */
@Service
public class ConversationMemoryService {

    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
    private final int maxTurns;
    private final Duration ttl;

    public ConversationMemoryService(
            @Value("${laws.agentic-rag.memory.max-turns:10}") int maxTurns,
            @Value("${laws.agentic-rag.memory.ttl-minutes:120}") long ttlMinutes) {
        this.maxTurns = Math.max(1, maxTurns);
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    public List<ConversationTurn> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        ConversationState state = conversations.get(conversationId);
        if (state == null) return List.of();
        synchronized (state) {
            if (state.updatedAt.plus(ttl).isBefore(Instant.now())) {
                conversations.remove(conversationId, state);
                return List.of();
            }
            return List.copyOf(state.turns);
        }
    }

    public void append(String conversationId, String userQuestion, String standaloneQuestion,
                       String answer, List<Document> evidence) {
        if (conversationId == null || conversationId.isBlank()) return;
        ConversationState state = conversations.computeIfAbsent(
                conversationId, ignored -> new ConversationState());
        synchronized (state) {
            state.turns.add(new ConversationTurn(
                    userQuestion,
                    standaloneQuestion == null || standaloneQuestion.isBlank()
                            ? userQuestion : standaloneQuestion,
                    answer == null ? "" : answer,
                    evidence == null ? List.of() : List.copyOf(evidence),
                    Instant.now()));
            while (state.turns.size() > maxTurns) state.turns.removeFirst();
            state.updatedAt = Instant.now();
        }
    }

    public void clear(String conversationId) {
        if (conversationId != null) conversations.remove(conversationId);
    }

    public String formatHistory(List<ConversationTurn> history, int maxHistoryTurns) {
        if (history == null || history.isEmpty()) return "（无历史会话）";
        int from = Math.max(0, history.size() - Math.max(1, maxHistoryTurns));
        StringBuilder text = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            ConversationTurn turn = history.get(i);
            text.append("用户：").append(turn.userQuestion()).append('\n');
            text.append("助手：").append(turn.answer()).append('\n');
        }
        return text.toString().trim();
    }

    public List<Document> latestEvidence(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        return history.getLast().evidence();
    }

    public record ConversationTurn(
            String userQuestion,
            String standaloneQuestion,
            String answer,
            List<Document> evidence,
            Instant createdAt) {
    }

    private static final class ConversationState {
        private final List<ConversationTurn> turns = new ArrayList<>();
        private Instant updatedAt = Instant.now();
    }
}
