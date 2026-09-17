package org.example.lawsrag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

@SpringBootTest
class LawsRagApplicationTests {

    @Autowired
    @Qualifier("dashScopeChatModel")
    private ChatModel dashscopeChatModel;

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel openaiChatModel;

    @Autowired
    @Qualifier("openRouterChatModel")
    private ChatModel openRouterChatModel;

    @Autowired
    @Qualifier("dashscopeEmbeddingModel")
    private EmbeddingModel dashscopeEmbeddingModel;

    @Autowired
    @Qualifier("openAiEmbeddingModel")
    private EmbeddingModel openaiEmbeddingModel;

    @Autowired
    @Qualifier("openRouterEmbeddingModel")
    private EmbeddingModel openRouterEmbeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void contextLoads() {
    }

    @Test
    void testQdrantSimilaritySearch() {
        String query = "民法调整哪些关系？";
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(0.5)
                        .build()
        );

        System.out.println("=== Qdrant 相似度查询测试 ===");
        System.out.println("查询: " + query);
        System.out.println("命中文档数: " + results.size());
        results.forEach(doc -> {
            System.out.println("---");
            System.out.println("内容: " + doc.getText());
            System.out.println("元数据: " + doc.getMetadata());
        });
    }

    @Test
    void testDashScopeChat() {
        String promptText = "你好，请简单介绍一下你自己";
        ChatResponse response = dashscopeChatModel.call(new Prompt(promptText));
        System.out.println("=== 千问Chat模型测试 ===");
        System.out.println("Response: " + response.getResult().getOutput().getText());
    }

    @Test
    void testOpenAIChat() {
        String promptText = "Hello, please introduce yourself briefly";
        ChatResponse response = openaiChatModel.call(new Prompt(promptText));
        System.out.println("=== OpenAI Chat模型测试 ===");
        System.out.println("Response: " + response.getResult().getOutput().getText());
    }

    @Test
    void testOpenRouterChat() {
        String promptText = "Hello, please introduce yourself briefly";
        ChatResponse response = openRouterChatModel.call(new Prompt(promptText));
        System.out.println("=== OpenRouter Chat模型测试 ===");
        System.out.println("Response: " + response.getResult().getOutput().getText());
    }

    @Test
    void testOpenRouterEmbedding() {
        String text = "This is a test text to verify OpenRouter embedding functionality";
        EmbeddingResponse response = openRouterEmbeddingModel.embedForResponse(List.of(text));

        float[] embedding = response.getResults().get(0).getOutput();
        System.out.println("=== OpenRouter Embedding模型测试 ===");
        System.out.println("原始文本: " + text);
        System.out.println("Embedding模型: " + response.getMetadata().getModel());
        System.out.println("向量维度: " + embedding.length);
        System.out.println("Token使用: " + response.getMetadata().getUsage().getTotalTokens());
        System.out.println("向量前10个值:");
        for (int i = 0; i < Math.min(10, embedding.length); i++) {
            System.out.printf("  [%d]: %.6f\n", i, embedding[i]);
        }
    }

    @Test
    void testDashScopeEmbedding() {
        String text = "这是一个测试文本，用于验证千问embedding功能";
        EmbeddingResponse response = dashscopeEmbeddingModel.embedForResponse(List.of(text));

        float[] embedding = response.getResults().get(0).getOutput();
        System.out.println("=== 千问Embedding模型测试 ===");
        System.out.println("原始文本: " + text);
        System.out.println("Embedding模型: " + response.getMetadata().getModel());
        System.out.println("向量维度: " + embedding.length);
        System.out.println("Token使用: " + response.getMetadata().getUsage().getTotalTokens());
        System.out.println("向量前10个值:");
        for (int i = 0; i < Math.min(10, embedding.length); i++) {
            System.out.printf("  [%d]: %.6f\n", i, embedding[i]);
        }
        System.out.println("向量后10个值:");
        for (int i = Math.max(0, embedding.length - 10); i < embedding.length; i++) {
            System.out.printf("  [%d]: %.6f\n", i, embedding[i]);
        }
    }

    @Test
    void testOpenAIEmbedding() {
        String text = "This is a test text to verify OpenAI embedding functionality";
        EmbeddingResponse response = openaiEmbeddingModel.embedForResponse(List.of(text));

        float[] embedding = response.getResults().get(0).getOutput();
        System.out.println("=== OpenAI Embedding模型测试 ===");
        System.out.println("原始文本: " + text);
        System.out.println("Embedding模型: " + response.getMetadata().getModel());
        System.out.println("向量维度: " + embedding.length);
        System.out.println("Token使用: " + response.getMetadata().getUsage().getTotalTokens());
        System.out.println("向量前10个值:");
        for (int i = 0; i < Math.min(10, embedding.length); i++) {
            System.out.printf("  [%d]: %.6f\n", i, embedding[i]);
        }
        System.out.println("向量后10个值:");
        for (int i = Math.max(0, embedding.length - 10); i < embedding.length; i++) {
            System.out.printf("  [%d]: %.6f\n", i, embedding[i]);
        }
    }

    @Test
    void testMultiModelUsage() {
        String text = "法律知识库检索测试";

        // 使用千问生成embedding
        EmbeddingResponse dashscopeEmbedding = dashscopeEmbeddingModel.embedForResponse(List.of(text));
        System.out.println("=== 多模型同时使用测试 ===");
        System.out.println("千问Embedding维度: " + dashscopeEmbedding.getResults().get(0).getOutput().length);

        // 使用OpenAI生成embedding
        EmbeddingResponse openaiEmbedding = openaiEmbeddingModel.embedForResponse(List.of(text));
        System.out.println("OpenAI Embedding维度: " + openaiEmbedding.getResults().get(0).getOutput().length);

        // 使用OpenRouter生成embedding
//        EmbeddingResponse openRouterEmbedding = openRouterEmbeddingModel.embedForResponse(List.of(text));
//        System.out.println("OpenRouter Embedding维度: " + openRouterEmbedding.getResults().get(0).getOutput().length);

        // 使用千问进行对话
        String promptText = "请解释以下法律概念：" + text;
        ChatResponse dashscopeChat = dashscopeChatModel.call(new Prompt(promptText));
        System.out.println("千问Chat响应: " + dashscopeChat.getResult().getOutput().getText());

        // 使用OpenAI进行对话
        String promptTextEn = "Explain the following legal concept: legal knowledge base retrieval";
        ChatResponse openaiChat = openaiChatModel.call(new Prompt(promptTextEn));
        System.out.println("OpenAI Chat响应: " + openaiChat.getResult().getOutput().getText());

        // 使用OpenRouter进行对话
        ChatResponse openRouterChat = openRouterChatModel.call(new Prompt(promptTextEn));
        System.out.println("OpenRouter Chat响应: " + openRouterChat.getResult().getOutput().getText());
    }

}
