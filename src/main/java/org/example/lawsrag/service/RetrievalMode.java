package org.example.lawsrag.service;

import java.util.Locale;

/** 第一阶段召回模式；Reranker是召回后的可选第二阶段。 */
public enum RetrievalMode {
    DENSE,
    BM25,
    HYBRID;

    public static RetrievalMode parse(String value) {
        if (value == null || value.isBlank()) return DENSE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("retrievalMode只能是dense、bm25或hybrid");
        }
    }
}
