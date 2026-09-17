package org.example.lawsrag.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class EvaluationDatasetService {

    private static final Pattern SAFE_DATASET_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final PathMatchingResourcePatternResolver resourceResolver;
    private final ObjectMapper objectMapper;
    private final String defaultDatasetId;
    private final String datasetPattern;

    public EvaluationDatasetService(ResourceLoader resourceLoader,
                                    ObjectMapper objectMapper,
                                    @Value("${laws.evaluation.dataset-id:labor-criminal-v1}") String datasetId,
                                    @Value("${laws.evaluation.dataset-pattern:classpath*:eval/datasets/*.jsonl}") String datasetPattern) {
        this.resourceResolver = new PathMatchingResourcePatternResolver(resourceLoader);
        this.objectMapper = objectMapper;
        this.defaultDatasetId = datasetId;
        this.datasetPattern = datasetPattern;
    }

    public List<EvaluationCase> loadCases() {
        return loadCases(defaultDatasetId);
    }

    public List<EvaluationCase> loadCases(String datasetId) {
        Resource resource = findDataset(datasetId);

        List<EvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                EvaluationCase evaluationCase;
                try {
                    evaluationCase = objectMapper.readValue(line, EvaluationCase.class);
                } catch (IOException e) {
                    throw new IllegalStateException("评测集第 " + lineNumber + " 行格式错误", e);
                }
                validate(evaluationCase, lineNumber, ids);
                cases.add(evaluationCase);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取评测集失败: " + datasetId, e);
        }
        return List.copyOf(cases);
    }

    public DatasetInfo getDatasetInfo() {
        return getDatasetInfo(defaultDatasetId);
    }

    public DatasetInfo getDatasetInfo(String datasetId) {
        List<EvaluationCase> cases = loadCases(datasetId);
        long devCount = cases.stream().filter(item -> "dev".equals(item.split())).count();
        long testCount = cases.stream().filter(item -> "test".equals(item.split())).count();
        long answerableCount = cases.stream().filter(EvaluationCase::answerable).count();
        return new DatasetInfo(datasetId, cases.size(), devCount, testCount,
                answerableCount, cases.size() - answerableCount);
    }

    public List<DatasetInfo> listDatasets() {
        try {
            List<DatasetInfo> datasets = new ArrayList<>();
            for (Resource resource : resourceResolver.getResources(datasetPattern)) {
                String id = datasetId(resource);
                if (id != null) {
                    datasets.add(getDatasetInfo(id));
                }
            }
            return datasets.stream()
                    .distinct()
                    .sorted(Comparator.comparing(DatasetInfo::datasetId))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描黄金评测集失败: " + datasetPattern, e);
        }
    }

    public String selectDatasetId(String requestedDatasetId) {
        String selected = requestedDatasetId == null || requestedDatasetId.isBlank()
                ? defaultDatasetId
                : requestedDatasetId;
        findDataset(selected);
        return selected;
    }

    public String getDatasetId() {
        return defaultDatasetId;
    }

    private Resource findDataset(String datasetId) {
        if (datasetId == null || !SAFE_DATASET_ID.matcher(datasetId).matches()) {
            throw new IllegalArgumentException("非法的评测集 ID: " + datasetId);
        }
        try {
            for (Resource resource : resourceResolver.getResources(datasetPattern)) {
                if (datasetId.equals(datasetId(resource))) {
                    return resource;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描黄金评测集失败: " + datasetPattern, e);
        }
        throw new IllegalArgumentException("评测集不存在: " + datasetId);
    }

    private String datasetId(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null || !filename.endsWith(".jsonl")) return null;
        return filename.substring(0, filename.length() - ".jsonl".length());
    }

    private void validate(EvaluationCase item, int lineNumber, Set<String> ids) {
        if (item.id() == null || item.id().isBlank()) {
            throw new IllegalStateException("评测集第 " + lineNumber + " 行缺少 id");
        }
        if (!ids.add(item.id())) {
            throw new IllegalStateException("评测集存在重复 id: " + item.id());
        }
        if (item.question() == null || item.question().isBlank()) {
            throw new IllegalStateException("评测题 " + item.id() + " 缺少 question");
        }
        if (!"dev".equals(item.split()) && !"test".equals(item.split())) {
            throw new IllegalStateException("评测题 " + item.id() + " 的 split 必须是 dev 或 test");
        }
        if (item.answerable() && (item.goldDocuments() == null || item.goldDocuments().isEmpty())) {
            throw new IllegalStateException("可回答评测题 " + item.id() + " 缺少 gold_documents");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvaluationCase(
            String id,
            String split,
            String category,
            String difficulty,
            String domain,
            @JsonAlias("query_type") String queryType,
            @JsonAlias("challenge_tags") List<String> challengeTags,
            String question,
            boolean answerable,
            @JsonAlias("gold_documents") List<GoldDocument> goldDocuments,
            @JsonAlias("hard_negative_documents") List<HardNegativeDocument> hardNegativeDocuments,
            @JsonAlias("gold_answer_points") List<String> goldAnswerPoints,
            @JsonAlias("forbidden_claims") List<String> forbiddenClaims,
            @JsonAlias("expected_behavior") String expectedBehavior
    ) {
        public EvaluationCase {
            challengeTags = challengeTags == null ? List.of() : List.copyOf(challengeTags);
            goldDocuments = goldDocuments == null ? List.of() : List.copyOf(goldDocuments);
            hardNegativeDocuments = hardNegativeDocuments == null
                    ? List.of() : List.copyOf(hardNegativeDocuments);
            goldAnswerPoints = goldAnswerPoints == null ? List.of() : List.copyOf(goldAnswerPoints);
            forbiddenClaims = forbiddenClaims == null ? List.of() : List.copyOf(forbiddenClaims);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldDocument(
            @JsonAlias("business_id") String businessId,
            @JsonAlias("source_name") String sourceName,
            @JsonAlias("article_no") String articleNo,
            @JsonAlias("article_no_arabic") String articleNoArabic,
            int relevance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HardNegativeDocument(
            @JsonAlias("business_id") String businessId,
            @JsonAlias("source_name") String sourceName,
            @JsonAlias("article_no") String articleNo,
            @JsonAlias("article_no_arabic") String articleNoArabic,
            String reason
    ) {}

    public record DatasetInfo(
            String datasetId,
            int totalCount,
            long devCount,
            long testCount,
            long answerableCount,
            long unanswerableCount
    ) {}
}
