package org.example.lawsrag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 法律条文向量搜索效果验证
 * 基于已入库的婚姻家庭编 79 条法条
 */
@SpringBootTest
class LawSearchTest {

    @Autowired
    private VectorStore vectorStore;

    // ==================== 1. 不带元数据过滤的纯语义搜索 ====================

    @Test
    void testSearchWithoutFilter_离婚条件() {
        String query = "什么情况下可以离婚";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        printResults("纯语义搜索 - " + query, results);
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchWithoutFilter_结婚年龄() {
        String query = "法定结婚年龄是多少岁";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        printResults("纯语义搜索 - " + query, results);
        assertFalse(results.isEmpty());

        // 期望第一千零四十七条能出现在结果中
        boolean hasTarget = results.stream()
                .anyMatch(doc -> doc.getText().contains("二十二周岁") || doc.getText().contains("二十周岁"));
        assertTrue(hasTarget, "应能搜索到结婚年龄相关法条");
    }

    @Test
    void testSearchWithoutFilter_夫妻共同财产() {
        String query = "夫妻共同财产包括哪些";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        printResults("纯语义搜索 - " + query, results);
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchWithoutFilter_子女抚养() {
        String query = "离婚后孩子归谁抚养";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        printResults("纯语义搜索 - " + query, results);
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchWithoutFilter_收养条件() {
        String query = "收养孩子需要什么条件";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        printResults("纯语义搜索 - " + query, results);
        assertFalse(results.isEmpty());
    }

    // ==================== 2. 带元数据过滤的搜索 ====================

    @Test
    void testSearchWithFilter_按章过滤_结婚() {
        String query = "婚姻无效的情形";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression("chapter == '第二章 结婚'")
                .build());

        printResults("元数据过滤(第二章 结婚) - " + query, results);
        assertFalse(results.isEmpty());

        // 验证所有结果都属于第二章
        assertTrue(results.stream().allMatch(doc ->
                        "第二章 结婚".equals(doc.getMetadata().get("chapter"))),
                "所有结果应属于第二章 结婚");
    }

    @Test
    void testSearchWithFilter_按章过滤_离婚() {
        String query = "财产怎么分割";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression("chapter == '第四章 离婚'")
                .build());

        printResults("元数据过滤(第四章 离婚) - " + query, results);
        assertFalse(results.isEmpty());

        assertTrue(results.stream().allMatch(doc ->
                        "第四章 离婚".equals(doc.getMetadata().get("chapter"))),
                "所有结果应属于第四章 离婚");
    }

    @Test
    void testSearchWithFilter_按节过滤_夫妻关系() {
        String query = "夫妻债务";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression("section == '第一节 夫妻关系'")
                .build());

        printResults("元数据过滤(第一节 夫妻关系) - " + query, results);
        assertFalse(results.isEmpty());

        assertTrue(results.stream().allMatch(doc ->
                        "第一节 夫妻关系".equals(doc.getMetadata().get("section"))),
                "所有结果应属于第一节 夫妻关系");
    }

    @Test
    void testSearchWithFilter_按条号精确查找() {
        String query = "结婚自愿";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(1)
                .filterExpression("article_no_arabic == '1046'")
                .build());

        printResults("元数据过滤(精确条号 1046) - " + query, results);
        assertFalse(results.isEmpty());
        assertEquals("1046", results.get(0).getMetadata().get("article_no_arabic"));
    }

    @Test
    void testSearchWithFilter_按source_type过滤() {
        String query = "家庭暴力";
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression("source_type == 'law'")
                .build());

        printResults("元数据过滤(source_type=law) - " + query, results);
        assertFalse(results.isEmpty());

        assertTrue(results.stream().allMatch(doc ->
                        "law".equals(doc.getMetadata().get("source_type"))),
                "所有结果 source_type 应为 law");
    }

    // ==================== 对比测试：同一查询，有无过滤的差异 ====================

    @Test
    void testCompare_财产分割_有无过滤对比() {
        String query = "财产如何分割";

        // 不带过滤
        List<Document> allResults = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .build());

        // 仅限离婚章
        List<Document> divorceResults = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression("chapter == '第四章 离婚'")
                .build());

        System.out.println("=" .repeat(80));
        System.out.println("【对比测试】查询: " + query);
        System.out.println("=" .repeat(80));

        System.out.println("\n>>> 无过滤 (全局搜索):");
        for (int i = 0; i < allResults.size(); i++) {
            Document doc = allResults.get(i);
            System.out.printf("  [%d] %s | %s | %s%n", i + 1,
                    doc.getMetadata().get("article_no"),
                    doc.getMetadata().get("chapter"),
                    truncate(doc.getText(), 60));
        }

        System.out.println("\n>>> 过滤: chapter='第四章 离婚':");
        for (int i = 0; i < divorceResults.size(); i++) {
            Document doc = divorceResults.get(i);
            System.out.printf("  [%d] %s | %s | %s%n", i + 1,
                    doc.getMetadata().get("article_no"),
                    doc.getMetadata().get("chapter"),
                    truncate(doc.getText(), 60));
        }
    }

    // ==================== 辅助方法 ====================

    private void printResults(String title, List<Document> results) {
        System.out.println();
        System.out.println("=" .repeat(80));
        System.out.println("【" + title + "】 命中 " + results.size() + " 条");
        System.out.println("=" .repeat(80));

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            var meta = doc.getMetadata();
            System.out.printf("[%d] %s (第%s条) | %s > %s > %s%n",
                    i + 1,
                    meta.get("article_no"),
                    meta.get("article_no_arabic"),
                    meta.get("part"),
                    meta.get("chapter"),
                    meta.get("section"));
            System.out.printf("    正文: %s%n", truncate(doc.getText(), 80));
            System.out.println();
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String oneLine = text.replace("\n", " ");
        return oneLine.length() > maxLen ? oneLine.substring(0, maxLen) + "..." : oneLine;
    }
}

