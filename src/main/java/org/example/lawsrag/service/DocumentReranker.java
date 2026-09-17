package org.example.lawsrag.service;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对向量召回候选进行第二阶段相关性排序。
 */
public interface DocumentReranker {

    boolean isAvailable();

    List<RerankedDocument> rerank(String query, List<CandidateDocument> candidates, int finalTopK);

    record CandidateDocument(
            int vectorRank,
            Double vectorScore,
            String sourceName,
            String articleNo,
            String text,
            Map<String, Object> attributes
    ) {
        public CandidateDocument {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        public String passage() {
            return "法律名称：《" + sourceName + "》\n"
                    + "条文：" + articleNo + "\n"
                    + "内容：" + text;
        }
    }

    record RerankedDocument(
            CandidateDocument candidate,
            int rerankRank,
            double rawScore,
            double normalizedScore
    ) {}
}
