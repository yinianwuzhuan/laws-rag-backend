package org.example.lawsrag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryServiceTest {

    @Test
    void isolatesConversationsAndKeepsOnlyConfiguredTurnCount() {
        ConversationMemoryService memory = new ConversationMemoryService(2, 120);

        memory.append("conversation-a", "问题1", "完整问题1", "回答1", List.of());
        memory.append("conversation-a", "问题2", "完整问题2", "回答2", List.of());
        memory.append("conversation-a", "问题3", "完整问题3", "回答3", List.of());
        memory.append("conversation-b", "另一个问题", "另一个问题", "另一个回答", List.of());

        assertEquals(2, memory.history("conversation-a").size());
        assertEquals("问题2", memory.history("conversation-a").getFirst().userQuestion());
        assertEquals(1, memory.history("conversation-b").size());
    }

    @Test
    void clearsConversationWithoutAffectingOthers() {
        ConversationMemoryService memory = new ConversationMemoryService(5, 120);
        memory.append("a", "问题", "问题", "回答", List.of());
        memory.append("b", "问题", "问题", "回答", List.of());

        memory.clear("a");

        assertTrue(memory.history("a").isEmpty());
        assertEquals(1, memory.history("b").size());
    }
}
