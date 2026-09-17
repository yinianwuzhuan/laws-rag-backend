package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TongyiAgenticConversationRouterTest {

    @Test
    void bypassesModelAndTreatsQuestionAsIndependentWhenAgenticRoutingIsDisabled() {
        ChatModel model = mock(ChatModel.class);
        ConversationMemoryService memory = new ConversationMemoryService(10, 120);
        TongyiAgenticConversationRouter router = new TongyiAgenticConversationRouter(
                model, new ObjectMapper(), memory, false, 6);

        var decision = router.route("违法解除劳动合同怎么赔偿？", List.of());

        assertEquals(AgenticConversationRouter.Action.INDEPENDENT_RETRIEVAL, decision.action());
        assertFalse(decision.dependsOnHistory());
        assertEquals(decision.originalQuestion(), decision.standaloneQuestion());
        verifyNoInteractions(model);
    }

    @Test
    void asksForClarificationWhenFirstTurnCannotStandAloneAndModelIsUnavailable() {
        ChatModel unavailableModel = mock(ChatModel.class);
        ConversationMemoryService memory = new ConversationMemoryService(10, 120);
        TongyiAgenticConversationRouter router = new TongyiAgenticConversationRouter(
                unavailableModel, new ObjectMapper(), memory, true, 6);

        var decision = router.route("这个合法吗？", List.of());

        assertEquals(AgenticConversationRouter.Action.CLARIFY, decision.action());
        assertTrue(decision.clarificationQuestion().contains("补充"));
        assertTrue(decision.fallback());
    }

    @Test
    void safelyFallsBackToContextualRetrievalForShortFollowUp() {
        ChatModel unavailableModel = mock(ChatModel.class);
        ConversationMemoryService memory = new ConversationMemoryService(10, 120);
        memory.append("conversation", "公司违法解除劳动合同怎么赔？",
                "公司违法解除劳动合同怎么赔？", "应依法计算赔偿金。", List.of());
        TongyiAgenticConversationRouter router = new TongyiAgenticConversationRouter(
                unavailableModel, new ObjectMapper(), memory, true, 6);

        var decision = router.route("那工作了三年半呢？", memory.history("conversation"));

        assertEquals(AgenticConversationRouter.Action.CONTEXTUAL_RETRIEVAL, decision.action());
        assertTrue(decision.dependsOnHistory());
        assertTrue(decision.fallback());
        assertTrue(decision.standaloneQuestion().contains("公司违法解除劳动合同怎么赔"));
        assertTrue(decision.standaloneQuestion().contains("工作了三年半"));
    }
}
