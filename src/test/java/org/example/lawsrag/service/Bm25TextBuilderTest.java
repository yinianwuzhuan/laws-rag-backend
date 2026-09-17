package org.example.lawsrag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25TextBuilderTest {

    @Test
    void shouldNormalizeChineseArticleNumber() {
        assertThat(Bm25TextBuilder.extractArticleToken("民法典第三百一十六条"))
                .isEqualTo("lawarticle316");
        assertThat(Bm25TextBuilder.extractArticleToken("刑法第一百三十三条之一"))
                .isEqualTo("lawarticle133sub1");
        assertThat(Bm25TextBuilder.extractArticleToken("民法典第1065条"))
                .isEqualTo("lawarticle1065");
    }

    @Test
    void shouldAddSameTokenToDocumentAndQuery() {
        String document = Bm25TextBuilder.documentText("中华人民共和国民法典", "第三百一十六条", "316", "法条正文");
        String query = Bm25TextBuilder.queryText("请定位《中华人民共和国民法典》第三百一十六条");

        assertThat(document).contains("lawarticle316");
        assertThat(document).doesNotContain("lawarticle316 lawarticle316");
        assertThat(query).endsWith("lawarticle316");
        assertThat(query).doesNotContain("lawarticle316 lawarticle316");
        assertThat(Bm25TextBuilder.queryText(query)).isEqualTo(query);
    }
}
