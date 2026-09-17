package org.example.lawsrag.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用本地 ONNX Cross-Encoder 对问题与候选法条进行批量相关性评分。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "laws.retrieval.reranker",
        name = "provider",
        havingValue = "onnx",
        matchIfMissing = true)
public class OnnxCrossEncoderReranker implements DocumentReranker {

    private final boolean enabled;
    private final Path modelDirectory;
    private final int maxLength;
    private final int batchSize;
    private final int intraOpThreads;
    private final int interOpThreads;
    private final boolean logDetails;

    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public OnnxCrossEncoderReranker(
            @Value("${laws.retrieval.reranker.enabled:false}") boolean enabled,
            @Value("${laws.retrieval.reranker.model-path:${RERANKER_MODEL_PATH:models/reranker}}") String modelPath,
            @Value("${laws.retrieval.reranker.max-length:512}") int maxLength,
            @Value("${laws.retrieval.reranker.batch-size:10}") int batchSize,
            @Value("${laws.retrieval.reranker.intra-op-threads:8}") int intraOpThreads,
            @Value("${laws.retrieval.reranker.inter-op-threads:1}") int interOpThreads,
            @Value("${laws.retrieval.reranker.log-details:false}") boolean logDetails) {
        this.enabled = enabled;
        this.modelDirectory = Path.of(modelPath).toAbsolutePath().normalize();
        this.maxLength = maxLength;
        this.batchSize = batchSize;
        this.intraOpThreads = intraOpThreads;
        this.interOpThreads = interOpThreads;
        this.logDetails = logDetails;
    }

    @PostConstruct
    void initialize() {
        if (!enabled) {
            log.info("[Reranker] 未启用，本次启动保持纯向量检索");
            return;
        }
        Path modelFile = modelDirectory.resolve("model.onnx");
        Path tokenizerFile = modelDirectory.resolve("tokenizer.json");
        if (!Files.isRegularFile(modelFile) || !Files.isRegularFile(tokenizerFile)) {
            throw new IllegalStateException("Reranker模型目录缺少 model.onnx 或 tokenizer.json: " + modelDirectory);
        }
        if (maxLength < 16 || batchSize < 1 || intraOpThreads < 1 || interOpThreads < 1) {
            throw new IllegalStateException("Reranker max-length必须>=16，batch-size和线程数必须>=1");
        }

        try {
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optAddSpecialTokens(true)
                    .optTruncation(true)
                    .optPadding(true)
                    .optMaxLength(maxLength)
                    .build();
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                // CPU部署显式限制线程，避免ONNX Runtime默认占满全部逻辑核心后产生
                // 线程争抢和明显的P95延迟波动。单模型图使用顺序执行，算子内部
                // 仍可通过intra-op线程并行。
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.setIntraOpNumThreads(intraOpThreads);
                options.setInterOpNumThreads(interOpThreads);
                options.setDeterministicCompute(true);
                session = environment.createSession(modelFile.toString(), options);
            }
            scoreBatch("劳动合同多久必须签订？", List.of("法律名称：《中华人民共和国劳动合同法》\n条文：第十条\n内容：建立劳动关系，应当订立书面劳动合同。"));
            log.info("[Reranker] ONNX模型加载完成, path={}, inputs={}, maxLength={}, batchSize={}, "
                            + "executionMode=SEQUENTIAL, intraOpThreads={}, interOpThreads={}, "
                            + "availableProcessors={}",
                    modelDirectory, session.getInputNames(), maxLength, batchSize,
                    intraOpThreads, interOpThreads, Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            closeResources();
            throw new IllegalStateException("加载Reranker模型失败: " + rootMessage(e), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return enabled && session != null && tokenizer != null;
    }

    @Override
    public List<RerankedDocument> rerank(
            String query, List<CandidateDocument> candidates, int finalTopK) {
        long requestStartedAt = System.nanoTime();
        synchronized (this) {
            long lockAcquiredAt = System.nanoTime();
            long queueWaitNanos = lockAcquiredAt - requestStartedAt;
            return rerankLocked(query, candidates, finalTopK, requestStartedAt, queueWaitNanos);
        }
    }

    private List<RerankedDocument> rerankLocked(
            String query, List<CandidateDocument> candidates, int finalTopK,
            long requestStartedAt, long queueWaitNanos) {
        if (!isAvailable()) {
            throw new IllegalStateException("Reranker未启用或模型尚未加载");
        }
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (finalTopK < 1) throw new IllegalArgumentException("finalTopK必须大于0");

        long executionStartedAt = System.nanoTime();
        long inputLogStartedAt = System.nanoTime();
        logRerankInput(query, candidates);
        long inputLogNanos = System.nanoTime() - inputLogStartedAt;

        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        long tokenizationNanos = 0L;
        long tensorPreparationNanos = 0L;
        long inferenceNanos = 0L;
        long outputParsingNanos = 0L;
        List<Integer> batchSequenceLengths = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += batchSize) {
            List<CandidateDocument> batch = candidates.subList(start, Math.min(start + batchSize, candidates.size()));
            BatchScoreResult batchResult = scoreBatch(
                    query, batch.stream().map(CandidateDocument::passage).toList());
            List<Double> scores = batchResult.scores();
            tokenizationNanos += batchResult.tokenizationNanos();
            tensorPreparationNanos += batchResult.tensorPreparationNanos();
            inferenceNanos += batchResult.inferenceNanos();
            outputParsingNanos += batchResult.outputParsingNanos();
            batchSequenceLengths.add(batchResult.sequenceLength());
            for (int i = 0; i < batch.size(); i++) {
                scored.add(new ScoredCandidate(batch.get(i), scores.get(i)));
            }
        }

        long sortingStartedAt = System.nanoTime();
        scored.sort(Comparator.comparingDouble(ScoredCandidate::rawScore).reversed()
                .thenComparingInt(item -> item.candidate().vectorRank()));
        List<RerankedDocument> result = new ArrayList<>();
        for (int i = 0; i < Math.min(finalTopK, scored.size()); i++) {
            ScoredCandidate item = scored.get(i);
            result.add(new RerankedDocument(item.candidate(), i + 1,
                    item.rawScore(), sigmoid(item.rawScore())));
        }
        long sortingNanos = System.nanoTime() - sortingStartedAt;
        long outputLogStartedAt = System.nanoTime();
        logRerankOutput(query, result);
        long outputLogNanos = System.nanoTime() - outputLogStartedAt;
        long executionNanos = System.nanoTime() - executionStartedAt;
        long requestTotalNanos = System.nanoTime() - requestStartedAt;
        int passageCharacters = candidates.stream()
                .mapToInt(candidate -> candidate.passage() == null ? 0 : candidate.passage().length())
                .sum();
        int maxPassageCharacters = candidates.stream()
                .mapToInt(candidate -> candidate.passage() == null ? 0 : candidate.passage().length())
                .max().orElse(0);
        log.info("[RerankerTiming] candidates={}, batches={}, queryCharacters={}, "
                        + "averagePassageCharacters={}, maxPassageCharacters={}, sequenceLengths={}, "
                        + "queueWait={}ms, "
                        + "tokenization={}ms, tensorPreparation={}ms, onnxInference={}ms, "
                        + "outputParsing={}ms, sorting={}ms, inputLogging={}ms, outputLogging={}ms, "
                        + "executionTotal={}ms, requestTotal={}ms",
                candidates.size(), (candidates.size() + batchSize - 1) / batchSize,
                query == null ? 0 : query.length(),
                candidates.isEmpty() ? 0 : passageCharacters / candidates.size(),
                maxPassageCharacters, batchSequenceLengths, millis(queueWaitNanos),
                millis(tokenizationNanos),
                millis(tensorPreparationNanos), millis(inferenceNanos),
                millis(outputParsingNanos), millis(sortingNanos), millis(inputLogNanos),
                millis(outputLogNanos), millis(executionNanos), millis(requestTotalNanos));
        return List.copyOf(result);
    }

    private void logRerankInput(String query, List<CandidateDocument> candidates) {
        if (!logDetails) return;
        StringBuilder message = new StringBuilder()
                .append("\n================ RERANK INPUT ================\n")
                .append("问题：").append(query).append('\n')
                .append("候选数量：").append(candidates.size()).append('\n');
        for (CandidateDocument candidate : candidates) {
            message.append("\n[向量排名 ").append(candidate.vectorRank()).append("]")
                    .append(" vectorScore=").append(candidate.vectorScore()).append('\n')
                    .append(candidate.passage()).append('\n');
        }
        message.append("============== RERANK INPUT END ==============\n");
        log.info("{}", message);
    }

    private void logRerankOutput(String query, List<RerankedDocument> results) {
        if (!logDetails) return;
        StringBuilder message = new StringBuilder()
                .append("\n================ RERANK OUTPUT ===============\n")
                .append("问题：").append(query).append('\n')
                .append("重排数量：").append(results.size()).append('\n');
        for (RerankedDocument result : results) {
            CandidateDocument candidate = result.candidate();
            message.append("\n[重排排名 ").append(result.rerankRank()).append("]")
                    .append(" 原向量排名=").append(candidate.vectorRank())
                    .append(" rawScore=").append(result.rawScore())
                    .append(" normalizedScore=").append(result.normalizedScore()).append('\n')
                    .append(candidate.passage()).append('\n');
        }
        message.append("============= RERANK OUTPUT END ==============\n");
        log.info("{}", message);
    }

    private BatchScoreResult scoreBatch(String query, List<String> passages) {
        try {
            long tokenizationStartedAt = System.nanoTime();
            PairList<String, String> pairs = new PairList<>(passages.size());
            passages.forEach(passage -> pairs.add(query, passage));
            Encoding[] encodings = tokenizer.batchEncode(pairs);
            long tokenizationNanos = System.nanoTime() - tokenizationStartedAt;

            long tensorPreparationStartedAt = System.nanoTime();
            int sequenceLength = encodings[0].getIds().length;

            long[][] inputIds = new long[encodings.length][sequenceLength];
            long[][] attentionMask = new long[encodings.length][sequenceLength];
            long[][] tokenTypeIds = new long[encodings.length][sequenceLength];
            for (int i = 0; i < encodings.length; i++) {
                copy(encodings[i].getIds(), inputIds[i]);
                copy(encodings[i].getAttentionMask(), attentionMask[i]);
                copy(encodings[i].getTypeIds(), tokenTypeIds[i]);
            }

            Map<String, OnnxTensor> tensors = new LinkedHashMap<>();
            try {
                tensors.put("input_ids", OnnxTensor.createTensor(environment, inputIds));
                tensors.put("attention_mask", OnnxTensor.createTensor(environment, attentionMask));
                if (session.getInputNames().contains("token_type_ids")) {
                    tensors.put("token_type_ids", OnnxTensor.createTensor(environment, tokenTypeIds));
                }
                long tensorPreparationNanos = System.nanoTime() - tensorPreparationStartedAt;
                long inferenceStartedAt = System.nanoTime();
                try (OrtSession.Result output = session.run(tensors)) {
                    long inferenceNanos = System.nanoTime() - inferenceStartedAt;
                    long outputParsingStartedAt = System.nanoTime();
                    List<Double> scores = logits(output.get(0), passages.size());
                    long outputParsingNanos = System.nanoTime() - outputParsingStartedAt;
                    return new BatchScoreResult(scores, sequenceLength, tokenizationNanos,
                            tensorPreparationNanos, inferenceNanos, outputParsingNanos);
                }
            } finally {
                tensors.values().forEach(OnnxTensor::close);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Reranker推理失败: " + rootMessage(e), e);
        }
    }

    private List<Double> logits(OnnxValue output, int expectedSize) throws OrtException {
        Object value = output.getValue();
        List<Double> scores = new ArrayList<>(expectedSize);
        if (value instanceof float[][] rows) {
            for (float[] row : rows) scores.add((double) row[0]);
        } else if (value instanceof float[] values) {
            for (float score : values) scores.add((double) score);
        } else if (value instanceof double[][] rows) {
            for (double[] row : rows) scores.add(row[0]);
        } else if (value instanceof double[] values) {
            for (double score : values) scores.add(score);
        } else {
            throw new IllegalStateException("不支持的Reranker输出类型: " + value.getClass().getName());
        }
        if (scores.size() != expectedSize) {
            throw new IllegalStateException("Reranker输出数量不匹配: expected=" + expectedSize + ", actual=" + scores.size());
        }
        return scores;
    }

    private void copy(long[] source, long[] target) {
        System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
    }

    private double sigmoid(double value) {
        if (value >= 0) return 1.0 / (1.0 + Math.exp(-value));
        double exp = Math.exp(value);
        return exp / (1.0 + exp);
    }

    private double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    @PreDestroy
    void closeResources() {
        if (tokenizer != null) tokenizer.close();
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("关闭Reranker ONNX Session失败", e);
            }
        }
        tokenizer = null;
        session = null;
    }

    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ScoredCandidate(CandidateDocument candidate, double rawScore) {}

    private record BatchScoreResult(
            List<Double> scores,
            int sequenceLength,
            long tokenizationNanos,
            long tensorPreparationNanos,
            long inferenceNanos,
            long outputParsingNanos
    ) {}
}
