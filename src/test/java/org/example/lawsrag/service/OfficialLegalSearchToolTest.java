package org.example.lawsrag.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OfficialLegalSearchToolTest {

    @Test
    void exposesAnExplicitSpringInjectionConstructor() {
        long injectableConstructors = List.of(OfficialLegalSearchTool.class.getDeclaredConstructors()).stream()
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();

        assertEquals(1, injectableConstructors);
    }

    @Test
    void acceptsOnlyConfiguredOfficialDomains() {
        OfficialLegalSearchTool tool = tool();

        assertTrue(tool.isAllowedUrl("https://flk.npc.gov.cn/detail.html?id=1"));
        assertTrue(tool.isAllowedUrl("https://www.gov.cn/zhengce/content/1.htm"));
        assertFalse(tool.isAllowedUrl("https://gov.cn.example.com/fake"));
        assertFalse(tool.isAllowedUrl("file:///etc/passwd"));
        assertFalse(tool.isAllowedUrl("http://127.0.0.1:8080/admin"));
    }

    @Test
    void parsesRssAndDropsNonOfficialCandidates() throws Exception {
        OfficialLegalSearchTool tool = tool();
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item><title>国务院法规</title>
                    <link>https://www.gov.cn/zhengce/content/2026/test.htm</link>
                    <description>官方内容</description></item>
                  <item><title>非官方网站</title>
                    <link>https://example.com/legal</link><description>非官方</description></item>
                </channel></rss>
                """;

        var results = tool.parseRss(rss);

        assertEquals(1, results.size());
        assertEquals("国务院法规", results.getFirst().title());
    }

    @Test
    void selectsPassagesMatchingLegalQuestionInsteadOfReturningWholePage() {
        OfficialLegalSearchTool tool = tool();
        String page = "网站导航。劳动合同解除或者终止前十二个月的平均工资，"
                + "用于计算经济补偿的月工资标准。其他完全无关的新闻内容。";

        var passages = tool.selectPassages(page, "经济补偿月工资是否采用解除前十二个月平均工资");

        assertFalse(passages.isEmpty());
        assertTrue(passages.getFirst().contains("十二个月"));
    }

    private OfficialLegalSearchTool tool() {
        return new OfficialLegalSearchTool(true, "https://www.bing.com/search?format=rss&q=",
                List.of("flk.npc.gov.cn", "gov.cn", "court.gov.cn", "spp.gov.cn", "moj.gov.cn"),
                5, 5, Duration.ofSeconds(3), mock(HttpClient.class));
    }
}
