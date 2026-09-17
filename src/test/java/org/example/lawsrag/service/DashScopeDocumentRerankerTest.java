package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.lawsrag.service.DocumentReranker.CandidateDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DashScopeDocumentRerankerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsQwen3RerankResultsBackToCandidates() throws Exception {
        List<CandidateDocument> candidates = candidates();
        var response = objectMapper.readTree("""
                {
                  "object": "list",
                  "results": [
                    {"index": 1, "relevance_score": 0.96},
                    {"index": 0, "relevance_score": 0.72}
                  ],
                  "model": "qwen3-rerank"
                }
                """);

        var results = DashScopeDocumentReranker.parseResults(
                response, candidates, 2);

        assertEquals("第八十七条", results.get(0).candidate().articleNo());
        assertEquals(1, results.get(0).rerankRank());
        assertEquals(0.96, results.get(0).normalizedScore());
        assertEquals("第四十七条", results.get(1).candidate().articleNo());
    }

    @Test
    void rejectsInvalidCandidateIndex() throws Exception {
        var response = objectMapper.readTree("""
                {"results":[{"index":9,"relevance_score":0.9}]}
                """);

        assertThrows(IllegalStateException.class,
                () -> DashScopeDocumentReranker.parseResults(
                        response, candidates(), 2));
    }

    private List<CandidateDocument> candidates() {
        return List.of(
                new CandidateDocument(1, 0.8, "中华人民共和国劳动合同法",
                        "第四十七条", "经济补偿按工作年限计算。", Map.of()),
                new CandidateDocument(2, 0.7, "中华人民共和国劳动合同法",
                        "第八十七条", "违法解除应支付二倍赔偿金。", Map.of()));
    }
}
