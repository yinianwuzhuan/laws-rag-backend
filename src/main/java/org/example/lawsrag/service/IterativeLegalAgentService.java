package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 第一阶段受控法律Agent：拆题、逐题检索、生成初稿、审查缺口，并最多补查一轮知识库。
 * 它不负责向用户输出答案，也不暴露模型思维链；最终答复仍由LawChatService统一生成。
 */
@Slf4j
@Service
public class IterativeLegalAgentService {

    private static final String DECOMPOSITION_SYSTEM_PROMPT = """
            你是法律咨询系统的问题规划器，只负责判断问题是否需要拆解并生成可检索的子问题，不回答法律问题。

            只输出一个JSON对象：
            {
              "complex":true,
              "subQuestions":[
                {"id":"q1","question":"可独立检索的问题","purpose":"该问题要解决的用户诉求"}
              ],
              "missingFacts":["会实质影响法律判断、但用户尚未提供的事实"],
              "clarificationQuestion":"确有必要时的一句最小追问，否则为空字符串"
            }

            规则：
            1. 最多拆成3个子问题；每个子问题必须对应用户明确询问的一个法律事项，并能独立检索。
            2. 同一规则下的同义表达不得重复拆分。简单单一问题设置complex=false，并保留一个与原问题一致的子问题。
            3. 只保留用户已经给出的事实，不得新增主体、日期、金额、法律概念、争议类型或法律结论。
            4. 不得把猜测出的“专业关键词”当成用户事实；子问题应自然、准确，不预设答案。
            5. missingFacts只填写会改变结论或计算结果的关键事实。即使存在缺失事实，也应保留当前可回答的子问题。
            6. clarificationQuestion最多问一个最关键事实，不得把多个无关问题堆在一起。
            7. 不输出分析过程、法律答案或JSON之外的文字。
            """;

    private static final String DRAFT_SYSTEM_PROMPT = """
            你是法律咨询系统的内部答案起草器。请根据分组法律依据形成一份内部初稿，供后续审查使用。
            只能使用输入中的法律条文；逐项回应子问题；无法由条文支持的部分明确标记为“依据不足”。
            不得使用外部知识，不得虚构条文，不得展示推理过程，不必套用面向用户的固定标题格式。
            """;

    private static final String AUDIT_SYSTEM_PROMPT = """
            你是法律咨询系统的答案完整性与矛盾审查器，不直接回答用户，也不改写答案。

            只输出一个JSON对象：
            {
              "complete":false,
              "hasContradiction":false,
              "summary":"一句可展示的审查摘要",
              "gaps":[
                {"subQuestionId":"q1","description":"尚未被可靠回答或缺少依据的具体事项"}
              ],
              "contradictions":["初稿内部矛盾，或初稿结论与已给条文冲突的具体说明"],
              "followUpQueries":[
                {"subQuestionId":"q1","query":"用于补足缺口的独立法律检索问题","reason":"补查目的"}
              ],
              "externalSearchNeeded":false,
              "externalSearchReasonCode":"NONE|MISSING_INTERNAL_LAW|MISSING_IMPLEMENTATION_RULE|POSSIBLY_OUTDATED|EFFECTIVENESS_UNCERTAIN|OFFICIAL_STANDARD_REQUIRED",
              "externalSearchQueries":[
                {"subQuestionId":"q1","query":"去除个人信息后的官方法律检索问题","reason":"需要外部官方证据的原因"}
              ],
              "missingUserFacts":["必须由用户补充、无法通过查法条解决的事实"],
              "clarificationQuestion":"必要时的一句最小追问，否则为空字符串"
            }

            审查规则：
            1. 分别检查每个子问题是否被初稿回答、结论是否有直接条文支持、数字和期限是否与条文一致。
            2. 检查初稿内部是否前后矛盾，是否先给结论又推翻，是否把“有规定”错误说成“没有规定”。
            3. followUpQueries最多2条，只能针对可通过再次查询内部法律库弥补的证据缺口。
            4. 只有内部缺少相关法律、实施细则、最新版本、效力状态或官方标准时，才设置externalSearchNeeded=true。
            5. 如果仍可先查询内部法律库，优先生成followUpQueries；完成内部补查后再次审查仍不足，才生成externalSearchQueries。
            6. externalSearchQueries最多2条，必须是纯法律检索问题，严禁包含姓名、单位名称、身份证号、案号、地址、工资金额和银行流水等敏感事实。
            7. 不能通过查法条解决的事实缺失放入missingUserFacts，不得为此生成任何搜索词。
            8. 不得使用自身法律记忆补充结论；不得因为希望答案更丰富而制造无关缺口。
            9. 不输出思维过程或JSON之外的文字。
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final LawSearchService lawSearchService;
    private final OfficialLegalSearchTool officialLegalSearchTool;
    private final boolean enabled;
    private final int maxSubQuestions;
    private final int candidateTopK;
    private final int perQuestionFinalTopK;
    private final int maxFollowUpQueries;
    private final int maxTotalEvidence;
    private final int maxExternalSearchQueries;
    private final boolean rerankEnabled;

    public IterativeLegalAgentService(
            @Qualifier("dashScopeChatModel") ChatModel chatModel,
            ObjectMapper objectMapper,
            LawSearchService lawSearchService,
            OfficialLegalSearchTool officialLegalSearchTool,
            @Value("${laws.agentic-rag.iterative.enabled:true}") boolean enabled,
            @Value("${laws.agentic-rag.iterative.max-sub-questions:3}") int maxSubQuestions,
            @Value("${laws.agentic-rag.iterative.candidate-top-k:10}") int candidateTopK,
            @Value("${laws.agentic-rag.iterative.per-question-final-top-k:3}") int perQuestionFinalTopK,
            @Value("${laws.agentic-rag.iterative.max-follow-up-queries:2}") int maxFollowUpQueries,
            @Value("${laws.agentic-rag.iterative.max-total-evidence:9}") int maxTotalEvidence,
            @Value("${laws.agentic-rag.official-search.max-queries:2}") int maxExternalSearchQueries,
            @Value("${laws.retrieval.reranker.apply-by-default:true}") boolean rerankEnabled) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.lawSearchService = lawSearchService;
        this.officialLegalSearchTool = officialLegalSearchTool;
        this.enabled = enabled;
        this.maxSubQuestions = Math.max(1, Math.min(3, maxSubQuestions));
        this.candidateTopK = Math.max(1, candidateTopK);
        this.perQuestionFinalTopK = Math.max(1, perQuestionFinalTopK);
        this.maxFollowUpQueries = Math.max(0, Math.min(2, maxFollowUpQueries));
        this.maxTotalEvidence = Math.max(1, maxTotalEvidence);
        this.maxExternalSearchQueries = Math.max(0, Math.min(3, maxExternalSearchQueries));
        this.rerankEnabled = rerankEnabled;
    }

    public AgentExecution execute(
            String question,
            boolean queryRewriteEnabled,
            ProgressListener progressListener) {
        ProgressListener progress = progressListener == null ? ProgressListener.noop() : progressListener;
        if (!enabled) {
            return AgentExecution.fastPath(QuestionPlan.simple(question, true, 0));
        }

        progress.onProgress(ProgressEvent.running("QUESTION_DECOMPOSITION", "拆解法律问题",
                "正在判断该咨询是否包含多个需要分别取证的法律事项。"));
        QuestionPlan plan = plan(question);
        progress.onProgress(new ProgressEvent("QUESTION_DECOMPOSITION", "completed", "拆解法律问题",
                plan.complex() ? "已形成分项检索计划。" : "识别为单一法律问题，将执行单项检索与证据检查。",
                plan.subQuestions().stream()
                        .map(item -> item.id() + "：" + item.question()).toList(),
                Map.of("complex", plan.complex(), "subQuestionCount", plan.subQuestions().size(),
                        "fallback", plan.fallback()), plan.durationMs()));
        long totalStartedAt = System.nanoTime();
        List<EvidenceGroup> groups = new ArrayList<>();
        for (SubQuestion subQuestion : plan.subQuestions()) {
            groups.add(retrieve(question, subQuestion, queryRewriteEnabled, progress, false));
        }

        progress.onProgress(ProgressEvent.running("INITIAL_DRAFT", "生成内部初稿",
                "正在根据各子问题的独立证据形成待审查初稿。"));
        long draftStartedAt = System.nanoTime();
        String initialDraft = generateDraft(question, plan, groups);
        String workingDraft = initialDraft;
        long draftDuration = elapsedMillis(draftStartedAt);
        progress.onProgress(new ProgressEvent("INITIAL_DRAFT", "completed", "生成内部初稿",
                "初稿已生成，尚未直接返回用户，将继续执行完整性与矛盾检查。",
                List.of(), Map.of("draftLength", initialDraft.length()), draftDuration));

        progress.onProgress(ProgressEvent.running("ANSWER_AUDIT", "检查完整性与矛盾",
                "正在核对各项诉求是否覆盖、结论是否与法律依据一致。"));
        AnswerAudit audit = audit(question, plan, groups, initialDraft,
                AuditPhase.INITIAL, List.of());
        progress.onProgress(new ProgressEvent("ANSWER_AUDIT", "completed", "检查完整性与矛盾",
                audit.summary(), auditDetails(audit),
                Map.of("complete", audit.complete(), "hasContradiction", audit.hasContradiction(),
                        "gapCount", audit.gaps().size(),
                        "followUpQueryCount", audit.followUpQueries().size(),
                        "fallback", audit.fallback()), audit.durationMs()));

        boolean secondRoundUsed = false;
        Set<String> executedQueries = new LinkedHashSet<>();
        for (FollowUpQuery followUp : audit.followUpQueries()) {
            if (executedQueries.size() >= maxFollowUpQueries) break;
            String normalized = followUp.query() == null ? "" : followUp.query().trim();
            if (normalized.isBlank() || !executedQueries.add(normalized)) continue;
            secondRoundUsed = true;
            SubQuestion target = plan.subQuestions().stream()
                    .filter(item -> item.id().equalsIgnoreCase(followUp.subQuestionId()))
                    .findFirst()
                    .orElse(new SubQuestion(followUp.subQuestionId(), normalized, followUp.reason()));
            EvidenceGroup supplement = retrieve(question,
                    new SubQuestion(target.id(), normalized, followUp.reason()),
                    queryRewriteEnabled, progress, true);
            mergeSupplement(groups, target, supplement.documents());
        }

        // 内部补查后重新生成和审查，只有仍然存在法律资料缺口时才允许调用外部工具。
        if (secondRoundUsed) {
            workingDraft = generateDraft(question, plan, groups);
            audit = audit(question, plan, groups, workingDraft,
                    AuditPhase.AFTER_INTERNAL_SEARCH, List.copyOf(executedQueries));
            progress.onProgress(new ProgressEvent("ANSWER_AUDIT", "completed", "复核内部补查结果",
                    audit.summary(), auditDetails(audit),
                    Map.of("complete", audit.complete(),
                            "externalSearchNeeded", audit.externalSearchNeeded(),
                            "followUpQueryCount", audit.followUpQueries().size()),
                    audit.durationMs()));
        }

        boolean externalSearchUsed = executeOfficialSearch(question, plan, groups, audit, progress);
        if (externalSearchUsed) {
            workingDraft = generateDraft(question, plan, groups);
            audit = audit(question, plan, groups, workingDraft,
                    AuditPhase.AFTER_OFFICIAL_SEARCH, List.copyOf(executedQueries));
            progress.onProgress(new ProgressEvent("SOURCE_VALIDATION", "completed", "复核官方证据",
                    audit.summary(), auditDetails(audit),
                    Map.of("complete", audit.complete(),
                            "hasContradiction", audit.hasContradiction()), audit.durationMs()));
        }

        List<Document> documents = flattenAndAnnotate(groups);
        String finalInstructions = buildFinalInstructions(
                question, plan, audit, secondRoundUsed, externalSearchUsed);
        long totalDuration = elapsedMillis(totalStartedAt);
        log.info("[IterativeLegalAgent] complex=true, subQuestions={}, secondRound={}, "
                        + "evidence={}, total={}ms, question='{}'",
                plan.subQuestions().size(), secondRoundUsed, documents.size(), totalDuration, question);
        return new AgentExecution(false, plan, groups, initialDraft, audit, documents,
                finalInstructions, secondRoundUsed, externalSearchUsed, totalDuration);
    }

    QuestionPlan parsePlan(String question, String response, long durationMs) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(response));
        boolean complex = root.path("complex").asBoolean(false);
        List<SubQuestion> subQuestions = new ArrayList<>();
        JsonNode items = root.path("subQuestions");
        if (items.isArray()) {
            int index = 1;
            for (JsonNode item : items) {
                if (subQuestions.size() >= maxSubQuestions) break;
                String subQuestion = item.path("question").asText("").trim();
                if (subQuestion.isBlank()) continue;
                String id = item.path("id").asText("q" + index).trim();
                if (id.isBlank()) id = "q" + index;
                subQuestions.add(new SubQuestion(id, subQuestion,
                        item.path("purpose").asText("").trim()));
                index++;
            }
        }
        if (subQuestions.isEmpty()) subQuestions.add(new SubQuestion("q1", question, "回答用户问题"));
        if (subQuestions.size() <= 1) complex = false;
        return new QuestionPlan(complex, List.copyOf(subQuestions),
                stringArray(root.path("missingFacts")),
                root.path("clarificationQuestion").asText("").trim(), durationMs, false);
    }

    AnswerAudit parseAudit(String response, long durationMs) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(response));
        List<Gap> gaps = new ArrayList<>();
        if (root.path("gaps").isArray()) {
            for (JsonNode item : root.path("gaps")) {
                String description = item.path("description").asText("").trim();
                if (!description.isBlank()) {
                    gaps.add(new Gap(item.path("subQuestionId").asText("").trim(), description));
                }
            }
        }
        List<FollowUpQuery> followUps = new ArrayList<>();
        if (root.path("followUpQueries").isArray()) {
            for (JsonNode item : root.path("followUpQueries")) {
                if (followUps.size() >= maxFollowUpQueries) break;
                String query = item.path("query").asText("").trim();
                if (query.isBlank()) continue;
                followUps.add(new FollowUpQuery(
                        item.path("subQuestionId").asText("").trim(), query,
                        item.path("reason").asText("").trim()));
            }
        }
        String summary = root.path("summary").asText("").trim();
        if (summary.isBlank()) summary = gaps.isEmpty() ? "未发现需要补查的明显缺口。" : "发现待补足事项。";
        List<ExternalSearchQuery> externalQueries = new ArrayList<>();
        if (root.path("externalSearchQueries").isArray()) {
            for (JsonNode item : root.path("externalSearchQueries")) {
                if (externalQueries.size() >= maxExternalSearchQueries) break;
                String query = item.path("query").asText("").trim();
                if (query.isBlank()) continue;
                externalQueries.add(new ExternalSearchQuery(
                        item.path("subQuestionId").asText("").trim(), query,
                        item.path("reason").asText("").trim()));
            }
        }
        boolean externalNeeded = root.path("externalSearchNeeded").asBoolean(false)
                && !externalQueries.isEmpty();
        return new AnswerAudit(root.path("complete").asBoolean(gaps.isEmpty()),
                root.path("hasContradiction").asBoolean(false), summary,
                List.copyOf(gaps), stringArray(root.path("contradictions")),
                List.copyOf(followUps), externalNeeded,
                root.path("externalSearchReasonCode").asText("NONE").trim(),
                List.copyOf(externalQueries), stringArray(root.path("missingUserFacts")),
                root.path("clarificationQuestion").asText("").trim(), durationMs, false);
    }

    private QuestionPlan plan(String question) {
        long startedAt = System.nanoTime();
        try {
            String response = ChatClient.create(chatModel).prompt()
                    .system(DECOMPOSITION_SYSTEM_PROMPT)
                    .user("用户问题：\n" + question)
                    .call().content();
            return parsePlan(question, response, elapsedMillis(startedAt));
        } catch (Exception e) {
            log.warn("[IterativeLegalAgent] 问题拆解失败，降级为单问题检索: {}", rootMessage(e));
            return QuestionPlan.simple(question, true, elapsedMillis(startedAt));
        }
    }

    private EvidenceGroup retrieve(
            String overallQuestion,
            SubQuestion subQuestion,
            boolean queryRewriteEnabled,
            ProgressListener progress,
            boolean supplemental) {
        String stage = supplemental ? "GAP_RETRIEVAL" : "SUBQUESTION_RETRIEVAL";
        String title = supplemental ? "针对缺口二次检索" : "分项检索法律依据";
        progress.onProgress(new ProgressEvent(stage, "running", title,
                (supplemental ? "正在补查：" : "正在检索：") + subQuestion.question(),
                List.of(), Map.of("subQuestionId", subQuestion.id(),
                        "supplemental", supplemental), 0));
        LawSearchService.DocumentSearchExecution execution =
                lawSearchService.searchDocumentsWithTrace(
                        subQuestion.question(), Math.max(candidateTopK, perQuestionFinalTopK),
                        perQuestionFinalTopK, Map.of(), RetrievalMode.HYBRID,
                        rerankEnabled, queryRewriteEnabled,
                        LawSearchService.SearchProgressListener.noop(), overallQuestion);
        List<Document> documents = execution.documents();
        LawSearchService.SearchExecution trace = execution.trace();
        List<String> details = new ArrayList<>();
        details.add("子问题：" + subQuestion.question());
        details.add("融合候选：" + trace.retrievalCandidates().size() + " 条；最终依据："
                + documents.size() + " 条");
        details.addAll(documents.stream().map(this::documentLabel).distinct().limit(3).toList());
        progress.onProgress(new ProgressEvent(stage, "completed", title,
                documents.isEmpty() ? "本轮未找到直接相关条文。" : "已完成该子问题的独立召回与重排。",
                details, Map.of("subQuestionId", subQuestion.id(),
                        "candidateCount", trace.retrievalCandidates().size(),
                        "evidenceCount", documents.size(), "supplemental", supplemental),
                trace.totalDurationMs()));
        return new EvidenceGroup(subQuestion, new ArrayList<>(documents), supplemental);
    }

    private String generateDraft(String question, QuestionPlan plan, List<EvidenceGroup> groups) {
        String prompt = "用户原始问题：\n" + question
                + "\n\n需要回答的子问题：\n" + formatSubQuestions(plan)
                + "\n\n分组法律依据：\n" + formatEvidenceGroups(groups);
        String content = ChatClient.create(chatModel).prompt()
                .system(DRAFT_SYSTEM_PROMPT).user(prompt).call().content();
        return content == null ? "" : content.trim();
    }

    private AnswerAudit audit(
            String question,
            QuestionPlan plan,
            List<EvidenceGroup> groups,
            String draft,
            AuditPhase phase,
            List<String> executedInternalQueries) {
        long startedAt = System.nanoTime();
        try {
            String prompt = "用户原始问题：\n" + question
                    + "\n\n子问题清单：\n" + formatSubQuestions(plan)
                    + "\n\n分组法律依据：\n" + formatEvidenceGroups(groups)
                    + "\n\n待审查初稿：\n" + draft
                    + "\n\n当前审查阶段：\n" + phase.instructions(executedInternalQueries);
            String response = ChatClient.create(chatModel).prompt()
                    .system(AUDIT_SYSTEM_PROMPT).user(prompt).call().content();
            return normalizeAuditForPhase(
                    parseAudit(response, elapsedMillis(startedAt)), phase);
        } catch (Exception e) {
            log.warn("[IterativeLegalAgent] 初稿审查失败，跳过二次检索并继续最终生成: {}", rootMessage(e));
            return AnswerAudit.fallback(elapsedMillis(startedAt));
        }
    }

    AnswerAudit normalizeAuditForPhase(AnswerAudit audit, AuditPhase phase) {
        if (phase == AuditPhase.INITIAL) return audit;
        if (phase == AuditPhase.AFTER_OFFICIAL_SEARCH) {
            return new AnswerAudit(audit.complete(), audit.hasContradiction(), audit.summary(),
                    audit.gaps(), audit.contradictions(), List.of(), false, "NONE", List.of(),
                    audit.missingUserFacts(), audit.clarificationQuestion(), audit.durationMs(),
                    audit.fallback());
        }

        List<ExternalSearchQuery> externalQueries = new ArrayList<>(audit.externalSearchQueries());
        if (externalQueries.isEmpty() && !audit.gaps().isEmpty()) {
            audit.followUpQueries().stream().limit(maxExternalSearchQueries).forEach(item ->
                    externalQueries.add(new ExternalSearchQuery(item.subQuestionId(), item.query(),
                            "内部补查后仍未解决：" + item.reason())));
        }
        boolean externalNeeded = !externalQueries.isEmpty();
        String reasonCode = externalNeeded
                ? (audit.externalSearchReasonCode().isBlank()
                        || "NONE".equalsIgnoreCase(audit.externalSearchReasonCode())
                        ? "MISSING_INTERNAL_LAW" : audit.externalSearchReasonCode())
                : "NONE";
        return new AnswerAudit(audit.complete(), audit.hasContradiction(), audit.summary(),
                audit.gaps(), audit.contradictions(), List.of(), externalNeeded, reasonCode,
                List.copyOf(externalQueries), audit.missingUserFacts(),
                audit.clarificationQuestion(), audit.durationMs(), audit.fallback());
    }

    private void mergeSupplement(
            List<EvidenceGroup> groups, SubQuestion target, List<Document> supplement) {
        for (int i = 0; i < groups.size(); i++) {
            EvidenceGroup group = groups.get(i);
            if (!group.subQuestion().id().equalsIgnoreCase(target.id())) continue;
            List<Document> merged = deduplicate(supplement, group.documents());
            groups.set(i, new EvidenceGroup(group.subQuestion(), merged, true));
            return;
        }
        groups.add(new EvidenceGroup(target, new ArrayList<>(supplement), true));
    }

    private boolean executeOfficialSearch(
            String overallQuestion,
            QuestionPlan plan,
            List<EvidenceGroup> groups,
            AnswerAudit audit,
            ProgressListener progress) {
        if (!audit.externalSearchNeeded() || audit.externalSearchQueries().isEmpty()
                || maxExternalSearchQueries == 0) {
            return false;
        }
        progress.onProgress(new ProgressEvent("TOOL_DECISION", "completed", "规划外部工具调用",
                "内部依据仍不足，Agent 决定调用只读官方法律搜索工具。",
                audit.externalSearchQueries().stream()
                        .map(item -> "官方检索：" + item.query()).toList(),
                Map.of("tool", "official_legal_search",
                        "reasonCode", audit.externalSearchReasonCode(),
                        "queryCount", audit.externalSearchQueries().size()), 0));

        int evidenceCount = 0;
        int executedCount = 0;
        Set<String> executedQueries = new LinkedHashSet<>();
        for (ExternalSearchQuery external : audit.externalSearchQueries()) {
            if (executedCount >= maxExternalSearchQueries) break;
            String query = external.query() == null ? "" : external.query().trim();
            if (query.isBlank() || !executedQueries.add(query)) continue;
            executedCount++;
            progress.onProgress(new ProgressEvent("OFFICIAL_WEB_SEARCH", "running", "搜索官方法律来源",
                    "正在执行：" + query, List.of(),
                    Map.of("tool", "official_legal_search", "queryIndex", executedCount), 0));
            OfficialLegalSearchTool.SearchResponse response =
                    officialLegalSearchTool.searchOfficialLegalSources(query);
            SubQuestion target = plan.subQuestions().stream()
                    .filter(item -> item.id().equalsIgnoreCase(external.subQuestionId()))
                    .findFirst()
                    .orElse(new SubQuestion(external.subQuestionId(), query, external.reason()));
            List<Document> officialDocuments = officialLegalSearchTool.toDocuments(
                    response, target.id() + "：" + target.question());
            mergeSupplement(groups, target, officialDocuments);
            evidenceCount += officialDocuments.size();
            List<String> details = new ArrayList<>();
            details.add("查询：" + response.executedQuery());
            details.add("可验证官方证据：" + officialDocuments.size() + " 条");
            response.evidence().stream().limit(3).forEach(item ->
                    details.add(item.issuingAuthority() + "：" + item.title()
                            + "（" + item.validityStatus() + "）"));
            progress.onProgress(new ProgressEvent("OFFICIAL_WEB_SEARCH", "completed", "搜索官方法律来源",
                    response.summary(), details,
                    Map.of("tool", "official_legal_search", "queryIndex", executedCount,
                            "evidenceCount", officialDocuments.size(),
                            "failureCount", response.failures().size()), response.durationMs()));
        }
        progress.onProgress(new ProgressEvent("SOURCE_VALIDATION", "running", "校验并合并官方证据",
                evidenceCount == 0 ? "未获得可验证的官方正文，将保守处理剩余问题。"
                        : "正在将已验证的官方正文与内部法律依据合并。",
                List.of("工具调用：" + executedCount + " 次", "新增官方证据：" + evidenceCount + " 条"),
                Map.of("toolCalls", executedCount, "officialEvidenceCount", evidenceCount), 0));
        log.info("[OfficialSearchPlan] question='{}', reasonCode={}, calls={}, evidence={}",
                overallQuestion, audit.externalSearchReasonCode(), executedCount, evidenceCount);
        return executedCount > 0;
    }

    private List<Document> flattenAndAnnotate(List<EvidenceGroup> groups) {
        Map<String, AnnotatedEvidence> unique = new LinkedHashMap<>();
        for (EvidenceGroup group : groups) {
            for (Document document : group.documents()) {
                String key = documentKey(document);
                AnnotatedEvidence annotated = unique.computeIfAbsent(key,
                        ignored -> new AnnotatedEvidence(document, new ArrayList<>()));
                String label = group.subQuestion().id() + "：" + group.subQuestion().question();
                if (!annotated.subQuestions().contains(label)) annotated.subQuestions().add(label);
            }
        }
        return unique.values().stream().limit(maxTotalEvidence).map(item -> {
            Map<String, Object> metadata = new LinkedHashMap<>(item.document().getMetadata());
            metadata.put("agent_sub_questions", String.join(" | ", item.subQuestions()));
            Document.Builder builder = Document.builder()
                    .text(item.document().getText()).metadata(metadata);
            if (item.document().getScore() != null) builder.score(item.document().getScore());
            return builder.build();
        }).toList();
    }

    private List<Document> deduplicate(List<Document> first, List<Document> second) {
        Map<String, Document> unique = new LinkedHashMap<>();
        first.forEach(document -> unique.putIfAbsent(documentKey(document), document));
        second.forEach(document -> unique.putIfAbsent(documentKey(document), document));
        return new ArrayList<>(unique.values());
    }

    private String buildFinalInstructions(
            String question, QuestionPlan plan, AnswerAudit audit,
            boolean secondRoundUsed, boolean externalSearchUsed) {
        StringBuilder result = new StringBuilder();
        result.append("用户原始问题：").append(question)
                .append("\n请在最终答复中逐项处理以下事项：\n")
                .append(formatSubQuestions(plan));
        if (!plan.missingFacts().isEmpty() || !audit.missingUserFacts().isEmpty()) {
            Set<String> missing = new LinkedHashSet<>(plan.missingFacts());
            missing.addAll(audit.missingUserFacts());
            result.append("\n以下事实可能影响判断；如现有信息不足，只提出必要的针对性补充问题，"
                    + "不得自行假设：\n- ")
                    .append(String.join("\n- ", missing));
        }
        if (!audit.gaps().isEmpty()) {
            result.append("\n内部完整性检查发现下列事项需要在最终答复中特别核对；"
                    + "只能依据最终提供的法条回答，仍无依据则自然说明暂无法确认：\n- ")
                    .append(String.join("\n- ", audit.gaps().stream()
                            .map(Gap::description).toList()));
        }
        if (audit.hasContradiction()) {
            result.append("\n最终答复必须消除初稿中的冲突，只保留与所列条文一致的唯一结论。"
                    + "不得向用户提及初稿或审查过程。");
        }
        if (secondRoundUsed) {
            result.append("\n已针对证据缺口补充查询；请综合全部最终依据作答，不得描述补查过程。");
        }
        if (externalSearchUsed) {
            result.append("\n部分依据来自经域名校验和正文抓取的官方网页。"
                    + "只有带source_url的官方证据可以作为联网依据；引用时必须保留对应链接，"
                    + "不得把搜索摘要、失败页面或模型记忆写入答案。"
                    + "如果效力状态仍未确认，应明确提示需要进一步核验，不得擅自断言现行有效。");
        }
        return result.toString();
    }

    private List<String> auditDetails(AnswerAudit audit) {
        List<String> details = new ArrayList<>();
        if (audit.gaps().isEmpty()) details.add("各子问题均已完成覆盖检查");
        else audit.gaps().stream().limit(3)
                .forEach(gap -> details.add("待补足：" + gap.description()));
        if (audit.hasContradiction()) details.add("发现结论或依据冲突，最终生成前必须消除");
        if (!audit.followUpQueries().isEmpty()) {
            details.add("计划补查：" + audit.followUpQueries().size() + " 项");
        }
        if (audit.externalSearchNeeded()) {
            details.add("需要官方搜索：" + audit.externalSearchReasonCode());
        }
        if (!audit.missingUserFacts().isEmpty()) {
            details.add("仍需用户确认的事实：" + audit.missingUserFacts().size() + " 项");
        }
        return details;
    }

    private String formatSubQuestions(QuestionPlan plan) {
        return String.join("\n", plan.subQuestions().stream()
                .map(item -> item.id() + ". " + item.question()
                        + (item.purpose().isBlank() ? "" : "（目标：" + item.purpose() + "）"))
                .toList());
    }

    private String formatEvidenceGroups(List<EvidenceGroup> groups) {
        StringBuilder result = new StringBuilder();
        int evidenceIndex = 1;
        for (EvidenceGroup group : groups) {
            result.append("\n【").append(group.subQuestion().id()).append("：")
                    .append(group.subQuestion().question()).append("】\n");
            if (group.documents().isEmpty()) {
                result.append("（本组暂无直接法律依据）\n");
                continue;
            }
            for (Document document : group.documents()) {
                result.append("[E").append(evidenceIndex++).append("] ")
                        .append(documentLabel(document)).append("\n");
                if ("official_web".equals(document.getMetadata().get("source_type"))) {
                    result.append("发布机关：")
                            .append(document.getMetadata().getOrDefault("issuing_authority", "官方机关"))
                            .append("\n效力标记：")
                            .append(document.getMetadata().getOrDefault("validity_status", "未核验"))
                            .append("\n原始链接：")
                            .append(document.getMetadata().getOrDefault("source_url", ""))
                            .append("\n");
                }
                result.append(document.getText()).append("\n");
            }
        }
        return result.toString();
    }

    private String documentLabel(Document document) {
        return document.getMetadata().getOrDefault("source_name", "未知法律文件") + " "
                + document.getMetadata().getOrDefault("article_no", "");
    }

    private String documentKey(Document document) {
        Object businessId = document.getMetadata().get("business_id");
        if (businessId != null && !String.valueOf(businessId).isBlank()) {
            return String.valueOf(businessId).toLowerCase(Locale.ROOT);
        }
        return (documentLabel(document) + "|" + document.getText()).toLowerCase(Locale.ROOT);
    }

    private List<String> stringArray(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank() && !values.contains(value)) values.add(value);
        }
        return List.copyOf(values);
    }

    private String extractJson(String response) {
        if (response == null) return "";
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) return response.trim();
        return response.substring(start, end + 1);
    }

    private long elapsedMillis(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record SubQuestion(String id, String question, String purpose) {}

    public record QuestionPlan(
            boolean complex,
            List<SubQuestion> subQuestions,
            List<String> missingFacts,
            String clarificationQuestion,
            long durationMs,
            boolean fallback) {
        static QuestionPlan simple(String question, boolean fallback, long durationMs) {
            return new QuestionPlan(false,
                    List.of(new SubQuestion("q1", question, "回答用户问题")),
                    List.of(), "", durationMs, fallback);
        }
    }

    public record EvidenceGroup(
            SubQuestion subQuestion,
            List<Document> documents,
            boolean supplemented) {}

    public record Gap(String subQuestionId, String description) {}

    public record FollowUpQuery(String subQuestionId, String query, String reason) {}

    public record ExternalSearchQuery(String subQuestionId, String query, String reason) {}

    public record AnswerAudit(
            boolean complete,
            boolean hasContradiction,
            String summary,
            List<Gap> gaps,
            List<String> contradictions,
            List<FollowUpQuery> followUpQueries,
            boolean externalSearchNeeded,
            String externalSearchReasonCode,
            List<ExternalSearchQuery> externalSearchQueries,
            List<String> missingUserFacts,
            String clarificationQuestion,
            long durationMs,
            boolean fallback) {
        static AnswerAudit fallback(long durationMs) {
            return new AnswerAudit(true, false,
                    "审查器暂不可用，已按初次检索依据继续生成最终答复。",
                    List.of(), List.of(), List.of(), false, "NONE", List.of(),
                    List.of(), "", durationMs, true);
        }
    }

    public record AgentExecution(
            boolean fastPath,
            QuestionPlan plan,
            List<EvidenceGroup> evidenceGroups,
            String initialDraft,
            AnswerAudit audit,
            List<Document> documents,
            String finalInstructions,
            boolean secondRoundUsed,
            boolean externalSearchUsed,
            long durationMs) {
        static AgentExecution fastPath(QuestionPlan plan) {
            return new AgentExecution(true, plan, List.of(), "", null,
                    List.of(), "", false, false, plan.durationMs());
        }
    }

    private record AnnotatedEvidence(Document document, List<String> subQuestions) {}

    enum AuditPhase {
        INITIAL,
        AFTER_INTERNAL_SEARCH,
        AFTER_OFFICIAL_SEARCH;

        String instructions(List<String> executedInternalQueries) {
            return switch (this) {
                case INITIAL -> "首次审查。若缺口可能由内部法律库补足，优先生成followUpQueries；"
                        + "此时通常不要直接安排官方搜索。";
                case AFTER_INTERNAL_SEARCH -> "内部补查已经执行完毕，不得再次生成followUpQueries。"
                        + "已经执行的内部查询为：" + formatQueries(executedInternalQueries)
                        + "。如果现有证据仍缺少相关法律、实施条例、司法解释、现行标准或效力信息，"
                        + "必须设置externalSearchNeeded=true并生成externalSearchQueries；"
                        + "如果缺少的是案件事实，则只写入missingUserFacts；否则保守结束。";
                case AFTER_OFFICIAL_SEARCH -> "官方来源搜索已经执行完毕。不得再生成followUpQueries或"
                        + "externalSearchQueries，externalSearchNeeded必须为false；只评估最终证据仍有哪些缺口。";
            };
        }

        private static String formatQueries(List<String> queries) {
            if (queries == null || queries.isEmpty()) return "无";
            return String.join("；", queries);
        }
    }

    public record ProgressEvent(
            String stage,
            String status,
            String title,
            String summary,
            List<String> details,
            Map<String, Object> metadata,
            long durationMs) {
        static ProgressEvent running(String stage, String title, String summary) {
            return new ProgressEvent(stage, "running", title, summary,
                    List.of(), Map.of(), 0);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(ProgressEvent event);

        static ProgressListener noop() {
            return ignored -> {};
        }
    }
}
