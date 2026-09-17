package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationDatasetServiceTest {

    @Test
    void loadsCompleteDatasetAndSerializesApiFieldsAsCamelCase() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvaluationDatasetService service = new EvaluationDatasetService(
                new DefaultResourceLoader(),
                objectMapper,
                "labor-criminal-v1",
                "classpath*:eval/datasets/*.jsonl"
        );

        var cases = service.loadCases();
        assertEquals(100, cases.size());
        assertEquals("ll-001", cases.getFirst().id());
        assertEquals("law_中华人民共和国劳动合同法_4_0",
                cases.getFirst().goldDocuments().getFirst().businessId());
        assertEquals("cl-040", cases.getLast().id());

        var info = service.getDatasetInfo();
        assertEquals("labor-criminal-v1", info.datasetId());
        assertEquals(100, info.totalCount());
        assertEquals(66, info.devCount());
        assertEquals(34, info.testCount());
        assertEquals(98, info.answerableCount());
        assertEquals(2, info.unanswerableCount());

        String apiJson = objectMapper.writeValueAsString(cases.getFirst().goldDocuments().getFirst());
        assertTrue(apiJson.contains("\"businessId\""));
        assertTrue(apiJson.contains("\"sourceName\""));

        var datasets = service.listDatasets();
        assertTrue(datasets.size() >= 3);
        assertTrue(datasets.stream().anyMatch(item -> "civil-code-v1".equals(item.datasetId())));
        assertTrue(datasets.stream().anyMatch(item -> "civil-code-v2".equals(item.datasetId())));
        assertTrue(datasets.stream().anyMatch(item -> "labor-criminal-v1".equals(item.datasetId())));
        assertFalse(datasets.stream().anyMatch(item -> "legal-rag-v3".equals(item.datasetId())));
        assertTrue(datasets.stream().anyMatch(item -> "legal-rag-v4".equals(item.datasetId())));
    }

    @Test
    void loadsLegalRagV4AsStratifiedNewCorpusChallengeSet() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvaluationDatasetService service = new EvaluationDatasetService(
                new DefaultResourceLoader(), objectMapper, "legal-rag-v4",
                "classpath*:eval/datasets/*.jsonl");

        var cases = service.loadCases();
        var info = service.getDatasetInfo();

        assertEquals(100, info.totalCount());
        assertEquals(66, info.devCount());
        assertEquals(34, info.testCount());
        assertEquals(100, info.answerableCount());
        assertEquals(20, cases.stream().filter(item -> "numeric_deadline".equals(item.queryType())).count());
        assertEquals(20, cases.stream().filter(item -> "concept_disambiguation".equals(item.queryType())).count());
        assertEquals(20, cases.stream().filter(item -> "negation_exception".equals(item.queryType())).count());
        assertEquals(20, cases.stream().filter(item -> "colloquial_noisy".equals(item.queryType())).count());
        assertEquals(15, cases.stream().filter(item -> "cross_law_multi".equals(item.queryType())).count());
        assertEquals(5, cases.stream().filter(item -> "exact_term_diagnostic".equals(item.queryType())).count());
        assertTrue(cases.stream().allMatch(item -> item.hardNegativeDocuments().size() == 3));
        assertEquals(21, cases.stream().flatMap(item -> item.goldDocuments().stream())
                .map(EvaluationDatasetService.GoldDocument::sourceName).distinct().count());
        assertTrue(cases.stream().noneMatch(item -> item.id().startsWith("bm25-")));
    }
}
