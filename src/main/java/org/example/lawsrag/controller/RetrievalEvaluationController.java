package org.example.lawsrag.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.service.EvaluationDatasetService;
import org.example.lawsrag.service.GenerationEvaluationService;
import org.example.lawsrag.service.RetrievalEvaluationService;
import org.example.lawsrag.service.RetrievalMode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api/evaluation")
public class RetrievalEvaluationController {

    private static final long MAX_JAVASCRIPT_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final EvaluationDatasetService datasetService;
    private final RetrievalEvaluationService evaluationService;
    private final GenerationEvaluationService generationEvaluationService;

    public RetrievalEvaluationController(EvaluationDatasetService datasetService,
                                         RetrievalEvaluationService evaluationService,
                                         GenerationEvaluationService generationEvaluationService) {
        this.datasetService = datasetService;
        this.evaluationService = evaluationService;
        this.generationEvaluationService = generationEvaluationService;
    }

    @GetMapping("/dataset")
    public ResponseEntity<Map<String, Object>> dataset(
            @RequestParam(value = "datasetId", required = false) String datasetId) {
        String selectedDatasetId = datasetService.selectDatasetId(datasetId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", datasetService.getDatasetInfo(selectedDatasetId)
        ));
    }

    @GetMapping("/datasets")
    public ResponseEntity<Map<String, Object>> datasets() {
        List<EvaluationDatasetService.DatasetInfo> datasets = datasetService.listDatasets();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "defaultDatasetId", datasetService.getDatasetId(),
                        "datasets", datasets,
                        "rerankerAvailable", evaluationService.isRerankerAvailable(),
                        "queryRewriteAvailable", evaluationService.isQueryRewriteAvailable(),
                        "judgeAvailable", generationEvaluationService.isJudgeAvailable(),
                        "judgeModel", generationEvaluationService.judgeModelName()
                )
        ));
    }

    @GetMapping(value = "/generation/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runGenerationEvaluation(
            @RequestParam(value = "datasetId", required = false) String datasetId,
            @RequestParam(value = "split", defaultValue = "dev") String split,
            @RequestParam(value = "limit", defaultValue = "5") int limit,
            @RequestParam(value = "topK", defaultValue = "5") int topK,
            @RequestParam(value = "candidateTopK", defaultValue = "10") int candidateTopK,
            @RequestParam(value = "retrievalMode", defaultValue = "hybrid") String retrievalModeValue,
            @RequestParam(value = "queryRewrite", defaultValue = "true") boolean queryRewrite,
            @RequestParam(value = "rerank", defaultValue = "true") boolean rerank,
            @RequestParam(value = "sampleSeed", required = false) Long requestedSampleSeed) {
        validate(split, topK, candidateTopK, rerank);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("生成评测题数必须在 1 到 100 之间");
        }
        if (!generationEvaluationService.isJudgeAvailable()) {
            throw new IllegalStateException("CF_API_FAN_API_KEY 未配置，生成评测不可用");
        }
        RetrievalMode retrievalMode = RetrievalMode.parse(retrievalModeValue);
        String selectedDatasetId = datasetService.selectDatasetId(datasetId);
        long sampleSeed = requestedSampleSeed == null
                ? ThreadLocalRandom.current().nextLong(1, MAX_JAVASCRIPT_SAFE_INTEGER)
                : requestedSampleSeed;
        SseEmitter emitter = new SseEmitter(0L);

        Thread.ofVirtual().name("generation-evaluation").start(() -> {
            try {
                send(emitter, "started", Map.of(
                        "dataset", datasetService.getDatasetInfo(selectedDatasetId),
                        "split", split,
                        "limit", limit,
                        "topK", topK,
                        "candidateTopK", rerank ? candidateTopK : topK,
                        "retrievalMode", retrievalMode.name().toLowerCase(),
                        "queryRewrite", queryRewrite,
                        "rerank", rerank,
                        "judgeModel", generationEvaluationService.judgeModelName(),
                        "sampleSeed", sampleSeed
                ));
                GenerationEvaluationService.GenerationSummary summary = generationEvaluationService.run(
                        selectedDatasetId, split, limit, candidateTopK, topK, retrievalMode,
                        queryRewrite, rerank, sampleSeed,
                        result -> sendUnchecked(emitter, "case_result", result));
                send(emitter, "summary", summary);
                emitter.complete();
            } catch (ClientDisconnectedException e) {
                log.info("生成评测页面已断开连接，终止本次推送");
                emitter.complete();
            } catch (Exception e) {
                log.error("生成评测执行失败", e);
                try {
                    send(emitter, "evaluation_error", Map.of(
                            "message", e.getMessage() == null ? "生成评测执行失败" : e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    @GetMapping(value = "/retrieval/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runRetrievalEvaluation(
            @RequestParam(value = "datasetId", required = false) String datasetId,
            @RequestParam(value = "split", defaultValue = "all") String split,
            @RequestParam(value = "topK", defaultValue = "5") int topK,
            @RequestParam(value = "candidateTopK", defaultValue = "10") int candidateTopK,
            @RequestParam(value = "retrievalMode", defaultValue = "dense") String retrievalModeValue,
            @RequestParam(value = "queryRewrite", defaultValue = "false") boolean queryRewrite,
            @RequestParam(value = "rerank", defaultValue = "false") boolean rerank) {
        validate(split, topK, candidateTopK, rerank);
        RetrievalMode retrievalMode = RetrievalMode.parse(retrievalModeValue);
        String selectedDatasetId = datasetService.selectDatasetId(datasetId);
        SseEmitter emitter = new SseEmitter(0L);

        Thread.ofVirtual().name("retrieval-evaluation").start(() -> {
            try {
                EvaluationDatasetService.DatasetInfo info = datasetService.getDatasetInfo(selectedDatasetId);
                send(emitter, "started", Map.of(
                        "dataset", info,
                        "split", split,
                        "topK", topK,
                        "candidateTopK", rerank ? candidateTopK : topK,
                        "retrievalMode", retrievalMode.name().toLowerCase(),
                        "queryRewrite", queryRewrite,
                        "rerank", rerank
                ));

                RetrievalEvaluationService.EvaluationSummary summary = evaluationService.run(
                        selectedDatasetId,
                        split,
                        candidateTopK,
                        topK,
                        retrievalMode,
                        queryRewrite,
                        rerank,
                        result -> sendUnchecked(emitter, "case_result", result)
                );
                send(emitter, "summary", summary);
                emitter.complete();
            } catch (ClientDisconnectedException e) {
                log.info("评测页面已断开连接，终止本次推送");
                emitter.complete();
            } catch (Exception e) {
                log.error("检索评测执行失败", e);
                try {
                    send(emitter, "evaluation_error", Map.of(
                            "message", e.getMessage() == null ? "评测执行失败" : e.getMessage()
                    ));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private void validate(String split, int topK, int candidateTopK, boolean rerank) {
        if (!"all".equals(split) && !"dev".equals(split) && !"test".equals(split)) {
            throw new IllegalArgumentException("split 只能是 all、dev 或 test");
        }
        if (topK < 1 || topK > 50) {
            throw new IllegalArgumentException("topK 必须在 1 到 50 之间");
        }
        if (candidateTopK < 1 || candidateTopK > 100) {
            throw new IllegalArgumentException("candidateTopK 必须在 1 到 100 之间");
        }
        if (rerank && candidateTopK < topK) {
            throw new IllegalArgumentException("开启重排时 candidateTopK 不能小于 topK");
        }
        if (rerank && !evaluationService.isRerankerAvailable()) {
            throw new IllegalStateException("Reranker模型未启用或未加载");
        }
    }

    private void sendUnchecked(SseEmitter emitter, String eventName, Object data) {
        try {
            send(emitter, eventName, data);
        } catch (IOException e) {
            throw new ClientDisconnectedException(e);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON));
    }

    private static class ClientDisconnectedException extends RuntimeException {
        ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
