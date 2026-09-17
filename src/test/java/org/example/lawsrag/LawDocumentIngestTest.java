package org.example.lawsrag;

import org.example.lawsrag.enums.SourceType;
import org.example.lawsrag.service.LawDocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Disabled("手工连接真实Qdrant的旧集成测试，默认禁止运行")
class LawDocumentIngestTest {

    @Autowired
    private LawDocumentService lawDocumentService;

    /**
     * 仅解析前 10 条，打印元数据，不写入数据库
     * 用于人工验证解析结果是否符合规范
     */
    @Test
    void testParseFirst10Articles() throws Exception {
        Path filePath = Path.of("C:/Users/Administrator/IdeaProjects/just-laws/docs/civil-and-commercial/civil-code/05-marriage-and-family.md");

        List<Document> allDocs = lawDocumentService.parseOnly(
                filePath,
                SourceType.LAW,
                "中华人民共和国民法典"
        );

        assertFalse(allDocs.isEmpty(), "解析结果不应为空");

        // 取前 10 条
        List<Document> first10 = allDocs.subList(0, Math.min(10, allDocs.size()));

        System.out.println("=" .repeat(80));
        System.out.println("共解析 " + allDocs.size() + " 条法条，展示前 " + first10.size() + " 条：");
        System.out.println("=" .repeat(80));

        for (int i = 0; i < first10.size(); i++) {
            Document doc = first10.get(i);
            var meta = doc.getMetadata();

            System.out.println();
            System.out.println("--- 第 " + (i + 1) + " 条 ---");
            System.out.println("business_id      : " + meta.get("business_id"));
            System.out.println("source_type      : " + meta.get("source_type"));
            System.out.println("source_type_name : " + meta.get("source_type_name"));
            System.out.println("source_name      : " + meta.get("source_name"));
            System.out.println("book             : " + meta.get("book"));
            System.out.println("part             : " + meta.get("part"));
            System.out.println("chapter          : " + meta.get("chapter"));
            System.out.println("section          : " + meta.get("section"));
            System.out.println("article_no       : " + meta.get("article_no"));
            System.out.println("article_no_arabic: " + meta.get("article_no_arabic"));
            System.out.println("chunk_index      : " + meta.get("chunk_index"));
            System.out.println("char_count       : " + meta.get("char_count"));
            System.out.println("text             : " + doc.getText());
        }

        // 基本断言：验证第一条（第一千零四十条）
        Document first = first10.get(0);
        assertEquals("law_中华人民共和国民法典_1040_0", first.getMetadata().get("business_id"));
        assertNotNull(first.getMetadata().get("content_hash"));
        assertEquals("law", first.getMetadata().get("source_type"));
        assertEquals("法条", first.getMetadata().get("source_type_name"));
        assertEquals("中华人民共和国民法典", first.getMetadata().get("source_name"));
        assertEquals("第五编 婚姻家庭", first.getMetadata().get("part"));
        assertEquals("第一章 一般规定", first.getMetadata().get("chapter"));
        assertEquals("", first.getMetadata().get("section"));
        assertEquals("第一千零四十条", first.getMetadata().get("article_no"));
        assertEquals("1040", first.getMetadata().get("article_no_arabic"));
        assertEquals(0, first.getMetadata().get("chunk_index"));
        assertTrue(first.getText().contains("本编调整因婚姻家庭产生的民事关系"));
    }

    /**
     * 解析并写入向量数据库（正式入库时使用）
     * 默认禁用，验证通过后手动启用
     */
    @Test
    void testIngestToVectorStore() throws Exception {
        Path filePath = Path.of("C:/Users/Administrator/IdeaProjects/just-laws/docs/civil-and-commercial/civil-code/05-marriage-and-family.md");

        int count = lawDocumentService.ingestFromFile(
                filePath,
                SourceType.LAW,
                "中华人民共和国民法典"
        );

        assertTrue(count > 0, "入库数量应大于 0");
        System.out.println("成功入库 " + count + " 条法条");
    }
}

