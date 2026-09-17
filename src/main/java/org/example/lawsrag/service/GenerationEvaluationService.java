package org.example.lawsrag.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.service.EvaluationDatasetService.EvaluationCase;
import org.example.lawsrag.service.EvaluationDatasetService.GoldDocument;
import org.example.lawsrag.service.LawSearchService.SearchExecution;
import org.example.lawsrag.service.LawChatService.GeneratedAnswer;
import org.example.lawsrag.service.ResponsesApiJudgeClient.JudgeResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * 对同一道黄金问题执行两条生成链路，并使用独立的强模型统一评分：
 * 1. 黄金法条 -> 业务生成模型；2. 真实检索法条 -> 同一个业务生成模型。
 */
@Slf4j
@Service
public class GenerationEvaluationService {

    private static final String JUDGE_SYSTEM_PROMPT = """
            你是一名严格、保守的中国法律 RAG 评测员。你的任务是评价答案质量，不是重新回答问题。
            你会同时收到匿名的“答案 A”和“答案 B”，每份答案都附有其实际输入上下文。必须分别评价，不能因为答案文字流畅而提高分数，也不得猜测答案来自哪条系统链路。

            五项指标均为 0 到 100 的整数：
            1. correctness：结论、主体、条件、数字、期限、例外是否符合法律黄金要点，是否触犯禁止性错误。
            2. completeness：黄金答案要点被覆盖的程度；不可回答题则评价是否正确拒答。
            3. faithfulness：答案中的实质性陈述是否能由该答案对应的“实际输入上下文”直接支持。
            4. citationQuality：引用的法律名称、条号和条文内容是否存在于对应上下文，引用是否真正支持结论。
            5. answerRelevancy：答案是否直接回应用户问题，是否简洁聚焦；重复结论、讨论弱相关法条、扩展用户未询问的事项或为了填满结构而增加内容都应扣分。只评价切题程度，不用该指标重复评价事实正确性。

            问题归因必须遵守以下规则：
            1. RETRIEVAL：实际输入上下文缺少黄金法条或关键黄金要点，但答案对现有上下文保持忠实并明确拒绝过度推断。
            2. GENERATION：实际输入上下文已经包含回答所需的黄金依据，但答案仍出现错误、遗漏、错误引用或无依据推断。
            3. BOTH：实际输入上下文既缺少关键黄金依据，答案又没有正确拒答，反而编造法条、虚构引用、套用无关条文或强行作出结论。
            4. NONE：输入上下文充分，且答案正确、完整、忠实，引用能够支持结论。
            不能把“检索不到依据后发生的编造”只归因为检索问题；这种情况必须判为 BOTH。
            对不可回答题，能够明确说明依据不足且不编造，应视为正确行为。

            overall 不需要你计算，系统会按 correctness 30%、completeness 20%、faithfulness 25%、citationQuality 15%、answerRelevancy 10% 计算。
            unsupportedClaims、missingPoints、citationIssues 只列关键问题，没有则返回空数组。
            verdict 只能是 A_BETTER、B_BETTER 或 TIE。
            rootCause 只能是 RETRIEVAL、GENERATION、BOTH 或 NONE，并严格按照上述归因规则判断。
            不输出思维过程，只输出简短、可核验的评分理由。
            必须只返回合法 JSON，不得使用 Markdown 代码块，也不得添加 JSON 之外的文字。
            """;

    private static final String JUDGE_USER_TEMPLATE = """
            请评价下面这组样本，并严格返回以下 JSON 结构：
            {
              "evaluationA": {
                "correctness": 0,
                "completeness": 0,
                "faithfulness": 0,
                "citationQuality": 0,
                "answerRelevancy": 0,
                "reason": "",
                "unsupportedClaims": [],
                "missingPoints": [],
                "citationIssues": []
              },
              "evaluationB": {
                "correctness": 0,
                "completeness": 0,
                "faithfulness": 0,
                "citationQuality": 0,
                "answerRelevancy": 0,
                "reason": "",
                "unsupportedClaims": [],
                "missingPoints": [],
                "citationIssues": []
              },
              "verdict": "A_BETTER|B_BETTER|TIE",
              "rootCause": "RETRIEVAL|GENERATION|BOTH|NONE",
              "comparisonReason": ""
            }

            评测材料（JSON）：
            %s
            """;
    private static final int JUDGE_PARSE_MAX_ATTEMPTS = 2;

    private final EvaluationDatasetService datasetService;
    private final LawSearchService lawSearchService;
    private final LawChatService lawChatService;
    private final ResponsesApiJudgeClient judgeClient;
    private final ObjectMapper objectMapper;
    private final String judgeModelName;
    private final int successThreshold;

    public GenerationEvaluationService(
            EvaluationDatasetService datasetService,
            LawSearchService lawSearchService,
            LawChatService lawChatService,
            ResponsesApiJudgeClient judgeClient,
            ObjectMapper objectMapper,
            @Value("${laws.generation-evaluation.success-threshold:80}") int successThreshold) {
        this.datasetService = datasetService;
        this.lawSearchService = lawSearchService;
        this.lawChatService = lawChatService;
        this.judgeClient = judgeClient;
        this.objectMapper = objectMapper;
        this.judgeModelName = judgeClient.model();
        this.successThreshold = successThreshold;
    }

    public GenerationSummary run(String datasetId,
                                 String split,
                                 int limit,
                                 int candidateTopK,
                                 int topK,
                                 RetrievalMode retrievalMode,
                                 boolean queryRewrite,
                                 boolean rerank,
                                 long sampleSeed,
                                 Consumer<GenerationCaseResult> onCaseCompleted) {
        if (!judgeClient.isAvailable()) {
            throw new IllegalStateException("CF_API_FAN_API_KEY 未配置，无法运行生成评测");
        }
        List<EvaluationCase> available = datasetService.loadCases(datasetId).stream()
                .filter(item -> "all".equals(split) || split.equals(item.split()))
                .toList();
        if (available.isEmpty()) {
            throw new IllegalArgumentException("评测集分组没有可执行的问题: " + split);
        }
        List<EvaluationCase> selected = sampleCases(available, limit, sampleSeed);

        long startedAt = System.currentTimeMillis();
        List<GenerationCaseResult> results = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            GenerationCaseResult result = evaluateCase(selected.get(i), i + 1, selected.size(),
                    candidateTopK, topK, retrievalMode, queryRewrite, rerank);
            results.add(result);
            onCaseCompleted.accept(result);
        }
        return summarize(datasetId, split, candidateTopK, topK, retrievalMode,
                queryRewrite, rerank, sampleSeed, available.size(), results,
                System.currentTimeMillis() - startedAt);
    }

    static List<EvaluationCase> sampleCases(List<EvaluationCase> available, int limit, long sampleSeed) {
        if (limit >= available.size()) {
            return List.copyOf(available);
        }
        List<EvaluationCase> shuffled = new ArrayList<>(available);
        Collections.shuffle(shuffled, new Random(sampleSeed));
        return List.copyOf(shuffled.subList(0, limit));
    }

    private GenerationCaseResult evaluateCase(EvaluationCase item,
                                                int sequence,
                                                int total,
                                                int candidateTopK,
                                                int topK,
                                                RetrievalMode retrievalMode,
                                                boolean queryRewrite,
                                                boolean rerank) {
        long startedAt = System.currentTimeMillis();
        try {
            List<Document> goldContext = goldContext(item);
            long goldGenerationStartedAt = System.currentTimeMillis();
            GeneratedAnswer goldGeneration = lawChatService.generateStrictAnswerWithUsage(
                    item.question(), goldContext);
            String goldAnswer = goldGeneration.content();
            long goldGenerationMs = System.currentTimeMillis() - goldGenerationStartedAt;

            long retrievalStartedAt = System.currentTimeMillis();
            SearchExecution search = lawSearchService.searchWithTrace(item.question(), candidateTopK,
                    topK, Map.of(), retrievalMode, rerank, queryRewrite);
            long retrievalMs = System.currentTimeMillis() - retrievalStartedAt;
            List<Document> ragContext = documents(search.finalResults(), rerank);

            long ragGenerationStartedAt = System.currentTimeMillis();
            GeneratedAnswer ragGeneration = lawChatService.generateStrictAnswerWithUsage(
                    item.question(), ragContext);
            String ragAnswer = ragGeneration.content();
            long ragGenerationMs = System.currentTimeMillis() - ragGenerationStartedAt;

            long judgeStartedAt = System.currentTimeMillis();
            JudgeComparison comparison;
            try {
                comparison = judge(item, goldContext, ragContext, goldAnswer, ragAnswer);
            } catch (Exception judgeError) {
                long judgeMs = System.currentTimeMillis() - judgeStartedAt;
                log.error("生成评测Judge失败，保留已生成答案与上下文, id={}, question={}",
                        item.id(), item.question(), judgeError);
                return new GenerationCaseResult(sequence, total, item.id(), item.split(), item.category(),
                        item.difficulty(), item.domain(), item.question(), item.answerable(),
                        "JUDGE_ERROR", rootMessage(judgeError), goldAnswer, ragAnswer,
                        contextViews(goldContext), contextViews(ragContext), null, null,
                        "", "", "", goldGenerationMs, retrievalMs, ragGenerationMs, judgeMs,
                        System.currentTimeMillis() - startedAt, goldGeneration.tokenUsage(),
                        ragGeneration.tokenUsage(), ModelTokenUsage.unavailable());
            }
            long judgeMs = System.currentTimeMillis() - judgeStartedAt;
            log.info("[GenerationEvaluationToken] id={}, gold={}, rag={}, judge={}, total={}",
                    item.id(), goldGeneration.tokenUsage(), ragGeneration.tokenUsage(),
                    comparison.tokenUsage(), combineCaseUsage(goldGeneration.tokenUsage(),
                            ragGeneration.tokenUsage(), comparison.tokenUsage()));

            return new GenerationCaseResult(sequence, total, item.id(), item.split(), item.category(),
                    item.difficulty(), item.domain(), item.question(), item.answerable(), "SCORED", null,
                    goldAnswer, ragAnswer, contextViews(goldContext), contextViews(ragContext),
                    comparison.goldEvaluation(), comparison.ragEvaluation(),
                    comparison.verdict(), comparison.rootCause(), comparison.comparisonReason(),
                    goldGenerationMs, retrievalMs, ragGenerationMs, judgeMs,
                    System.currentTimeMillis() - startedAt, goldGeneration.tokenUsage(),
                    ragGeneration.tokenUsage(), comparison.tokenUsage());
        } catch (Exception e) {
            log.error("生成评测题执行失败, id={}, question={}", item.id(), item.question(), e);
            return new GenerationCaseResult(sequence, total, item.id(), item.split(), item.category(),
                    item.difficulty(), item.domain(), item.question(), item.answerable(), "ERROR",
                    rootMessage(e), "", "", List.of(), List.of(), null, null,
                    "", "", "", 0, 0, 0, 0, System.currentTimeMillis() - startedAt,
                    ModelTokenUsage.unavailable(), ModelTokenUsage.unavailable(),
                    ModelTokenUsage.unavailable());
        }
    }

    private List<Document> goldContext(EvaluationCase item) {
        if (!item.answerable()) return List.of();
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < item.goldAnswerPoints().size(); i++) {
            GoldDocument gold = item.goldDocuments().get(Math.min(i, item.goldDocuments().size() - 1));
            result.add(Document.builder()
                    .text(item.goldAnswerPoints().get(i))
                    .metadata(Map.of(
                            "business_id", value(gold.businessId()),
                            "source_name", value(gold.sourceName()),
                            "article_no", value(gold.articleNo()),
                            "gold_context", true
                    ))
                    .build());
        }
        return List.copyOf(result);
    }

    private List<Document> documents(List<Map<String, Object>> rows, boolean rerank) {
        return rows.stream().map(row -> {
            // Spring AI Document 明确禁止 metadata 中出现 null。
            // 检索追踪结果会包含尚未执行阶段的空字段（例如未重排时的 rerank_score），
            // 因此这里只复制实际有值的字段。
            Map<String, Object> metadata = new LinkedHashMap<>();
            row.forEach((key, value) -> {
                if (value != null && !"text".equals(key) && !"score".equals(key)) {
                    metadata.put(key, value);
                }
            });
            Double score = number(row.get(rerank ? "rerank_score" : "retrieval_score"));
            return Document.builder()
                    .text(value(row.get("text")))
                    .metadata(metadata)
                    .score(score)
                    .build();
        }).toList();
    }

    private JudgeComparison judge(EvaluationCase item,
                                  List<Document> goldContext,
                                  List<Document> ragContext,
                                  String goldAnswer,
                                  String ragAnswer) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= JUDGE_PARSE_MAX_ATTEMPTS; attempt++) {
            try {
                return judgeOnce(item, goldContext, ragContext, goldAnswer, ragAnswer);
            } catch (Exception e) {
                lastError = e;
                log.warn("[JudgeParseRetry] id={}, attempt={}/{}, reason={}",
                        item.id(), attempt, JUDGE_PARSE_MAX_ATTEMPTS, rootMessage(e));
            }
        }
        throw lastError == null ? new IllegalStateException("Judge评分失败") : lastError;
    }

    private JudgeComparison judgeOnce(EvaluationCase item,
                                      List<Document> goldContext,
                                      List<Document> ragContext,
                                      String goldAnswer,
                                      String ragAnswer) throws Exception {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("question", item.question());
        material.put("answerable", item.answerable());
        material.put("expectedBehavior", item.expectedBehavior());
        material.put("goldAnswerPoints", item.goldAnswerPoints());
        material.put("forbiddenClaims", item.forbiddenClaims());
        material.put("goldDocuments", item.goldDocuments());
        boolean goldIsA = Math.floorMod(item.id().hashCode(), 2) == 0;
        material.put("inputContextA", contextViews(goldIsA ? goldContext : ragContext));
        material.put("answerA", goldIsA ? goldAnswer : ragAnswer);
        material.put("inputContextB", contextViews(goldIsA ? ragContext : goldContext));
        material.put("answerB", goldIsA ? ragAnswer : goldAnswer);

        JudgeResponse judgeResponse = judgeClient.generateWithUsage(
                JUDGE_SYSTEM_PROMPT,
                String.format(JUDGE_USER_TEMPLATE, objectMapper.writeValueAsString(material)));
        RawJudgeComparison raw = objectMapper.readValue(
                extractJson(judgeResponse.content()), RawJudgeComparison.class);
        if (raw.evaluationA() == null || raw.evaluationB() == null) {
            throw new IllegalStateException("Judge JSON 缺少 evaluationA 或 evaluationB");
        }
        JudgeScore evaluationA = normalize(raw.evaluationA());
        JudgeScore evaluationB = normalize(raw.evaluationB());
        JudgeScore goldEvaluation = goldIsA ? evaluationA : evaluationB;
        JudgeScore ragEvaluation = goldIsA ? evaluationB : evaluationA;
        String verdict = normalizeVerdict(raw.verdict(), goldIsA);
        return new JudgeComparison(goldEvaluation, ragEvaluation, verdict,
                allowed(raw.rootCause(), List.of("RETRIEVAL", "GENERATION", "BOTH", "NONE"), "NONE"),
                value(raw.comparisonReason()), judgeResponse.tokenUsage());
    }

    static String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Judge 返回空响应");
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Judge 未返回合法 JSON: " + response);
        }
        return response.substring(start, end + 1);
    }

    private String normalizeVerdict(String verdict, boolean goldIsA) {
        if ("TIE".equals(verdict)) return "TIE";
        if ("A_BETTER".equals(verdict)) return goldIsA ? "GOLD_BETTER" : "RAG_BETTER";
        if ("B_BETTER".equals(verdict)) return goldIsA ? "RAG_BETTER" : "GOLD_BETTER";
        return "TIE";
    }

    private JudgeScore normalize(JudgeScore score) {
        if (score == null) score = new JudgeScore(0, 0, 0, 0, 0, 0,
                "Judge 未返回评分", List.of(), List.of(), List.of());
        int correctness = clamp(score.correctness());
        int completeness = clamp(score.completeness());
        int faithfulness = clamp(score.faithfulness());
        int citationQuality = clamp(score.citationQuality());
        int answerRelevancy = clamp(score.answerRelevancy());
        double overall = round(correctness * 0.30 + completeness * 0.20
                + faithfulness * 0.25 + citationQuality * 0.15 + answerRelevancy * 0.10);
        return new JudgeScore(correctness, completeness, faithfulness, citationQuality,
                answerRelevancy, overall,
                value(score.reason()), safe(score.unsupportedClaims()), safe(score.missingPoints()),
                safe(score.citationIssues()));
    }

    private GenerationSummary summarize(String datasetId,
                                        String split,
                                        int candidateTopK,
                                        int topK,
                                        RetrievalMode retrievalMode,
                                        boolean queryRewrite,
                                        boolean rerank,
                                        long sampleSeed,
                                        int availableCases,
                                        List<GenerationCaseResult> results,
                                        long durationMs) {
        List<GenerationCaseResult> scored = results.stream()
                .filter(item -> "SCORED".equals(item.status()))
                .toList();
        AggregateScore gold = aggregate(scored.stream().map(GenerationCaseResult::goldEvaluation).toList());
        AggregateScore rag = aggregate(scored.stream().map(GenerationCaseResult::ragEvaluation).toList());
        TokenUsageSummary goldTokens = aggregateUsage(results.stream()
                .map(GenerationCaseResult::goldTokenUsage).toList());
        TokenUsageSummary ragTokens = aggregateUsage(results.stream()
                .map(GenerationCaseResult::ragTokenUsage).toList());
        TokenUsageSummary judgeTokens = aggregateUsage(results.stream()
                .map(GenerationCaseResult::judgeTokenUsage).toList());
        TokenUsageSummary overallTokens = combineUsage(goldTokens, ragTokens, judgeTokens);
        return new GenerationSummary(datasetId, split, judgeModelName, successThreshold,
                topK, rerank ? candidateTopK : topK, retrievalMode.name().toLowerCase(),
                queryRewrite, rerank, sampleSeed, availableCases, results.size(), scored.size(),
                results.stream().filter(item -> !"SCORED".equals(item.status())).count(),
                gold, rag, round(gold.overall() - rag.overall()),
                scored.stream().filter(item -> item.goldEvaluation().overall() >= successThreshold).count(),
                scored.stream().filter(item -> item.ragEvaluation().overall() >= successThreshold).count(),
                rootCauseCounts(scored), goldTokens, ragTokens, judgeTokens, overallTokens,
                results.isEmpty() ? 0.0 : round((double) overallTokens.totalTokens() / results.size()),
                durationMs, round(results.stream().mapToLong(GenerationCaseResult::durationMs)
                        .average().orElse(0.0)));
    }

    private TokenUsageSummary aggregateUsage(List<ModelTokenUsage> usages) {
        long input = 0;
        long output = 0;
        long total = 0;
        long measuredCalls = 0;
        long unavailableCalls = 0;
        for (ModelTokenUsage usage : usages) {
            if (usage != null && usage.available()) {
                input += usage.inputTokens();
                output += usage.outputTokens();
                total += usage.totalTokens();
                measuredCalls++;
            } else {
                unavailableCalls++;
            }
        }
        return new TokenUsageSummary(input, output, total, measuredCalls, unavailableCalls);
    }

    private TokenUsageSummary combineUsage(TokenUsageSummary... summaries) {
        long input = 0;
        long output = 0;
        long total = 0;
        long measured = 0;
        long unavailable = 0;
        for (TokenUsageSummary summary : summaries) {
            input += summary.inputTokens();
            output += summary.outputTokens();
            total += summary.totalTokens();
            measured += summary.measuredCalls();
            unavailable += summary.unavailableCalls();
        }
        return new TokenUsageSummary(input, output, total, measured, unavailable);
    }

    private ModelTokenUsage combineCaseUsage(ModelTokenUsage... usages) {
        long input = 0;
        long output = 0;
        long total = 0;
        boolean available = false;
        for (ModelTokenUsage usage : usages) {
            if (usage != null && usage.available()) {
                input += usage.inputTokens();
                output += usage.outputTokens();
                total += usage.totalTokens();
                available = true;
            }
        }
        return available ? new ModelTokenUsage(input, output, total, true)
                : ModelTokenUsage.unavailable();
    }

    private AggregateScore aggregate(List<JudgeScore> scores) {
        return new AggregateScore(
                average(scores.stream().map(JudgeScore::correctness).toList()),
                average(scores.stream().map(JudgeScore::completeness).toList()),
                average(scores.stream().map(JudgeScore::faithfulness).toList()),
                average(scores.stream().map(JudgeScore::citationQuality).toList()),
                average(scores.stream().map(JudgeScore::answerRelevancy).toList()),
                average(scores.stream().map(JudgeScore::overall).toList()));
    }

    private Map<String, Long> rootCauseCounts(List<GenerationCaseResult> scored) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String cause : List.of("RETRIEVAL", "GENERATION", "BOTH", "NONE")) {
            counts.put(cause, scored.stream().filter(item -> cause.equals(item.rootCause())).count());
        }
        return counts;
    }

    public boolean isJudgeAvailable() {
        return judgeClient.isAvailable();
    }

    public String judgeModelName() {
        return judgeModelName;
    }

    private List<ContextDocument> contextViews(List<Document> documents) {
        List<ContextDocument> views = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            views.add(new ContextDocument(i + 1,
                    value(doc.getMetadata().get("business_id")),
                    value(doc.getMetadata().get("source_name")),
                    value(doc.getMetadata().get("article_no")),
                    doc.getText(), doc.getScore()));
        }
        return List.copyOf(views);
    }

    private double average(List<? extends Number> values) {
        return round(values.stream().mapToDouble(Number::doubleValue).average().orElse(0.0));
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private List<String> safe(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private Double number(Object value) { return value instanceof Number number ? number.doubleValue() : null; }
    private String allowed(String value, List<String> allowed, String fallback) {
        return allowed.contains(value) ? value : fallback;
    }
    private String rootMessage(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? e.getClass().getSimpleName() : current.getMessage();
    }

    public record ContextDocument(int rank, String businessId, String sourceName,
                                  String articleNo, String text, Double score) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JudgeScore(
            int correctness,
            int completeness,
            int faithfulness,
            int citationQuality,
            int answerRelevancy,
            double overall,
            String reason,
            List<String> unsupportedClaims,
            List<String> missingPoints,
            List<String> citationIssues
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawJudgeComparison(
            @JsonProperty("evaluationA") JudgeScore evaluationA,
            @JsonProperty("evaluationB") JudgeScore evaluationB,
            String verdict,
            String rootCause,
            String comparisonReason
    ) {}

    public record JudgeComparison(JudgeScore goldEvaluation, JudgeScore ragEvaluation,
                                  String verdict, String rootCause, String comparisonReason,
                                  ModelTokenUsage tokenUsage) {}

    public record GenerationCaseResult(
            int sequence, int total, String id, String split, String category,
            String difficulty, String domain, String question, boolean answerable,
            String status, String error, String goldAnswer, String ragAnswer,
            List<ContextDocument> goldContext, List<ContextDocument> ragContext,
            JudgeScore goldEvaluation, JudgeScore ragEvaluation,
            String verdict, String rootCause, String comparisonReason,
            long goldGenerationMs, long retrievalMs, long ragGenerationMs,
            long judgeMs, long durationMs, ModelTokenUsage goldTokenUsage,
            ModelTokenUsage ragTokenUsage, ModelTokenUsage judgeTokenUsage
    ) {
        @JsonProperty("totalTokenUsage")
        public ModelTokenUsage totalTokenUsage() {
            long input = 0, output = 0, total = 0;
            boolean available = false;
            for (ModelTokenUsage usage : List.of(goldTokenUsage, ragTokenUsage, judgeTokenUsage)) {
                if (usage != null && usage.available()) {
                    input += usage.inputTokens();
                    output += usage.outputTokens();
                    total += usage.totalTokens();
                    available = true;
                }
            }
            return available ? new ModelTokenUsage(input, output, total, true)
                    : ModelTokenUsage.unavailable();
        }
    }

    public record AggregateScore(double correctness, double completeness,
                                 double faithfulness, double citationQuality,
                                 double answerRelevancy, double overall) {}

    public record TokenUsageSummary(long inputTokens, long outputTokens, long totalTokens,
                                    long measuredCalls, long unavailableCalls) {}

    public record GenerationSummary(
            String datasetId, String split, String judgeModel, int successThreshold,
            int topK, int candidateTopK, String retrievalMode, boolean queryRewrite,
            boolean rerank, long sampleSeed, int availableCases,
            int totalCases, int scoredCases, long failedCases,
            AggregateScore goldContextScore, AggregateScore ragScore, double oracleGap,
            long goldSuccessCases, long ragSuccessCases, Map<String, Long> rootCauseCounts,
            TokenUsageSummary goldGenerationTokens, TokenUsageSummary ragGenerationTokens,
            TokenUsageSummary judgeTokens, TokenUsageSummary overallTokens,
            double averageTokensPerCase,
            long durationMs, double averageLatencyMs
    ) {}
}
