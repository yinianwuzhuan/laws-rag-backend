package org.example.lawsrag.service;

import org.example.lawsrag.service.DocumentReranker.CandidateDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 手工运行的真实模型测试。模型目录不进入Git，因此默认跳过。
 */
@EnabledIfEnvironmentVariable(named = "RUN_RERANKER_MODEL_TEST", matches = "true")
class OnnxCrossEncoderRerankerModelTest {

    @Test
    void ranksRelevantLawAboveUnrelatedLaw() {
        String modelPath = System.getenv().getOrDefault("RERANKER_MODEL_PATH", "models/reranker");
        OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(
                true, modelPath, 512, 10, 8, 1, true);
        reranker.initialize();
        try {
            List<CandidateDocument> candidates = List.of(
                    candidate(1, "中华人民共和国刑法", "第二百六十四条",
                            "盗窃公私财物，数额较大的，或者多次盗窃、入户盗窃、携带凶器盗窃、扒窃的，依法追究刑事责任。"),
                    candidate(2, "中华人民共和国劳动合同法", "第八十二条",
                            "用人单位自用工之日起超过一个月不满一年未与劳动者订立书面劳动合同的，应当向劳动者每月支付二倍的工资。")
            );

            var results = reranker.rerank("公司工作半年一直没有签订书面劳动合同怎么办？", candidates, 2);
            results.forEach(item -> System.out.printf(
                    "rerankRank=%d vectorRank=%d score=%.6f article=%s%n",
                    item.rerankRank(), item.candidate().vectorRank(),
                    item.normalizedScore(), item.candidate().articleNo()));

            assertEquals("第八十二条", results.getFirst().candidate().articleNo());
            assertEquals(2, results.getFirst().candidate().vectorRank());
        } finally {
            reranker.closeResources();
        }
    }

    private CandidateDocument candidate(
            int vectorRank, String sourceName, String articleNo, String text) {
        return new CandidateDocument(
                vectorRank, 1.0 / vectorRank, sourceName, articleNo, text, Map.of("id", vectorRank));
    }
}
