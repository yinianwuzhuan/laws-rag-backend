package org.example.lawsrag.controller;

import org.example.lawsrag.service.LawSearchService;
import org.example.lawsrag.service.RetrievalMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LawSearchControllerTest {

    @Test
    void returnsCompleteVectorAndRerankTrace() {
        LawSearchService service = mock(LawSearchService.class);
        when(service.isRerankerAvailable()).thenReturn(true);
        Map<String, Object> vector = Map.of("vector_rank", 1, "business_id", "law_a_1_0");
        Map<String, Object> reranked = Map.of(
                "vector_rank", 1, "rerank_rank", 2, "business_id", "law_a_1_0");
        when(service.searchWithTrace(anyString(), anyInt(), anyInt(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new LawSearchService.SearchExecution(
                        List.of(vector), List.of(reranked), List.of(reranked)));
        LawSearchController controller = new LawSearchController(service);

        var response = controller.search("测试问题", 5, 10, true, false, "dense",
                null, null, null, null, null, null, null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(vector), response.getBody().get("vectorCandidates"));
        assertEquals(List.of(reranked), response.getBody().get("rerankedCandidates"));
        assertEquals(List.of(reranked), response.getBody().get("results"));
        verify(service).searchWithTrace(
                "测试问题", 10, 5, Map.of(), RetrievalMode.DENSE, true, false);
    }

    @Test
    void rejectsRerankWhenModelIsUnavailable() {
        LawSearchService service = mock(LawSearchService.class);
        when(service.isRerankerAvailable()).thenReturn(false);
        LawSearchController controller = new LawSearchController(service);

        var capabilities = controller.searchCapabilities();
        var response = controller.search("测试问题", 5, 10, true, false, "dense",
                null, null, null, null, null, null, null, null, null);

        assertFalse((Boolean) capabilities.getBody().get("rerankerAvailable"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("Reranker"));
    }
}
