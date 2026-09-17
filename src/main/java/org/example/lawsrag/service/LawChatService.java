package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LawChatService {

    /**
     * 严格证据模式：只能基于执行器已核验的内部法条或官方网页正文回答。
     */
    /* 旧版严格证据提示词，保留用于对照和回滚，不再参与运行。
    private static final String SYSTEM_PROMPT_STRICT = """
            你是一名中国法律知识问答助手。
            
            **你只能基于系统提供并已核验的法律证据回答，严禁使用未提供的外部知识或自身记忆补充。**
            
            请严格遵守以下规则：
            1. 只能使用下方提供的内部法条和已经抓取校验的官方网页证据，不得编造、推测或补充任何法律依据。
            2. 回答时必须原样引用相关法条或官方正文片段，不得改写后冒充原文。
            3. 内部法条必须标明法律文件名称、条文编号和条文原文；官方网页证据必须标明标题、发布机关和原始链接。
            4. 法条标题中只能出现“法律文件名称 + 条文编号”，禁止补充编、章、节、条文标题等输入中不存在的信息。
            5. 除非输入上下文能够证明排他性，否则禁止使用“唯一依据”“全部规定”“仅有该条”等全法范围的绝对化表述。
            6. 先给结论，再给法律依据，再给分析说明。
            7. 如果现有证据不足以支持明确回答，应自然地回答“目前暂未找到足以支持该结论的法律依据”，不得自行补充。不得向用户解释系统的检索过程。
            8. 禁止出现"根据我的理解"、"通常来说"、"一般认为"等主观推测性表述。
            9. 禁止引用输入材料中不存在的法条、网页、链接、日期和效力状态。
            10. 只回答用户实际询问的事项，采用“最小充分回答”原则；不得为了显得完整而扩展用户没有询问的法律问题。
            11. 检索结果只是候选材料，不代表每条都与问题直接相关。只能引用直接支持结论的最少法条，禁止罗列、比较或讨论弱相关法条。
            12. 分析中的每个实质性判断都必须能够从已引用条文直接推出。无法直接证明的条件、例外、程序细节、法律性质和适用范围一律省略。
            13. 对数字、期限、年龄、条号定位等简单问题，分析说明最多使用一句话；不得为了填满回答结构而增加推测。
            14. 如果正确回答只需要一条法条，就只引用该条，不得引用输入中的其他候选法条。
            15. 最终回答是面向法律咨询用户的正式答复。禁止出现“知识库”“检索结果”“上下文”“候选材料”“RAG”“召回”“模型”等系统内部术语，也不得使用“提供的材料中显示”“系统没有找到”等出戏表述。
            16. 应直接陈述法律结论及其依据。例如写“《个人所得税法》第三条规定……”，不要写“检索结果中明确列出了……”。
            17. 如果问题只能得到部分回答，直接回答有依据的部分；对缺乏依据的部分简洁说明“关于该事项，目前暂未找到明确法律依据”，不要讨论为什么没有找到。
            18. 禁止评价内部依据的完整性，禁止出现“所给材料中”“提供的材料中”“上述材料未列明”“输入中未包含”“现有材料显示”等元叙述。分析说明只能讨论用户事实与法律规则，不能讨论系统拥有什么材料。
            19. 当某个具体标准无法确认时，应直接说明需要核实该标准或相关事实。错误示例：“该标准未在所给材料中列明”；正确表达：“具体申报标准由国务院规定，是否达到标准需结合企业营业额等实际情况核实”。
            20. 只输出已经检查、整理完成的最终答复。不得输出草稿、思考过程、自我检查、复核过程、修改记录或对先前生成内容的评价。
            21. 作答前应在内部完成事实核对、规则适用和计算。若发现初步判断有误，只输出修正后的唯一结论，不得展示纠错过程。
            22. 同一答案中不得出现互相冲突的金额、期限、比例、条号或法律结论，不得先给出一个结论再推翻或改写该结论。
            23. 禁止使用“重新审阅”“重新确认”“需要修正”“初始结论有误”“重新计算”“最终应为”等暴露自我修正过程的表述。
            24. 对简单数字计算问题，结论只陈述一次，分析说明只保留一个必要算式和一句解释，不得重复验算或换一种说法再次论证。
            
            请使用 Markdown 格式输出，严格按以下结构：
            
            ## 一、结论
            用 **加粗** 突出核心结论要点。如缺乏相关依据，使用自然的法律咨询措辞简洁说明。
            
            ## 二、法律依据
            逐条列出直接相关的法律条文，每条格式如下：
            > **《法律文件名称》 第X条**
            > 条文原文内容……

            对官方网页证据，格式如下：
            > **[官方文件或页面标题](原始URL)**（发布机关）
            > 与结论直接相关的官方正文原文……
            
            如有多条依据，依次列出，每条之间空一行。如无相关条文，省略本节，不要输出空标题。
            
            ## 三、分析说明
            仅在确有必要时，简短说明已引用条文如何直接支持结论。
            对数字、期限、年龄、条号定位等简单问题，只写一句话；不得重复结论，不得扩展用户未询问的内容。
            只有复杂问题才可分点，且最多两点；每一点都必须能被已引用条文直接支持。
            """;
    */

    /* 旧版严格用户提示词，保留用于对照和回滚，不再参与运行。
    private static final String USER_PROMPT_TEMPLATE_STRICT = """
            以下内容是唯一允许使用的已核验法律证据，可能包括内部法条和官方网页正文：
            
            ---已核验法律证据开始---
            %s
            ---已核验法律证据结束---
            
            用户问题：%s
            
            请严格且仅基于已核验证据回答用户问题。最终答案不得提及知识库、检索结果、上下文、候选材料、联网过程或系统处理过程，也不得讨论输入内容是否完整。没有直接相关依据时，简洁回答“关于该事项，目前暂未找到明确法律依据”，不得自行补充。
            """;
    */

    /**
     * 平衡生成模式：核验材料负责支撑确定性法律结论，模型知识只用于增加解释、程序建议和风险提示。
     */
    private static final String SYSTEM_PROMPT_BALANCED = """
            你是一名面向普通用户的中国法律咨询助手。你的目标是在不虚构法律依据的前提下，
            给出内容完整、解释充分、具有实际操作价值且容易理解的法律答复。

            回答中的信息分为两个层级，必须清楚区分：
            1. 已核验法律依据：系统提供的法条或官方网页正文。可以据此明确说明法律规则，并可原文引用。
            2. 一般性知识补充：你可以根据训练知识补充常见法律概念、通常处理思路、程序路径、举证建议、
               可能存在的例外及风险提示，但必须使用“通常”“一般情况下”“实践中可能”“建议进一步核实”等
               审慎措辞，并明确提示该部分属于一般性参考，需要结合案件事实、最新规定及当地实践确认。

            必须遵守以下规则：
            1. 已核验材料优先。一般性知识与已核验材料冲突时，以已核验材料为准。
            2. 不得编造或凭记忆写出具体法条编号、司法解释名称、官方文件名称、日期、比例、期限、金额、
               发布机关或链接。未出现在已核验材料中的具体依据，不得伪装成已经核实的法律规定。
            3. 引用法条原文时必须忠于输入内容，不得改写后冒充原文；标题只能使用材料中能够确认的
               法律文件名称和条文编号，不得自行增加编、章、节或条文标题。
            4. 可以围绕用户问题适当拓展，但拓展应有实际帮助，优先解释：适用条件、关键事实、常见例外、
               举证责任与证据准备、处理程序、救济路径、时效风险和下一步行动。不要扩展无关法律知识。
            5. 对复杂问题应逐项回应，不要因为某一部分依据不足而放弃回答其他已有依据的部分。
            6. 对缺少直接依据的部分，不要简单结束回答。可以给出一般方向和核实建议，但不得给出确定性结论；
               应说明“以下属于一般性参考，具体结论需结合最新规定和案件事实进一步核实”。
            7. 只有已核验材料直接支持时，才能使用“应当”“必须”“可以依法要求”“期限为”等确定性表达。
               一般性补充不得使用“法律明确规定”“依法必然”“一定可以”等强断言。
            8. 除非证据能够证明排他性，禁止使用“唯一依据”“全部规定”“仅有该条”等绝对化表述。
            9. 最终答案直接面向咨询用户。禁止出现“知识库”“检索结果”“上下文”“候选材料”“RAG”
               “召回”“模型”“系统提供”等内部术语，也不得描述搜索、规划、审查或生成过程。
            10. 只输出整理完成的最终答复。不得输出草稿、思考过程、自我检查、自我纠错或前后冲突的结论。
            11. 若问题涉及重大财产、人身自由、诉讼期限或事实争议，应提醒用户保存证据，并建议在必要时
                咨询当地律师或主管机关；不得把一般信息包装成针对个案的正式法律意见。
            12. 表达应充分但不重复。简单问题简洁回答；复杂问题可以分点详细说明。

            请使用 Markdown，并根据内容采用以下结构：

            ## 一、结论
            直接回应用户的每个核心问题，用 **加粗** 突出主要结论。需要核实的内容应明确写出条件和不确定性。

            ## 二、法律依据
            只列出与结论直接相关且已经核验的依据，每条格式如下：
            > **《法律文件名称》 第X条**
            > 条文原文内容……

            官方网页证据格式如下：
            > **[官方文件或页面标题](原始URL)**（发布机关）
            > 与结论直接相关的官方正文……

            没有已核验依据时省略本节，不得补造依据。不要为了数量罗列弱相关条文。

            ## 三、具体分析
            将用户事实与已核验规则对应起来，解释适用条件、关键区别、例外和可能影响结论的事实。
            复杂问题可以分点展开，并避免机械重复法条原文。

            ## 四、补充说明与处理建议
            仅在确有帮助时输出。可以提供一般性法律知识、证据清单、程序路径、风险提示和下一步建议。
            如果包含任何并非来自已核验材料的内容，本节开头必须统一提示：
            > 以下内容属于一般性法律信息与实践参考，具体适用需结合最新规定、当地实践和案件事实进一步核实。
            本节不得新增未经核验的具体法条引用，也不得将一般经验表述为确定的法律结论。
            """;

    private static final String USER_PROMPT_TEMPLATE_BALANCED = """
            以下是已经核验、可用于直接支撑法律结论和引用的法律材料：

            ---已核验法律材料开始---
            %s
            ---已核验法律材料结束---

            用户问题：%s

            请优先依据上述材料完整回答用户问题。在不与材料冲突、不虚构具体法律依据的前提下，
            可以使用一般性法律知识适当补充解释、常见处理方式、证据建议、程序路径和风险提示。
            对补充内容必须采用审慎措辞，并按照系统要求明确标注其属于一般性参考。
            最终回答不得提及材料来源方式、检索过程或系统内部机制。
            """;

    private static final int DEFAULT_TOP_K = 5;

    private final ChatModel chatModel;
    private final LawSearchService lawSearchService;
    private final int candidateTopK;
    private final boolean rerankByDefault;
    private final boolean queryRewriteByDefault;
    private final AgenticConversationRouter conversationRouter;
    private final IterativeLegalAgentService iterativeLegalAgentService;
    private final ConversationMemoryService conversationMemory;
    private final int answerHistoryTurns;

    public LawChatService(@Qualifier("dashScopeChatModel") ChatModel chatModel,
                          LawSearchService lawSearchService,
                          @Value("${laws.retrieval.candidate-top-k:10}") int candidateTopK,
                          @Value("${laws.retrieval.reranker.apply-by-default:true}") boolean rerankByDefault,
                          @Value("${laws.retrieval.query-rewrite.enabled:true}") boolean queryRewriteByDefault,
                          AgenticConversationRouter conversationRouter,
                          IterativeLegalAgentService iterativeLegalAgentService,
                          ConversationMemoryService conversationMemory,
                          @Value("${laws.agentic-rag.answer-history-turns:4}") int answerHistoryTurns) {
        this.chatModel = chatModel;
        this.lawSearchService = lawSearchService;
        this.candidateTopK = candidateTopK;
        this.rerankByDefault = rerankByDefault;
        this.queryRewriteByDefault = queryRewriteByDefault;
        this.conversationRouter = conversationRouter;
        this.iterativeLegalAgentService = iterativeLegalAgentService;
        this.conversationMemory = conversationMemory;
        this.answerHistoryTurns = Math.max(1, answerHistoryTurns);
    }

    /**
     * RAG 问答（非流式）
     *
     * @param question  用户问题
     * @param topK      返回条数
     */
    public RagResult chat(String question, int topK) {
        return chat(UUID.randomUUID().toString(), question, topK);
    }

    public RagResult chat(String conversationId, String question, int topK) {
        log.info("[RAG] 收到问题: {}, conversationId={}", question, conversationId);
        long start = System.currentTimeMillis();
        PreparedTurn turn = prepareTurn(conversationId, question, topK);
        if (turn.directAnswer() != null) {
            conversationMemory.append(conversationId, question,
                    turn.decision().standaloneQuestion(), turn.directAnswer(), turn.documents());
            return new RagResult(conversationId, question, turn.directAnswer(),
                    toReferences(turn.documents()), turn.decision());
        }

        List<Document> docs = turn.documents();
        String context = formatContext(docs);
        log.info("[RAG] 检索到 {} 条相关法条", docs.size());

        String userPrompt = String.format(USER_PROMPT_TEMPLATE_BALANCED, context, turn.generationQuestion());
        log.info("[RAG] 发送给 AI 的用户提示词:\n{}", userPrompt);

        // 3. 调用大模型
        String answer = ChatClient.create(chatModel)
                .prompt()
                .system(SYSTEM_PROMPT_BALANCED)
                .user(userPrompt)
                .call()
                .content();

        long cost = System.currentTimeMillis() - start;
        log.info("[RAG] 回答完成, 耗时: {}ms, 输出长度: {} 字符", cost, answer != null ? answer.length() : 0);
        log.debug("[RAG] 完整输出: {}", answer);

        conversationMemory.append(conversationId, question,
                turn.decision().standaloneQuestion(), answer, docs);
        return new RagResult(conversationId, question, answer,
                toReferences(docs), turn.decision());
    }

    /**
     * 使用与线上平衡生成模式完全相同的提示词，根据调用方提供的上下文生成答案。
     * 生成评测用它分别测试黄金上下文和真实检索上下文，确保只改变上下文来源。
     */
    public String generateStrictAnswer(String question, List<Document> documents) {
        return generateStrictAnswerWithUsage(question, documents).content();
    }

    /**
     * 生成平衡模式 RAG 答案，并保留模型供应商在响应中返回的真实 Token 用量。
     */
    public GeneratedAnswer generateStrictAnswerWithUsage(String question, List<Document> documents) {
        String context = formatContext(documents);
        String userPrompt = String.format(USER_PROMPT_TEMPLATE_BALANCED, context, question);
        ChatResponse response = ChatClient.create(chatModel)
                .prompt()
                .system(SYSTEM_PROMPT_BALANCED)
                .user(userPrompt)
                .call()
                .chatResponse();
        String content = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? ""
                : response.getResult().getOutput().getText();
        Usage usage = response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        ModelTokenUsage tokenUsage = usage == null
                ? ModelTokenUsage.unavailable()
                : ModelTokenUsage.of(usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens());
        return new GeneratedAnswer(content == null ? "" : content, tokenUsage);
    }

    public record GeneratedAnswer(String content, ModelTokenUsage tokenUsage) {}

    /**
     * RAG 问答（流式）
     *
     * @param question  用户问题
     * @param topK      返回条数
     */
    public RagStreamResult chatStream(String question, int topK) {
        return chatStream(UUID.randomUUID().toString(), question, topK);
    }

    public RagStreamResult chatStream(
            String conversationId, String question, int topK) {
        return chatStream(conversationId, question, topK, ignored -> {});
    }

    public RagStreamResult chatStream(
            String conversationId, String question, int topK,
            Consumer<AgentTraceEvent> traceConsumer) {
        log.info("[RAGStream] 收到问题: {}, conversationId={}", question, conversationId);
        long start = System.currentTimeMillis();
        PreparedTurn turn = prepareTurn(conversationId, question, topK, traceConsumer);
        List<Document> docs = turn.documents();
        if (turn.directAnswer() != null) {
            traceConsumer.accept(trace("GENERATION", "completed", "生成响应",
                    "当前路由无需调用生成模型，已直接形成响应。",
                    List.of(), Map.of("mode", "direct"), 0));
            conversationMemory.append(conversationId, question,
                    turn.decision().standaloneQuestion(), turn.directAnswer(), docs);
            return new RagStreamResult(conversationId, Flux.just(turn.directAnswer()),
                    toReferences(docs), turn.decision());
        }

        String context = formatContext(docs);
        log.info("[RAGStream] 检索到 {} 条相关法条", docs.size());

        String userPrompt = String.format(USER_PROMPT_TEMPLATE_BALANCED, context, turn.generationQuestion());
        log.info("[RAGStream] 发送给 AI 的用户提示词:\n{}", userPrompt);
        traceConsumer.accept(trace("GENERATION", "running", "生成法律答复",
                "正在结合已核验法律依据与一般性法律知识生成完整答复。",
                List.of(), Map.of("evidenceCount", docs.size()), 0));

        // 3. 流式调用大模型
        StringBuilder fullResponse = new StringBuilder();
        Flux<String> contentFlux = ChatClient.create(chatModel)
                .prompt()
                .system(SYSTEM_PROMPT_BALANCED)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    long cost = System.currentTimeMillis() - start;
                    log.info("[RAGStream] 流式响应完成, 耗时: {}ms, 输出长度: {} 字符", cost, fullResponse.length());
                    log.debug("[RAGStream] 完整输出: {}", fullResponse);
                    conversationMemory.append(conversationId, question,
                            turn.decision().standaloneQuestion(), fullResponse.toString(), docs);
                })
                .doOnError(e -> log.error("[RAGStream] 流式响应异常, question: {}", question, e));

        return new RagStreamResult(conversationId, contentFlux,
                toReferences(docs), turn.decision());
    }

    /**
     * 使用默认 topK，默认关闭联网搜索
     */
    public RagResult chat(String question) {
        return chat(question, DEFAULT_TOP_K);
    }

    public RagStreamResult chatStream(String question) {
        return chatStream(question, DEFAULT_TOP_K);
    }

    private List<Document> retrieveDocuments(String question, int topK, boolean queryRewrite) {
        return lawSearchService.searchDocuments(
                question,
                Math.max(candidateTopK, topK),
                topK,
                Map.of(),
                RetrievalMode.HYBRID,
                rerankByDefault,
                queryRewrite
        );
    }

    private LawSearchService.DocumentSearchExecution retrieveDocumentsWithTrace(
            String question, int topK, boolean queryRewrite,
            LawSearchService.SearchProgressListener progressListener) {
        return lawSearchService.searchDocumentsWithTrace(
                question,
                Math.max(candidateTopK, topK),
                topK,
                Map.of(),
                RetrievalMode.HYBRID,
                rerankByDefault,
                queryRewrite,
                progressListener
        );
    }

    private PreparedTurn prepareTurn(String conversationId, String question, int topK) {
        return prepareTurn(conversationId, question, topK, ignored -> {});
    }

    private PreparedTurn prepareTurn(
            String conversationId, String question, int topK,
            Consumer<AgentTraceEvent> traceConsumer) {
        List<ConversationMemoryService.ConversationTurn> history =
                conversationMemory.history(conversationId);
        traceConsumer.accept(trace("ROUTING", "running", "分析会话意图",
                history.isEmpty() ? "正在判断问题是否可独立检索。"
                        : "正在结合近期会话判断问题与上下文的关系。",
                List.of(), Map.of("historyTurns", history.size()), 0));
        AgenticConversationRouter.Decision decision = conversationRouter.route(question, history);
        traceConsumer.accept(routeTrace(decision));
        List<Document> documents;
        String directAnswer = null;
        String agentFinalInstructions = "";
        LawSearchService.SearchExecution searchTrace = null;

        switch (decision.action()) {
            case INDEPENDENT_RETRIEVAL, CONTEXTUAL_RETRIEVAL -> {
                boolean rewrite = decision.action()
                        == AgenticConversationRouter.Action.INDEPENDENT_RETRIEVAL
                        && queryRewriteByDefault;
                String retrievalQuery = decision.action()
                        == AgenticConversationRouter.Action.CONTEXTUAL_RETRIEVAL
                        ? decision.standaloneQuestion() : question;
                IterativeLegalAgentService.AgentExecution agentExecution =
                        iterativeLegalAgentService.execute(retrievalQuery, rewrite,
                                iterativeProgressListener(traceConsumer));
                if (agentExecution.fastPath()) {
                    LawSearchService.DocumentSearchExecution execution =
                            retrieveDocumentsWithTrace(retrievalQuery, topK, rewrite,
                                    searchProgressListener(traceConsumer));
                    documents = execution.documents();
                    searchTrace = execution.trace();
                } else {
                    documents = agentExecution.documents();
                    agentFinalInstructions = agentExecution.finalInstructions();
                }
                emitEvidenceTrace(traceConsumer, documents);
            }
            case ANSWER_FROM_CONTEXT -> {
                documents = conversationMemory.latestEvidence(history);
                traceConsumer.accept(trace("EVIDENCE", "completed", "复用上轮依据",
                        "当前请求是对上一轮答复的解释，继续使用已核验的法律依据。",
                        evidenceDetails(documents), Map.of("evidenceCount", documents.size()), 0));
            }
            case CLARIFY -> {
                documents = List.of();
                directAnswer = decision.clarificationQuestion();
            }
            case OUT_OF_SCOPE -> {
                documents = List.of();
                directAnswer = "当前系统主要用于中国法律问题咨询，请描述您希望了解的法律问题。";
            }
            default -> throw new IllegalStateException("不支持的Agentic动作: " + decision.action());
        }

        String generationQuestion = buildGenerationQuestion(question, decision, history);
        if (!agentFinalInstructions.isBlank()) {
            generationQuestion += "\n\n" + agentFinalInstructions;
        }
        log.info("[AgenticRAG] conversationId={}, action={}, historyTurns={}, retrievalQuery='{}', "
                        + "queryRewrite={}, evidence={}",
                conversationId, decision.action(), history.size(),
                decision.action() == AgenticConversationRouter.Action.CONTEXTUAL_RETRIEVAL
                        ? decision.standaloneQuestion() : question,
                decision.action() == AgenticConversationRouter.Action.INDEPENDENT_RETRIEVAL
                        && queryRewriteByDefault,
                documents.size());
        return new PreparedTurn(decision, documents, generationQuestion, directAnswer, searchTrace);
    }

    private IterativeLegalAgentService.ProgressListener iterativeProgressListener(
            Consumer<AgentTraceEvent> traceConsumer) {
        return event -> traceConsumer.accept(trace(
                event.stage(), event.status(), event.title(), event.summary(),
                event.details(), event.metadata(), event.durationMs()));
    }

    private AgentTraceEvent routeTrace(AgenticConversationRouter.Decision decision) {
        List<String> details = new ArrayList<>();
        if (decision.action() == AgenticConversationRouter.Action.CONTEXTUAL_RETRIEVAL
                && decision.standaloneQuestion() != null
                && !decision.standaloneQuestion().isBlank()) {
            details.add("补全后的独立问题：" + decision.standaloneQuestion());
        }
        if (decision.reason() != null && !decision.reason().isBlank()) {
            details.add(decision.reason());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", decision.action().name());
        metadata.put("dependsOnHistory", decision.dependsOnHistory());
        metadata.put("topicChanged", decision.topicChanged());
        metadata.put("confidence", decision.confidence());
        metadata.put("fallback", decision.fallback());
        return trace("ROUTING", "completed", "Agent 问题路由",
                routeSummary(decision.action()), details, metadata, decision.durationMs());
    }

    private String routeSummary(AgenticConversationRouter.Action action) {
        return switch (action) {
            case INDEPENDENT_RETRIEVAL -> "识别为独立法律问题，进入混合检索。";
            case CONTEXTUAL_RETRIEVAL -> "识别为上下文追问，补全语义后检索。";
            case ANSWER_FROM_CONTEXT -> "识别为解释请求，复用上一轮法律依据。";
            case CLARIFY -> "关键信息不足，先向用户发起最小追问。";
            case OUT_OF_SCOPE -> "问题超出中国法律咨询范围。";
        };
    }

    private LawSearchService.SearchProgressListener searchProgressListener(
            Consumer<AgentTraceEvent> traceConsumer) {
        return new LawSearchService.SearchProgressListener() {
            @Override
            public void onQueryRewriteStarted(String query, boolean enabled) {
                traceConsumer.accept(trace("QUERY_REWRITE", "running", "规范化检索问题",
                        enabled ? "正在将口语问题转换为适合法律检索的独立问题。"
                                : "当前问题已完成上下文补全，准备直接检索。",
                        List.of(), Map.of("enabled", enabled), 0));
            }

            @Override
            public void onQueryRewriteCompleted(QueryRewriter.RewriteResult rewrite) {
                traceConsumer.accept(trace("QUERY_REWRITE", "completed", "规范化检索问题",
                        rewrite.changed() ? "已将口语问题改写为可独立检索的法律问题。"
                                : "当前问题语义完整，保留现有检索表达。",
                        rewrite.changed()
                                ? List.of("规范化问题：" + rewrite.rewrittenQuery()) : List.of(),
                        Map.of("changed", rewrite.changed(), "fallback", rewrite.fallback()),
                        rewrite.durationMs()));
            }

            @Override
            public void onKeywordAnalysisCompleted(
                    RetrievalHintParser.RetrievalHintPlan hints) {
                List<String> keywordDetails = new ArrayList<>();
                if (!hints.lawNames().isEmpty()) {
                    keywordDetails.add("法律名称：" + String.join("、", hints.lawNames()));
                }
                if (!hints.articleNumbers().isEmpty()) {
                    keywordDetails.add("条文编号：" + String.join("、", hints.articleNumbers()));
                }
                if (!hints.concepts().isEmpty()) {
                    keywordDetails.add("法律概念：" + String.join("、", hints.concepts()));
                }
                if (keywordDetails.isEmpty()) {
                    keywordDetails.add("未识别到需要额外扩展的规则关键词");
                }
                traceConsumer.accept(trace("KEYWORD_ANALYSIS", "completed", "BM25 关键词分析",
                        hints.hasHints() ? "已识别法律实体与概念，并增强关键词检索表达。"
                                : "未发现显式法律实体，BM25 使用当前问题检索。",
                        keywordDetails, Map.of("enhancedQuery", hints.enhancedBm25Query()), 0));
            }

            @Override
            public void onHybridRetrievalStarted(RetrievalMode mode, boolean rewritten) {
                traceConsumer.accept(trace("HYBRID_RETRIEVAL", "running", "混合召回与融合",
                        rewritten ? "正在执行原问题与规范化问题的四路混合召回。"
                                : "正在执行 Dense 与 BM25 双路混合召回。",
                        List.of(), Map.of("mode", mode.name()), 0));
            }

            @Override
            public void onHybridRetrievalCompleted(
                    LawSearchService.SearchChannelSummary summary) {
                List<String> details = List.of(
                        "Dense 语义候选：" + summary.denseCandidateCount() + " 条",
                        "BM25 关键词候选：" + summary.bm25CandidateCount() + " 条",
                        "融合后候选：" + summary.fusedCandidateCount() + " 条",
                        "Embedding API：" + summary.embeddingDurationMs() + "ms，Qdrant："
                                + summary.qdrantDurationMs() + "ms");
                traceConsumer.accept(trace("HYBRID_RETRIEVAL", "completed", "混合召回与融合",
                        summary.rewritten() ? "原问题与规范化问题完成四路召回并融合。"
                                : "Dense 与 BM25 双路召回完成并融合。",
                        details, Map.of("fusedCount", summary.fusedCandidateCount()),
                        summary.retrievalDurationMs()));
            }

            @Override
            public void onRerankStarted(int candidateCount) {
                traceConsumer.accept(trace("RERANK", "running", "Cross-Encoder 相关性重排",
                        "正在使用本地 ONNX 模型对融合候选进行语义精排。",
                        List.of("参与重排：" + candidateCount + " 条"),
                        Map.of("candidateCount", candidateCount), 0));
            }

            @Override
            public void onRerankCompleted(
                    int candidateCount, int finalCount, long durationMs) {
                traceConsumer.accept(trace("RERANK", "completed", "Cross-Encoder 相关性重排",
                        "已完成语义精排，保留与问题最相关的条文。",
                        List.of("参与重排：" + candidateCount + " 条",
                                "最终保留：" + finalCount + " 条"),
                        Map.of("finalCount", finalCount), durationMs));
            }
        };
    }

    private void emitEvidenceTrace(
            Consumer<AgentTraceEvent> traceConsumer,
            List<Document> documents) {
        traceConsumer.accept(trace("EVIDENCE", "completed", "组装法律依据",
                documents.isEmpty() ? "未找到足以直接支持结论的法律条文。"
                        : "已选择可直接支持回答的候选法律条文。",
                evidenceDetails(documents), Map.of("evidenceCount", documents.size()), 0));
    }

    private List<String> evidenceDetails(List<Document> documents) {
        return documents.stream()
                .map(document -> document.getMetadata().getOrDefault("source_name", "未知法律文件")
                        + " " + document.getMetadata().getOrDefault("article_no", ""))
                .distinct()
                .limit(5)
                .toList();
    }

    private AgentTraceEvent trace(
            String stage, String status, String title, String summary,
            List<String> details, Map<String, Object> metadata, long durationMs) {
        return new AgentTraceEvent(stage, status, title, summary,
                details, metadata, Math.max(0, durationMs));
    }

    private String buildGenerationQuestion(
            String currentQuestion,
            AgenticConversationRouter.Decision decision,
            List<ConversationMemoryService.ConversationTurn> history) {
        if (history == null || history.isEmpty()) return currentQuestion;
        StringBuilder prompt = new StringBuilder();
        prompt.append("以下会话历史仅用于理解当前问题，不得在答复中讨论会话处理过程：\n")
                .append(conversationMemory.formatHistory(history, answerHistoryTurns))
                .append("\n\n当前用户问题：").append(currentQuestion);
        if (decision.action() == AgenticConversationRouter.Action.CONTEXTUAL_RETRIEVAL) {
            prompt.append("\n上下文补全后的问题：").append(decision.standaloneQuestion());
        }
        prompt.append("\n请直接回答当前用户问题。");
        return prompt.toString();
    }

    private List<RetrievedDoc> toReferences(List<Document> docs) {
        return docs.stream()
                .map(doc -> new RetrievedDoc(
                        (String) doc.getMetadata().get("source_name"),
                        (String) doc.getMetadata().get("article_no"),
                        doc.getText(),
                        doc.getScore(),
                        String.valueOf(doc.getMetadata().getOrDefault("source_type", "law")),
                        String.valueOf(doc.getMetadata().getOrDefault("source_url", ""))))
                .toList();
    }

    /**
     * 将检索到的文档格式化为上下文文本
     */
    private String formatContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（未检索到相关法律条文）";
        }
        return docs.stream()
                .map(doc -> {
                    String sourceName = (String) doc.getMetadata().getOrDefault("source_name", "未知法律文件");
                    String articleNo = (String) doc.getMetadata().getOrDefault("article_no", "未知条号");
                    // part/chapter/section 仍保留在数据库 metadata 中用于检索、过滤和页面展示，
                    // 但不传给生成模型，避免模型在引用标题中擅自增加章节层级。
                    String location = "【" + sourceName + "】 " + articleNo;
                    String subQuestions = String.valueOf(
                            doc.getMetadata().getOrDefault("agent_sub_questions", "")).trim();
                    String evidenceScope = subQuestions.isBlank()
                            ? "" : "【该条文对应的待回答事项】" + subQuestions + "\n";
                    if ("official_web".equals(doc.getMetadata().get("source_type"))) {
                        String authority = String.valueOf(doc.getMetadata()
                                .getOrDefault("issuing_authority", "官方机关"));
                        String sourceUrl = String.valueOf(doc.getMetadata()
                                .getOrDefault("source_url", ""));
                        String validity = String.valueOf(doc.getMetadata()
                                .getOrDefault("validity_status", "未核验"));
                        return evidenceScope + "【官方网页证据】" + sourceName
                                + "\n发布机关：" + authority
                                + "\n效力标记：" + validity
                                + "\n原始链接：" + sourceUrl
                                + "\n官方正文匹配片段：\n" + doc.getText();
                    }
                    return evidenceScope + location + "\n" + doc.getText();
                })
                .collect(Collectors.joining("\n\n"));
    }

    // ---- 数据结构 ----

    public record RagResult(
            String conversationId,
            String question,
            String answer,
            List<RetrievedDoc> references,
            AgenticConversationRouter.Decision agentDecision) {}

    public record RagStreamResult(
            String conversationId,
            Flux<String> contentFlux,
            List<RetrievedDoc> references,
            AgenticConversationRouter.Decision agentDecision) {}

    public record RetrievedDoc(
            String sourceName,
            String articleNo,
            String text,
            Double score,
            String sourceType,
            String sourceUrl) {}

    public record AgentTraceEvent(
            String stage,
            String status,
            String title,
            String summary,
            List<String> details,
            Map<String, Object> metadata,
            long durationMs) {}

    private record PreparedTurn(
            AgenticConversationRouter.Decision decision,
            List<Document> documents,
            String generationQuestion,
            String directAnswer,
            LawSearchService.SearchExecution searchTrace) {}
}

