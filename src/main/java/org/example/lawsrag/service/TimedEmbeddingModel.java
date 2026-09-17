package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * EmbeddingModel 计时装饰器。使用 ThreadLocal 累计当前请求线程的API调用耗时，
 * 不改变原模型的请求、重试和返回结果。
 */
import java.util.List;

@Slf4j
public class TimedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final ThreadLocal<Long> accumulatedNanos = ThreadLocal.withInitial(() -> 0L);
    private final ThreadLocal<Integer> callCount = ThreadLocal.withInitial(() -> 0);

    public TimedEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        long startedAt = System.nanoTime();
        try {
            return delegate.call(request);
        } finally {
            recordCall("request", request.getInstructions(), System.nanoTime() - startedAt);
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        long startedAt = System.nanoTime();
        try {
            return delegate.embed(texts);
        } finally {
            recordCall("batch", texts, System.nanoTime() - startedAt);
        }
    }

    @Override
    public float[] embed(Document document) {
        long startedAt = System.nanoTime();
        try {
            return delegate.embed(document);
        } finally {
            recordCall("document", List.of(getEmbeddingContent(document)),
                    System.nanoTime() - startedAt);
        }
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    public void clearLastCallTiming() {
        accumulatedNanos.remove();
        callCount.remove();
    }

    public long consumeLastCallNanos() {
        long value = accumulatedNanos.get();
        int calls = callCount.get();
        log.info("[EmbeddingApiSummary] calls={}, accumulated={}ms", calls, millis(value));
        accumulatedNanos.remove();
        callCount.remove();
        return value;
    }

    private void recordCall(String type, List<String> texts, long elapsedNanos) {
        accumulatedNanos.set(accumulatedNanos.get() + elapsedNanos);
        int currentCall = callCount.get() + 1;
        callCount.set(currentCall);
        int inputCount = texts == null ? 0 : texts.size();
        int totalCharacters = texts == null ? 0 : texts.stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(String::length)
                .sum();
        log.info("[EmbeddingApiTiming] call={}, type={}, inputCount={}, totalCharacters={}, duration={}ms",
                currentCall, type, inputCount, totalCharacters, millis(elapsedNanos));
    }

    private double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
