package org.example.lawsrag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Qdrant官方Java Client配置；Dense、BM25和RRF共用同一连接。 */
@Configuration
public class QdrantClientConfig {

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(
            @Value("${spring.ai.vectorstore.qdrant.host:localhost}") String host,
            @Value("${spring.ai.vectorstore.qdrant.port:6334}") int port,
            @Value("${spring.ai.vectorstore.qdrant.uses-tls:false}") boolean usesTls,
            @Value("${spring.ai.vectorstore.qdrant.api-key:}") String apiKey) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, usesTls);
        if (apiKey != null && !apiKey.isBlank()) builder.withApiKey(apiKey);
        return new QdrantClient(builder.build());
    }
}
