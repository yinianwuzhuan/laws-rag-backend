package org.example.lawsrag.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.example.lawsrag.service.TimedEmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.LinkedMultiValueMap;

/**
 * 多模型配置示例
 * 展示如何在同一个应用中同时使用多个AI模型提供商
 *
 * DashScope、OpenAI 的 ChatModel / EmbeddingModel 已由各自的 AutoConfiguration 自动注册。
 * OpenRouter 需要手动配置，使用 OpenAI 兼容的 API 接口。
 */
@Configuration
public class AIModelConfig {

    /**
     * OpenRouter Chat模型
     * 使用 OpenAI 兼容的 API 接口访问 OpenRouter
     */
    @Bean
    @Qualifier("openRouterChatModel")
    public ChatModel openRouterChatModel(
            @Value("${spring.ai.openrouter.api-key}") String apiKey,
            @Value("${spring.ai.openrouter.base-url}") String baseUrl) {
        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("HTTP-Referer", "https://github.com/laws-rag");
        headers.add("X-Title", "laws-rag");
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .headers(headers)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("openai/gpt-4o-mini")
                        .temperature(0.7)
                        .build())
                .build();
    }

    /**
     * OpenRouter Embedding模型
     * 使用 OpenAI 兼容的 API 接口访问 OpenRouter
     */
    @Bean
    @Qualifier("openRouterEmbeddingModel")
    public EmbeddingModel openRouterEmbeddingModel(
            @Value("${spring.ai.openrouter.api-key}") String apiKey,
            @Value("${spring.ai.openrouter.base-url}") String baseUrl) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model("openai/text-embedding-3-large")
                .dimensions(3072)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    /**
     * 指定 OpenRouter EmbeddingModel 为默认选择
     * 当有多个 EmbeddingModel Bean 时，VectorStore 等自动配置会使用 @Primary 标记的 Bean
     */
    @Bean
    @Primary
    public TimedEmbeddingModel embeddingModel(
            @Qualifier("openRouterEmbeddingModel") EmbeddingModel openRouterEmbeddingModel) {
        return new TimedEmbeddingModel(openRouterEmbeddingModel);
    }
}
