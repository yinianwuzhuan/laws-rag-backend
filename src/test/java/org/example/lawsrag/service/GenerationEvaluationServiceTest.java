package org.example.lawsrag.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationEvaluationServiceTest {

    @Test
    void extractsJsonWhenModelWrapsItInMarkdown() {
        String response = """
                ```json
                {"verdict":"TIE","rootCause":"NONE"}
                ```
                """;

        assertEquals("{\"verdict\":\"TIE\",\"rootCause\":\"NONE\"}",
                GenerationEvaluationService.extractJson(response));
    }

    @Test
    void rejectsResponseWithoutJsonObject() {
        assertThrows(IllegalStateException.class,
                () -> GenerationEvaluationService.extractJson("no json"));
    }

    @Test
    void samplesDeterministicallyWhenLimitIsSmallerThanDataset() {
        List<EvaluationDatasetService.EvaluationCase> cases = IntStream.rangeClosed(1, 20)
                .mapToObj(this::evaluationCase)
                .toList();

        var first = GenerationEvaluationService.sampleCases(cases, 5, 20260817L);
        var second = GenerationEvaluationService.sampleCases(cases, 5, 20260817L);

        assertEquals(5, first.size());
        assertEquals(first.stream().map(EvaluationDatasetService.EvaluationCase::id).toList(),
                second.stream().map(EvaluationDatasetService.EvaluationCase::id).toList());
        assertEquals(cases, GenerationEvaluationService.sampleCases(cases, 20, 99L));
    }

    private EvaluationDatasetService.EvaluationCase evaluationCase(int number) {
        return new EvaluationDatasetService.EvaluationCase(
                "case-" + number, "dev", "category", "hard", "domain", "type",
                List.of(), "question-" + number, true,
                List.of(new EvaluationDatasetService.GoldDocument(
                        "business-" + number, "law", "第" + number + "条", String.valueOf(number), 2)),
                List.of(), List.of("answer"), List.of(), null);
    }
}
