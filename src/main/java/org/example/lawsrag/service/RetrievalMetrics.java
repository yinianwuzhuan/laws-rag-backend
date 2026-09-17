package org.example.lawsrag.service;

import org.example.lawsrag.service.EvaluationDatasetService.GoldDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    public static MetricsResult calculate(List<GoldDocument> goldDocuments,
                                          List<String> retrievedBusinessIds,
                                          int topK) {
        Map<String, Integer> relevanceById = new HashMap<>();
        for (GoldDocument gold : goldDocuments) {
            relevanceById.put(gold.businessId(), gold.relevance());
        }

        Integer firstRelevantRank = null;
        for (int i = 0; i < retrievedBusinessIds.size(); i++) {
            if (relevanceById.containsKey(retrievedBusinessIds.get(i))) {
                firstRelevantRank = i + 1;
                break;
            }
        }

        List<CutoffMetric> cutoffMetrics = new ArrayList<>();
        for (int cutoff : cutoffs(topK)) {
            Set<String> matchedGold = new HashSet<>();
            double dcg = 0.0;
            int end = Math.min(cutoff, retrievedBusinessIds.size());
            for (int i = 0; i < end; i++) {
                String businessId = retrievedBusinessIds.get(i);
                Integer relevance = relevanceById.get(businessId);
                if (relevance != null && matchedGold.add(businessId)) {
                    dcg += gain(relevance) / log2(i + 2);
                }
            }

            int matchedCount = matchedGold.size();
            double recall = goldDocuments.isEmpty() ? 0.0 : (double) matchedCount / goldDocuments.size();
            double precision = (double) matchedCount / cutoff;
            double idcg = idealDcg(goldDocuments, cutoff);
            double ndcg = idcg == 0.0 ? 0.0 : dcg / idcg;
            cutoffMetrics.add(new CutoffMetric(cutoff, matchedCount > 0, matchedCount,
                    round(recall), round(precision), round(ndcg)));
        }

        double reciprocalRank = firstRelevantRank == null ? 0.0 : 1.0 / firstRelevantRank;
        return new MetricsResult(firstRelevantRank, round(reciprocalRank), List.copyOf(cutoffMetrics));
    }

    public static List<Integer> cutoffs(int topK) {
        LinkedHashSet<Integer> cutoffs = new LinkedHashSet<>();
        if (topK >= 1) cutoffs.add(1);
        if (topK >= 3) cutoffs.add(3);
        if (topK >= 5) cutoffs.add(5);
        cutoffs.add(topK);
        return List.copyOf(cutoffs);
    }

    private static double idealDcg(List<GoldDocument> goldDocuments, int cutoff) {
        List<Integer> relevance = goldDocuments.stream()
                .map(GoldDocument::relevance)
                .sorted(Comparator.reverseOrder())
                .limit(cutoff)
                .toList();
        double idcg = 0.0;
        for (int i = 0; i < relevance.size(); i++) {
            idcg += gain(relevance.get(i)) / log2(i + 2);
        }
        return idcg;
    }

    private static double gain(int relevance) {
        return Math.pow(2, relevance) - 1;
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }

    public static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    public record MetricsResult(
            Integer firstRelevantRank,
            double reciprocalRank,
            List<CutoffMetric> cutoffMetrics
    ) {}

    public record CutoffMetric(
            int cutoff,
            boolean hit,
            int matchedCount,
            double recall,
            double precision,
            double ndcg
    ) {}
}
