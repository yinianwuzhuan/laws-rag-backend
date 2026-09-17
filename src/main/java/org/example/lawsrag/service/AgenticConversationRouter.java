package org.example.lawsrag.service;

import java.util.List;

/** 多轮法律咨询的受限 Agentic 路由器。 */
public interface AgenticConversationRouter {

    Decision route(String currentQuestion, List<ConversationMemoryService.ConversationTurn> history);

    enum Action {
        INDEPENDENT_RETRIEVAL,
        CONTEXTUAL_RETRIEVAL,
        ANSWER_FROM_CONTEXT,
        CLARIFY,
        OUT_OF_SCOPE
    }

    record Decision(
            Action action,
            String originalQuestion,
            String standaloneQuestion,
            String clarificationQuestion,
            boolean dependsOnHistory,
            boolean topicChanged,
            String reason,
            double confidence,
            long durationMs,
            boolean fallback) {

        public static Decision independent(String question) {
            return new Decision(Action.INDEPENDENT_RETRIEVAL, question, question, "",
                    false, false, "问题语义完整，可独立检索", 1.0, 0, false);
        }
    }
}
