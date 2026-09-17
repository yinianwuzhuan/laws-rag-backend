package org.example.lawsrag.service;

import org.example.lawsrag.service.EvaluationDatasetService.GoldDocument;
import org.example.lawsrag.service.RetrievalMetrics.CutoffMetric;
import org.example.lawsrag.service.RetrievalMetrics.MetricsResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsTest {

    @Test
    void calculatesRankAndCutoffMetricsForSingleGoldDocument() {
        List<GoldDocument> gold = List.of(
                new GoldDocument("law_general_principles_188_0", "中华人民共和国民法典",
                        "第一百八十八条", "188", 2)
        );

        MetricsResult result = RetrievalMetrics.calculate(gold, List.of(
                "law_general_principles_189_0",
                "law_general_principles_188_0",
                "law_general_principles_195_0",
                "law_general_principles_194_0",
                "law_general_principles_196_0"
        ), 5);

        assertEquals(2, result.firstRelevantRank());
        assertEquals(0.5, result.reciprocalRank());
        CutoffMetric at1 = metricAt(result, 1);
        CutoffMetric at3 = metricAt(result, 3);
        assertFalse(at1.hit());
        assertTrue(at3.hit());
        assertEquals(1.0, at3.recall());
        assertEquals(0.333333, at3.precision());
    }

    @Test
    void usesGradedRelevanceForNdcgAndRecallForMultipleGoldDocuments() {
        List<GoldDocument> gold = List.of(
                new GoldDocument("core", "民法典", "第五百零六条", "506", 2),
                new GoldDocument("support", "民法典", "第四百九十七条", "497", 1)
        );

        MetricsResult result = RetrievalMetrics.calculate(gold,
                List.of("noise", "support", "core", "noise-2", "noise-3"), 5);

        CutoffMetric at3 = metricAt(result, 3);
        assertEquals(2, at3.matchedCount());
        assertEquals(1.0, at3.recall());
        assertEquals(0.586883, at3.ndcg());
    }

    @Test
    void duplicateRetrievedPointDoesNotCreditTheSameGoldDocumentTwice() {
        List<GoldDocument> gold = List.of(
                new GoldDocument("core", "民法典", "第二百一十五条", "215", 2)
        );

        MetricsResult result = RetrievalMetrics.calculate(
                gold, List.of("core", "core", "noise"), 3);

        CutoffMetric at3 = metricAt(result, 3);
        assertEquals(1, at3.matchedCount());
        assertEquals(1.0, at3.ndcg());
    }

    private CutoffMetric metricAt(MetricsResult result, int cutoff) {
        return result.cutoffMetrics().stream()
                .filter(item -> item.cutoff() == cutoff)
                .findFirst()
                .orElseThrow();
    }
}
