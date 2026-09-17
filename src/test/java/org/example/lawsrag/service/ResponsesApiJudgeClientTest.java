package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResponsesApiJudgeClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsStandardResponsesApiOutput() throws Exception {
        var json = objectMapper.readTree("""
                {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\"verdict\\":\\"TIE\\"}"}]}]}
                """);
        assertEquals("{\"verdict\":\"TIE\"}", ResponsesApiJudgeClient.extractOutputText(json));
    }

    @Test
    void readsProxyOutputTextAndChatCompletionFallback() throws Exception {
        assertEquals("direct", ResponsesApiJudgeClient.extractOutputText(
                objectMapper.readTree("{\"output_text\":\"direct\"}")));
        assertEquals("chat", ResponsesApiJudgeClient.extractOutputText(
                objectMapper.readTree("{\"choices\":[{\"message\":{\"content\":\"chat\"}}]}")));
    }

    @Test
    void readsResponsesAndChatCompletionTokenUsage() throws Exception {
        var responsesUsage = ResponsesApiJudgeClient.extractTokenUsage(objectMapper.readTree("""
                {"usage":{"input_tokens":120,"output_tokens":30,"total_tokens":150}}
                """));
        assertEquals(120, responsesUsage.inputTokens());
        assertEquals(30, responsesUsage.outputTokens());
        assertEquals(150, responsesUsage.totalTokens());

        var chatUsage = ResponsesApiJudgeClient.extractTokenUsage(objectMapper.readTree("""
                {"usage":{"prompt_tokens":80,"completion_tokens":20}}
                """));
        assertEquals(100, chatUsage.totalTokens());
    }
}
