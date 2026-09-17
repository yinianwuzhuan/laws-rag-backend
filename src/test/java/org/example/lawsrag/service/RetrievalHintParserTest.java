package org.example.lawsrag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalHintParserTest {

    private final RetrievalHintParser parser = new RetrievalHintParser();

    @Test
    void extractsExplicitLawNamesAndMultipleArticleNumbersWithoutFilters() {
        var plan = parser.parse(
                "民法典第四条和第五条分别是什么？",
                "《中华人民共和国民法典》第四条和第五条分别规定了什么？");

        assertThat(plan.lawNames()).containsExactly("中华人民共和国民法典");
        assertThat(plan.articleNumbers()).containsExactly("4", "5");
        assertThat(plan.articleTokens()).containsExactly("lawarticle4", "lawarticle5");
        assertThat(plan.enhancedBm25Query())
                .contains("中华人民共和国民法典", "lawarticle4", "lawarticle5");
    }

    @Test
    void extractsColloquialLegalConcepts() {
        var plan = parser.parse(
                "老板三个月不发工资，我是不是不用提前30天就能直接走？",
                "用人单位未支付工资时劳动者能否立即解除劳动合同？");

        assertThat(plan.concepts()).contains(
                "拖欠劳动报酬", "劳动者解除劳动合同", "提前三十日通知");
        assertThat(plan.rerankerContext()).contains("规则识别的法律概念");
    }

    @Test
    void prefersSpecificLawAliasOverContainedGenericAlias() {
        var plan = parser.parse("劳动争议调解仲裁法第九条是什么？", "劳动仲裁如何处理？");

        assertThat(plan.lawNames())
                .containsExactly("中华人民共和国劳动争议调解仲裁法")
                .doesNotContain("中华人民共和国仲裁法");
    }
}
