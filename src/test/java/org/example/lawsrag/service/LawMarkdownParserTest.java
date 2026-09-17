package org.example.lawsrag.service;

import org.example.lawsrag.enums.SourceType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LawMarkdownParserTest {

    @Test
    void createsCanonicalBusinessIdHashAndStablePointId() throws Exception {
        String markdown = """
                # 第五编 婚姻家庭
                ## 第一章 一般规定
                **第一千零四十七条**　结婚年龄，男不得早于二十二周岁，女不得早于二十周岁。
                """;
        var parser = new LawMarkdownParser(SourceType.LAW, "中华人民共和国民法典");

        var document = parser.parse(new ByteArrayInputStream(
                markdown.getBytes(StandardCharsets.UTF_8))).getFirst();

        assertEquals("law_中华人民共和国民法典_1047_0",
                document.getMetadata().get("business_id"));
        assertNotNull(document.getMetadata().get("content_hash"));
        assertEquals("中华人民共和国民法典", document.getMetadata().get("source_name"));
        assertEquals("1047", document.getMetadata().get("article_no_arabic"));
        assertEquals(0, document.getMetadata().get("chunk_index"));
        assertEquals(org.example.lawsrag.util.DocumentIdentity.pointId(
                "law_中华人民共和国民法典_1047_0"), document.getId());
    }

    @Test
    void parsesPlainCriminalLawInsertedArticlesAsIndependentDocuments() throws Exception {
        String markdown = """
                ## 第二章 危害公共安全罪
                **第一百二十条**　组织、领导恐怖活动组织的，依法处罚。
                第一百二十条之一　资助恐怖活动组织的，依法处罚。
                第一百二十条之二　为实施恐怖活动准备工具的，依法处罚。
                **第一百二十一条**　以暴力方法劫持航空器的，依法处罚。
                """;
        var parser = new LawMarkdownParser(SourceType.LAW, "中华人民共和国刑法");

        var documents = parser.parse(new ByteArrayInputStream(
                markdown.getBytes(StandardCharsets.UTF_8)));

        assertEquals(4, documents.size());
        assertEquals("law_中华人民共和国刑法_120_0",
                documents.get(0).getMetadata().get("business_id"));
        assertEquals("law_中华人民共和国刑法_120-1_0",
                documents.get(1).getMetadata().get("business_id"));
        assertEquals("第一百二十条之一",
                documents.get(1).getMetadata().get("article_no"));
        assertEquals("120-2", documents.get(2).getMetadata().get("article_no_arabic"));
        assertEquals("law_中华人民共和国刑法_121_0",
                documents.get(3).getMetadata().get("business_id"));
    }

    @Test
    void preservesOfficialMetadataPreambleAndExistingArticleIdentity() throws Exception {
        String markdown = """
                ---
                documentType: administrative-regulations
                documentTypeName: 行政法规
                officialId: abc-123
                officialCategory: 行政法规
                issuer: 国务院
                promulgatedOn: 2008-09-18
                effectiveFrom: 2008-09-18
                status: 有效
                sourceUrl: https://example.test/abc-123
                ---
                # 中华人民共和国劳动合同法实施条例
                为了贯彻实施《中华人民共和国劳动合同法》，制定本条例。
                ## 第三章 劳动合同的解除和终止
                **第二十七条**　劳动合同法第四十七条规定的经济补偿的月工资按照劳动者应得工资计算。
                """;
        var parser = new LawMarkdownParser(SourceType.LAW, "中华人民共和国劳动合同法实施条例");

        var documents = parser.parse(new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, documents.size());
        assertEquals("preamble", documents.getFirst().getMetadata().get("chunk_type"));
        var article = documents.get(1);
        assertEquals("law_中华人民共和国劳动合同法实施条例_27_0",
                article.getMetadata().get("business_id"));
        assertEquals("administrative-regulations", article.getMetadata().get("document_type"));
        assertEquals("abc-123", article.getMetadata().get("official_id"));
        assertEquals("国务院", article.getMetadata().get("issuer"));
        assertEquals("article", article.getMetadata().get("chunk_type"));
        assertEquals("27", article.getMetadata().get("article_number"));
        assertFalse(article.getMetadata().containsValue(null));
    }

    @Test
    void chunksOfficialDocumentWithoutArticleNumbersInsteadOfDroppingIt() throws Exception {
        String longParagraph = "本办法用于规范公告性文件。".repeat(100);
        String markdown = """
                ---
                documentType: administrative-regulations
                officialId: no-article-1
                issuer: 政务院
                status: 有效
                ---
                # 政务院关于发表公报及公告性文件的办法
                ## 一、关于法令者
                %s
                """.formatted(longParagraph);
        var parser = new LawMarkdownParser(SourceType.LAW, "政务院关于发表公报及公告性文件的办法");

        var documents = parser.parse(new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));

        assertFalse(documents.isEmpty());
        assertEquals("paragraph", documents.getFirst().getMetadata().get("chunk_type"));
        assertEquals("administrative-regulations", documents.getFirst().getMetadata().get("document_type"));
        assertEquals("no-article-1", documents.getFirst().getMetadata().get("document_id"));
        assertFalse(documents.getFirst().getText().isBlank());
    }

    @Test
    void preservesAttachmentAfterLastArticle() throws Exception {
        String markdown = """
                ---
                documentType: legal-decisions
                officialId: decision-1
                ---
                # 某项决定
                **第一条**　决定正文。
                ## 附件：配套名单
                第一项配套内容
                """;
        var documents = new LawMarkdownParser(SourceType.LAW, "某项决定")
                .parse(new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, documents.size());
        assertEquals("article", documents.getFirst().getMetadata().get("chunk_type"));
        assertEquals("attachment", documents.get(1).getMetadata().get("chunk_type"));
        assertEquals("legal-decisions", documents.get(1).getMetadata().get("document_type"));
    }

    @Test
    void assignsStableVariantIdentityWhenCompositeOfficialFileRestartsArticleNumbers() throws Exception {
        String markdown = """
                ---
                documentType: supreme-court-interpretations
                officialId: composite-1
                ---
                # 关于修改某规定的决定
                **第一条**　增加一款修改内容。
                **第一条**　重新公布后的完整条文。
                """;
        var documents = new LawMarkdownParser(SourceType.JUDICIAL_INTERPRETATION, "关于修改某规定的决定")
                .parse(new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));

        assertEquals("judicial_interpretation_关于修改某规定的决定_1_0",
                documents.getFirst().getMetadata().get("business_id"));
        assertEquals("judicial_interpretation_关于修改某规定的决定_1-variant-1_0",
                documents.get(1).getMetadata().get("business_id"));
        assertEquals("article_variant", documents.get(1).getMetadata().get("chunk_type"));
    }
}
