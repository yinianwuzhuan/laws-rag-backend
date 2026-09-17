package org.example.lawsrag.service;

import org.example.lawsrag.service.DocumentReranker.CandidateDocument;
import org.example.lawsrag.service.DocumentReranker.RerankedDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LawSearchServiceTest {

    @Test
    void keepsVectorOrderWhenRerankIsDisabled() {
        QdrantHybridStore vectorStore = vectorStoreWithThreeDocuments();
        DocumentReranker reranker = mock(DocumentReranker.class);
        LawSearchService service = new LawSearchService(
                vectorStore, reranker, timingModel(), unchangedRewriter(), hintParser());

        var results = service.search("劳动合同", 10, 2, Map.of(), false);

        assertEquals(2, results.size());
        assertEquals(1, results.getFirst().get("rank"));
        assertEquals(1, results.getFirst().get("vector_rank"));
        assertNull(results.getFirst().get("rerank_rank"));
        verifyNoInteractions(reranker);
    }

    @Test
    void removesNullDisplayPlaceholdersWhenCreatingSpringAiDocuments() {
        QdrantHybridStore vectorStore = vectorStoreWithThreeDocuments();
        DocumentReranker reranker = mock(DocumentReranker.class);
        LawSearchService service = new LawSearchService(
                vectorStore, reranker, timingModel(), unchangedRewriter(), hintParser());

        var documents = service.searchDocuments(
                "劳动合同", 10, 2, Map.of(), RetrievalMode.DENSE, false);

        assertEquals(2, documents.size());
        assertEquals("law_1", documents.getFirst().getMetadata().get("business_id"));
        assertFalse(documents.getFirst().getMetadata().containsKey("rerank_rank"));
        assertFalse(documents.getFirst().getMetadata().containsKey("rerank_score"));
        assertFalse(documents.getFirst().getMetadata().containsKey("rerank_raw_score"));
        assertTrue(documents.getFirst().getMetadata().values().stream()
                .noneMatch(java.util.Objects::isNull));
    }

    @Test
    void preservesVectorMetadataAndUsesRerankerOrder() {
        QdrantHybridStore vectorStore = vectorStoreWithThreeDocuments();
        DocumentReranker reranker = new ReverseReranker();
        LawSearchService service = new LawSearchService(
                vectorStore, reranker, timingModel(), unchangedRewriter(), hintParser());

        var results = service.search("劳动合同", 3, 2, Map.of(), true);

        assertEquals(2, results.size());
        assertEquals("law_3", results.getFirst().get("business_id"));
        assertEquals(3, results.getFirst().get("vector_rank"));
        assertEquals(1, results.getFirst().get("rerank_rank"));
        assertEquals(0.9, (double) results.getFirst().get("rerank_score"), 0.0001);
    }

    @Test
    void sendsFinalTopKToRerankerAndKeepsRetrievalCandidates() {
        QdrantHybridStore vectorStore = vectorStoreWithThreeDocuments();
        ReverseReranker reranker = new ReverseReranker();
        LawSearchService service = new LawSearchService(
                vectorStore, reranker, timingModel(), unchangedRewriter(), hintParser());

        var execution = service.searchWithTrace("劳动合同", 3, 2, Map.of(), true);

        assertEquals(3, execution.vectorCandidates().size());
        assertEquals("law_1", execution.vectorCandidates().getFirst().get("business_id"));
        assertEquals(2, reranker.lastFinalTopK);
        assertEquals(2, execution.rerankedCandidates().size());
        assertEquals("law_3", execution.rerankedCandidates().getFirst().get("business_id"));
        assertEquals(2, execution.finalResults().size());
    }

    @Test
    void emitsProgressAtEachRealPipelineBoundary() {
        LawSearchService service = new LawSearchService(
                vectorStoreWithThreeDocuments(), new ReverseReranker(),
                timingModel(), unchangedRewriter(), hintParser());
        List<String> events = new ArrayList<>();
        LawSearchService.SearchProgressListener listener =
                new LawSearchService.SearchProgressListener() {
                    @Override
                    public void onQueryRewriteStarted(String query, boolean enabled) {
                        events.add("rewrite-start");
                    }

                    @Override
                    public void onQueryRewriteCompleted(QueryRewriter.RewriteResult rewrite) {
                        events.add("rewrite-complete");
                    }

                    @Override
                    public void onKeywordAnalysisCompleted(
                            RetrievalHintParser.RetrievalHintPlan hints) {
                        events.add("keywords-complete");
                    }

                    @Override
                    public void onHybridRetrievalStarted(
                            RetrievalMode mode, boolean rewritten) {
                        events.add("retrieval-start");
                    }

                    @Override
                    public void onHybridRetrievalCompleted(
                            LawSearchService.SearchChannelSummary summary) {
                        events.add("retrieval-complete");
                    }

                    @Override
                    public void onRerankStarted(int candidateCount) {
                        events.add("rerank-start");
                    }

                    @Override
                    public void onRerankCompleted(
                            int candidateCount, int finalCount, long durationMs) {
                        events.add("rerank-complete");
                    }
                };

        service.searchWithTrace("劳动合同", 3, 2, Map.of(),
                RetrievalMode.DENSE, true, false, listener);

        assertEquals(List.of(
                "rewrite-start",
                "rewrite-complete",
                "keywords-complete",
                "retrieval-start",
                "retrieval-complete",
                "rerank-start",
                "rerank-complete"), events);
    }

    @Test
    void usesSingleQdrantFourChannelFusionForRewrittenHybridQuery() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        Map<String, Object> shared = document("law_shared", "第一条", 0.8, 2);
        Map<String, Object> originalOnly = document("law_original", "第二条", 0.9, 1);
        Map<String, Object> rewrittenOnly = document("law_rewritten", "第三条", 0.95, 1);
        String enhancedBm25Query = hintParser().parse(
                "老板欠我钱，我能直接走吗",
                "用人单位拖欠劳动报酬时能否立即解除劳动合同")
                .enhancedBm25Query();
        when(store.searchWithRewrite(
                "老板欠我钱，我能直接走吗",
                "用人单位拖欠劳动报酬时能否立即解除劳动合同",
                enhancedBm25Query,
                3, Map.of(), RetrievalMode.HYBRID))
                .thenReturn(new QdrantHybridStore.RewrittenRetrievalChannels(
                        List.of(originalOnly, shared),
                        List.of(shared),
                        List.of(rewrittenOnly, shared),
                        List.of(shared),
                        List.of(shared, originalOnly, rewrittenOnly)));
        QueryRewriter rewriter = new QueryRewriter() {
            @Override
            public RewriteResult rewrite(String originalQuery) {
                return new RewriteResult(originalQuery,
                        "用人单位拖欠劳动报酬时能否立即解除劳动合同", true, false, 12);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        LawSearchService service = new LawSearchService(
                store, mock(DocumentReranker.class), timingModel(), rewriter, hintParser());

        var execution = service.searchWithTrace(
                "老板欠我钱，我能直接走吗", 3, 3, Map.of(),
                RetrievalMode.HYBRID, false, true);

        assertEquals("law_shared", execution.finalResults().getFirst().get("business_id"));
        assertEquals(2, execution.originalDenseCandidates().size());
        assertEquals(1, execution.originalBm25Candidates().size());
        assertEquals(2, execution.rewrittenDenseCandidates().size());
        assertEquals(1, execution.rewrittenBm25Candidates().size());
        assertTrue(execution.rewrite().changed());
        verify(store).searchWithRewrite(
                "老板欠我钱，我能直接走吗",
                "用人单位拖欠劳动报酬时能否立即解除劳动合同",
                enhancedBm25Query,
                3, Map.of(), RetrievalMode.HYBRID);
    }

    @Test
    void sendsRuleBasedHintsToBm25AndRerankerWithoutCreatingFilters() {
        String query = "劳动合同法第三十八条规定老板不发工资时我能直接走吗？";
        RetrievalHintParser parser = hintParser();
        String enhanced = parser.parse(query, query).enhancedBm25Query();
        List<Map<String, Object>> documents = List.of(
                document("law_1", "第三十八条", 0.9, 1),
                document("law_2", "第三十二条", 0.8, 2),
                document("law_3", "第三十条", 0.7, 3));
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        when(store.search(query, enhanced, 3, Map.of(), RetrievalMode.DENSE))
                .thenReturn(new QdrantHybridStore.RetrievalChannels(
                        documents, List.of(), List.of(), documents));
        ReverseReranker reranker = new ReverseReranker();
        LawSearchService service = new LawSearchService(
                store, reranker, timingModel(), unchangedRewriter(), parser);

        var execution = service.searchWithTrace(
                query, 3, 2, Map.of(), RetrievalMode.DENSE, true, false);

        assertTrue(execution.retrievalHints().lawNames().contains("中华人民共和国劳动合同法"));
        assertTrue(execution.retrievalHints().articleTokens().contains("lawarticle38"));
        assertTrue(enhanced.contains("lawarticle38"));
        assertTrue(reranker.lastQuery.contains("规则检索提示"));
        assertTrue(reranker.lastQuery.contains("拖欠劳动报酬"));
        verify(store).search(query, enhanced, 3, Map.of(), RetrievalMode.DENSE);
    }

    @Test
    void addsOverallQuestionOnlyToRerankerContext() {
        QdrantHybridStore store = vectorStoreWithThreeDocuments();
        ReverseReranker reranker = new ReverseReranker();
        LawSearchService service = new LawSearchService(
                store, reranker, timingModel(), unchangedRewriter(), hintParser());

        service.searchWithTrace("劳动合同", 3, 2, Map.of(),
                RetrievalMode.DENSE, true, false,
                LawSearchService.SearchProgressListener.noop(),
                "公司解除合同后，补偿年限和月工资应如何计算？");

        assertTrue(reranker.lastQuery.contains("整体咨询背景"));
        assertTrue(reranker.lastQuery.contains("补偿年限和月工资"));
        verify(store).search("劳动合同", "劳动合同", 3, Map.of(), RetrievalMode.DENSE);
    }

    private QdrantHybridStore vectorStoreWithThreeDocuments() {
        QdrantHybridStore vectorStore = mock(QdrantHybridStore.class);
        List<Map<String, Object>> documents = List.of(
                document("law_1", "第一条", 0.9, 1),
                document("law_2", "第二条", 0.8, 2),
                document("law_3", "第三条", 0.7, 3)
        );
        when(vectorStore.search("劳动合同", "劳动合同", 2, Map.of(), RetrievalMode.DENSE))
                .thenReturn(new QdrantHybridStore.RetrievalChannels(
                        documents.subList(0, 2), List.of(), List.of(), documents.subList(0, 2)));
        when(vectorStore.search("劳动合同", "劳动合同", 3, Map.of(), RetrievalMode.DENSE))
                .thenReturn(new QdrantHybridStore.RetrievalChannels(
                        documents, List.of(), List.of(), documents));
        return vectorStore;
    }

    private TimedEmbeddingModel timingModel() {
        return new TimedEmbeddingModel(mock(org.springframework.ai.embedding.EmbeddingModel.class));
    }

    private RetrievalHintParser hintParser() {
        return new RetrievalHintParser();
    }

    private QueryRewriter unchangedRewriter() {
        return new QueryRewriter() {
            @Override
            public RewriteResult rewrite(String originalQuery) {
                return RewriteResult.unchanged(originalQuery);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    private Map<String, Object> document(
            String businessId, String articleNo, double score, int rank) {
        return Map.of(
                "rank", rank,
                "score", score,
                "dense_rank", rank,
                "dense_score", score,
                "business_id", businessId,
                "source_name", "中华人民共和国测试法",
                "article_no", articleNo,
                "text", "测试法条内容 " + articleNo
        );
    }

    private static class ReverseReranker implements DocumentReranker {
        private String lastQuery = "";
        private int lastFinalTopK;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<RerankedDocument> rerank(
                String query, List<CandidateDocument> candidates, int finalTopK) {
            lastQuery = query;
            lastFinalTopK = finalTopK;
            List<CandidateDocument> reversed = new ArrayList<>(candidates);
            java.util.Collections.reverse(reversed);
            List<RerankedDocument> result = new ArrayList<>();
            for (int i = 0; i < Math.min(finalTopK, reversed.size()); i++) {
                result.add(new RerankedDocument(reversed.get(i), i + 1, 2.2 - i, 0.9 - i * 0.1));
            }
            return result;
        }
    }
}
