package org.example.lawsrag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IterativeLegalAgentServiceTest {

    @Test
    void parsesAndLimitsComplexQuestionPlan() throws Exception {
        IterativeLegalAgentService service = service(3, 2);
        String response = """
                模型前缀
                {
                  "complex": true,
                  "subQuestions": [
                    {"id":"q1","question":"补偿基数采用哪个期间？","purpose":"确定时间范围"},
                    {"id":"q2","question":"哪些收入计入月工资？","purpose":"确定口径"},
                    {"id":"q3","question":"发生争议时由谁举证？","purpose":"确定举证责任"},
                    {"id":"q4","question":"无关的第四题","purpose":"应被截断"}
                  ],
                  "missingFacts":["解除时间"],
                  "clarificationQuestion":"劳动合同何时解除？"
                }
                模型后缀
                """;

        var plan = service.parsePlan("经济补偿怎么算？", response, 12);

        assertTrue(plan.complex());
        assertEquals(3, plan.subQuestions().size());
        assertEquals("发生争议时由谁举证？", plan.subQuestions().get(2).question());
        assertEquals(1, plan.missingFacts().size());
        assertFalse(plan.fallback());
    }

    @Test
    void oneSubQuestionAlwaysUsesFastPathSemantics() throws Exception {
        IterativeLegalAgentService service = service(3, 2);
        String response = """
                {"complex":true,"subQuestions":[
                  {"id":"q1","question":"劳动合同法第四十七条是什么？","purpose":"定位条文"}
                ],"missingFacts":[],"clarificationQuestion":""}
                """;

        var plan = service.parsePlan("劳动合同法第四十七条是什么？", response, 8);

        assertFalse(plan.complex());
        assertEquals(1, plan.subQuestions().size());
    }

    @Test
    void parsesAuditAndLimitsSecondRoundQueries() throws Exception {
        IterativeLegalAgentService service = service(3, 2);
        String response = """
                {
                  "complete":false,
                  "hasContradiction":true,
                  "summary":"时间范围已有依据，但工资口径与举证责任仍不足。",
                  "gaps":[{"subQuestionId":"q2","description":"工资组成缺少依据"}],
                  "contradictions":["初稿把离职前十二个月错误写成自然年度"],
                  "followUpQueries":[
                    {"subQuestionId":"q2","query":"经济补偿月工资包括哪些收入","reason":"补工资口径"},
                    {"subQuestionId":"q3","query":"劳动争议工资数额举证责任","reason":"补举证规则"},
                    {"subQuestionId":"q3","query":"第三条不应执行","reason":"超过上限"}
                  ],
                  "missingUserFacts":["解除日期"],
                  "clarificationQuestion":"劳动合同何时解除？"
                }
                """;

        var audit = service.parseAudit(response, 15);

        assertFalse(audit.complete());
        assertTrue(audit.hasContradiction());
        assertEquals(2, audit.followUpQueries().size());
        assertEquals(1, audit.gaps().size());
        assertEquals("劳动合同何时解除？", audit.clarificationQuestion());
    }

    @Test
    void enablesOfficialSearchOnlyWhenStructuredQueryIsPresent() throws Exception {
        IterativeLegalAgentService service = service(3, 2);
        String response = """
                {
                  "complete":false,
                  "hasContradiction":false,
                  "summary":"缺少实施细则",
                  "gaps":[{"subQuestionId":"q1","description":"内部未覆盖工资口径实施规则"}],
                  "contradictions":[],
                  "followUpQueries":[],
                  "externalSearchNeeded":true,
                  "externalSearchReasonCode":"MISSING_IMPLEMENTATION_RULE",
                  "externalSearchQueries":[
                    {"subQuestionId":"q1","query":"经济补偿月工资计算口径实施条例","reason":"查实施细则"}
                  ],
                  "missingUserFacts":[],
                  "clarificationQuestion":""
                }
                """;

        var audit = service.parseAudit(response, 20);

        assertTrue(audit.externalSearchNeeded());
        assertEquals("MISSING_IMPLEMENTATION_RULE", audit.externalSearchReasonCode());
        assertEquals(1, audit.externalSearchQueries().size());
    }

    @Test
    void promotesRepeatedInternalQueriesToOfficialSearchAfterInternalRound() throws Exception {
        IterativeLegalAgentService service = service(3, 2);
        String response = """
                {
                  "complete":false,
                  "hasContradiction":false,
                  "summary":"实施细则仍缺失",
                  "gaps":[{"subQuestionId":"q2","description":"工资构成和凭证规则仍无依据"}],
                  "contradictions":[],
                  "followUpQueries":[
                    {"subQuestionId":"q2","query":"经济补偿月工资构成及工资凭证认定规则","reason":"查实施细则"}
                  ],
                  "externalSearchNeeded":false,
                  "externalSearchReasonCode":"NONE",
                  "externalSearchQueries":[],
                  "missingUserFacts":[],
                  "clarificationQuestion":""
                }
                """;

        var parsed = service.parseAudit(response, 20);
        var normalized = service.normalizeAuditForPhase(parsed,
                IterativeLegalAgentService.AuditPhase.AFTER_INTERNAL_SEARCH);

        assertTrue(normalized.externalSearchNeeded());
        assertEquals("MISSING_INTERNAL_LAW", normalized.externalSearchReasonCode());
        assertEquals(1, normalized.externalSearchQueries().size());
        assertTrue(normalized.followUpQueries().isEmpty());
    }

    @Test
    void suppressesFurtherSearchPlansAfterOfficialSearch() throws Exception {
        IterativeLegalAgentService service = service(3, 2);
        String response = """
                {
                  "complete":false,
                  "hasContradiction":false,
                  "summary":"仍存在缺口",
                  "gaps":[{"subQuestionId":"q1","description":"仍无法确认"}],
                  "followUpQueries":[{"subQuestionId":"q1","query":"重复补查","reason":"重复"}],
                  "externalSearchNeeded":true,
                  "externalSearchReasonCode":"OFFICIAL_STANDARD_REQUIRED",
                  "externalSearchQueries":[{"subQuestionId":"q1","query":"重复官网搜索","reason":"重复"}],
                  "missingUserFacts":[],
                  "clarificationQuestion":""
                }
                """;

        var parsed = service.parseAudit(response, 20);
        var normalized = service.normalizeAuditForPhase(parsed,
                IterativeLegalAgentService.AuditPhase.AFTER_OFFICIAL_SEARCH);

        assertFalse(normalized.externalSearchNeeded());
        assertTrue(normalized.externalSearchQueries().isEmpty());
        assertTrue(normalized.followUpQueries().isEmpty());
    }

    private IterativeLegalAgentService service(int maxSubQuestions, int maxFollowUps) {
        return new IterativeLegalAgentService(
                mock(ChatModel.class), new ObjectMapper(), mock(LawSearchService.class),
                mock(OfficialLegalSearchTool.class), true, maxSubQuestions, 10, 3,
                maxFollowUps, 9, 2, true);
    }
}
