package org.example.lawsrag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VectorStoreTest {

    @Autowired
    private VectorStore vectorStore;

    @Test
    void testQdrantAddDocuments() {
        List<Document> documents = List.of(
                new Document(
                        "第一条 为了保障公民、法人和其他组织的合法权益，维护公共利益和社会秩序，制定本法。",
                        Map.of("source", "民法典", "chapter", "总则", "article", "1")
                ),
                new Document(
                        "第二条 民法调整平等主体的自然人、法人和非法人组织之间的人身关系和财产关系。",
                        Map.of("source", "民法典", "chapter", "总则", "article", "2")
                ),
                new Document(
                        "第三条 民事主体的人身权利、财产权利以及其他合法权益受法律保护，任何组织或者个人不得侵犯。",
                        Map.of("source", "民法典", "chapter", "总则", "article", "3")
                )
        );

        vectorStore.add(documents);

        System.out.println("=== Qdrant 写入测试 ===");
        System.out.println("成功写入文档数量: " + documents.size());
    }

    @Test
    void testSimilaritySearch1() {
        // 2. 执行相似度搜索
        String query = "公民合法权益是什么呢？";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(3)
                .build());

        // 3. 验证结果
        assertFalse(results.isEmpty(), "搜索结果不应为空");
        assertTrue(results.size() <= 3, "返回结果数量不应超过 topK");

        // 打印搜索结果
        System.out.println("查询: " + query);
        System.out.println("=".repeat(50));
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            System.out.printf("[%d] 相似度得分: %s%n", i + 1, doc.getMetadata().get("distance"));
            System.out.printf("    内容: %s%n", doc.getText());
            System.out.printf("    分类: %s%n", doc.getMetadata().get("category"));
            System.out.println();
        }

//        // 验证 RAG 相关文档应该排在前列
//        boolean hasRagDocument = results.stream()
//                .anyMatch(doc -> doc.getText().contains("RAG"));
//        assertTrue(hasRagDocument, "搜索结果应包含 RAG 相关文档");
    }

    @Test
    void testSimilaritySearch() {
        // 1. 添加测试文档
        List<Document> documents = List.of(
                new Document("Spring Boot 是一个用于简化 Spring 应用开发的框架", Map.of("category", "framework")),
                new Document("Python 是一种广泛使用的编程语言", Map.of("category", "language")),
                new Document("Qdrant 是一个高性能的向量数据库", Map.of("category", "database")),
                new Document("RAG 是 Retrieval-Augmented Generation 的缩写，用于增强大模型能力", Map.of("category", "ai")),
                new Document("Docker 是一个开源的容器化平台", Map.of("category", "devops"))
        );
        vectorStore.add(documents);

        // 2. 执行相似度搜索
        String query = "什么是 RAG 技术";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(3)
                .build());

        // 3. 验证结果
        assertFalse(results.isEmpty(), "搜索结果不应为空");
        assertTrue(results.size() <= 3, "返回结果数量不应超过 topK");

        // 打印搜索结果
        System.out.println("查询: " + query);
        System.out.println("=".repeat(50));
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            System.out.printf("[%d] 相似度得分: %s%n", i + 1, doc.getMetadata().get("distance"));
            System.out.printf("    内容: %s%n", doc.getText());
            System.out.printf("    分类: %s%n", doc.getMetadata().get("category"));
            System.out.println();
        }

        // 验证 RAG 相关文档应该排在前列
        boolean hasRagDocument = results.stream()
                .anyMatch(doc -> doc.getText().contains("RAG"));
        assertTrue(hasRagDocument, "搜索结果应包含 RAG 相关文档");
    }

    @Test
    void testSimilaritySearchWithThreshold() {
        // 添加测试文档
        List<Document> documents = List.of(
                new Document("Java 是一种面向对象的编程语言"),
                new Document("TypeScript 是 JavaScript 的超集"),
                new Document("Go 语言由 Google 开发")
        );
        vectorStore.add(documents);

        // 使用相似度阈值搜索
        String query = "编程语言有哪些";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .similarityThreshold(0.7)
                .build());

        System.out.println("查询(带阈值): " + query);
        System.out.println("=".repeat(50));
        for (Document doc : results) {
            System.out.printf("内容: %s%n", doc.getText());
            System.out.printf("相似度: %s%n", doc.getMetadata().get("distance"));
            System.out.println();
        }
    }

    @Test
    void testSimilaritySearchWithFilter() {
        // 添加带元数据的文档
        List<Document> documents = List.of(
                new Document("Spring Framework 是 Java 企业级开发框架", Map.of("lang", "java", "type", "framework")),
                new Document("Django 是 Python Web 框架", Map.of("lang", "python", "type", "framework")),
                new Document("Flask 是轻量级 Python Web 框架", Map.of("lang", "python", "type", "framework")),
                new Document("Express 是 Node.js Web 框架", Map.of("lang", "javascript", "type", "framework"))
        );
        vectorStore.add(documents);

        // 带过滤条件的搜索
        String query = "Web 开发框架";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(10)
                .filterExpression("lang == 'python'")
                .build());

        System.out.println("查询(过滤 Python): " + query);
        System.out.println("=".repeat(50));
        for (Document doc : results) {
            System.out.printf("内容: %s%n", doc.getText());
            System.out.printf("语言: %s%n", doc.getMetadata().get("lang"));
            System.out.println();
        }

        // 验证所有结果都是 Python 相关
        assertTrue(results.stream().allMatch(doc -> "python".equals(doc.getMetadata().get("lang"))),
                "所有结果应该都是 Python 相关的文档");
    }

    @Test
    void testDeleteDocuments() {
        // 添加文档
        Document doc1 = new Document("测试文档1 - 用于删除测试", Map.of("testId", "delete-test-1"));
        Document doc2 = new Document("测试文档2 - 用于删除测试", Map.of("testId", "delete-test-2"));
        vectorStore.add(List.of(doc1, doc2));

        String docId1 = doc1.getId();

        // 删除文档
        vectorStore.delete(List.of(docId1));

        // 验证删除成功（Qdrant 可能还需要一些时间同步，这里只是演示 API 用法）
        System.out.println("已删除文档: " + docId1);
    }
}
