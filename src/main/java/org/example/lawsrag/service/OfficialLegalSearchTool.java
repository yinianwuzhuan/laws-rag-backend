package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读官方法律搜索工具。搜索引擎仅负责发现URL；只有成功抓取并通过域名校验的官方正文
 * 才会转换为最终生成可使用的证据。
 */
@Slf4j
@Service
public class OfficialLegalSearchTool {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?:公布日期|发布日期|施行日期|实施日期)?[：:]?\\s*((?:19|20)\\d{2}[年./-]\\d{1,2}[月./-]\\d{1,2}日?)");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；\\n])");
    private static final int MIN_PAGE_TEXT_LENGTH = 120;
    private static final int MAX_PAGE_TEXT_LENGTH = 40_000;

    private final boolean enabled;
    private final String searchEndpoint;
    private final List<String> allowedDomains;
    private final int maxResultsPerQuery;
    private final int maxPagesToFetch;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    @Autowired
    public OfficialLegalSearchTool(
            @Value("${laws.agentic-rag.official-search.enabled:true}") boolean enabled,
            @Value("${laws.agentic-rag.official-search.endpoint:https://www.bing.com/search?format=rss&q=}")
            String searchEndpoint,
            @Value("${laws.agentic-rag.official-search.allowed-domains:flk.npc.gov.cn,gov.cn,court.gov.cn,spp.gov.cn,moj.gov.cn}")
            String allowedDomains,
            @Value("${laws.agentic-rag.official-search.max-results-per-query:5}") int maxResultsPerQuery,
            @Value("${laws.agentic-rag.official-search.max-pages-to-fetch:5}") int maxPagesToFetch,
            @Value("${laws.agentic-rag.official-search.timeout-seconds:8}") int timeoutSeconds) {
        this(enabled, searchEndpoint, splitDomains(allowedDomains), maxResultsPerQuery,
                maxPagesToFetch, Duration.ofSeconds(Math.max(2, timeoutSeconds)),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .proxy(ProxySelector.getDefault())
                        .build());
    }

    OfficialLegalSearchTool(
            boolean enabled,
            String searchEndpoint,
            List<String> allowedDomains,
            int maxResultsPerQuery,
            int maxPagesToFetch,
            Duration requestTimeout,
            HttpClient httpClient) {
        this.enabled = enabled;
        this.searchEndpoint = searchEndpoint;
        this.allowedDomains = allowedDomains.stream()
                .map(value -> value.toLowerCase(Locale.ROOT).trim())
                .filter(value -> !value.isBlank()).distinct().toList();
        this.maxResultsPerQuery = Math.max(1, maxResultsPerQuery);
        this.maxPagesToFetch = Math.max(1, maxPagesToFetch);
        this.requestTimeout = requestTimeout;
        this.httpClient = httpClient;
    }

    @Tool(name = "official_legal_search",
            description = "仅当内部法律依据不足、缺少实施细则、效力状态不确定或需要最新官方标准时，"
                    + "搜索中国官方法律网站并返回已经抓取校验的正文片段。不得用于补充用户事实，"
                    + "不得传入姓名、身份证号、单位名称、案号、地址、银行流水等个人或案件敏感信息。")
    public SearchResponse searchOfficialLegalSources(
            @ToolParam(description = "去除个人信息后的独立法律检索问题") String query) {
        long startedAt = System.nanoTime();
        if (!enabled) return SearchResponse.disabled(query);
        String safeQuery = sanitizeQuery(query);
        if (safeQuery.isBlank()) {
            return new SearchResponse(query, safeQuery, List.of(), List.of(),
                    "查询为空，未执行官方搜索", elapsedMillis(startedAt), false);
        }
        try {
            List<SearchCandidate> candidates = discover(safeQuery);
            List<OfficialEvidence> evidence = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (SearchCandidate candidate : candidates.stream()
                    .limit(maxPagesToFetch).toList()) {
                try {
                    OfficialEvidence item = fetchAndValidate(candidate, safeQuery);
                    if (item != null) evidence.add(item);
                } catch (Exception e) {
                    failures.add(candidate.url() + "：" + rootMessage(e));
                }
            }
            String summary = evidence.isEmpty()
                    ? "未找到可抓取并验证的官方正文"
                    : "找到 " + evidence.size() + " 条可验证官方证据";
            log.info("[OfficialLegalSearchTool] query='{}', candidates={}, evidence={}, failures={}, total={}ms",
                    safeQuery, candidates.size(), evidence.size(), failures.size(), elapsedMillis(startedAt));
            return new SearchResponse(query, safeQuery, List.copyOf(evidence),
                    List.copyOf(failures), summary, elapsedMillis(startedAt), true);
        } catch (Exception e) {
            log.warn("[OfficialLegalSearchTool] 官方搜索失败, query='{}', reason={}",
                    safeQuery, rootMessage(e));
            return new SearchResponse(query, safeQuery, List.of(),
                    List.of(rootMessage(e)), "官方搜索暂不可用", elapsedMillis(startedAt), true);
        }
    }

    public List<Document> toDocuments(SearchResponse response, String subQuestionLabel) {
        if (response == null || response.evidence().isEmpty()) return List.of();
        return response.evidence().stream().map(item -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_name", item.title());
            metadata.put("article_no", "");
            metadata.put("source_type", "official_web");
            metadata.put("source_url", item.url());
            metadata.put("issuing_authority", item.issuingAuthority());
            metadata.put("publication_date", item.publicationDate());
            metadata.put("validity_status", item.validityStatus());
            metadata.put("accessed_at", item.accessedAt());
            metadata.put("business_id", "official_web_" + Integer.toUnsignedString(
                    item.url().hashCode(), 16));
            metadata.put("agent_sub_questions", subQuestionLabel == null ? "" : subQuestionLabel);
            String text = String.join("\n", item.matchedPassages());
            return Document.builder().text(text).metadata(metadata).build();
        }).toList();
    }

    List<SearchCandidate> parseRss(String rss) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        org.w3c.dom.Document document = factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(rss)));
        var items = document.getElementsByTagName("item");
        List<SearchCandidate> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.getLength() && results.size() < maxResultsPerQuery; i++) {
            var item = items.item(i);
            String title = childText(item, "title");
            String link = childText(item, "link");
            String description = stripHtml(childText(item, "description"));
            if (!isAllowedUrl(link) || !seen.add(link)) continue;
            results.add(new SearchCandidate(title, link, description));
        }
        return List.copyOf(results);
    }

    boolean isAllowedUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || host.isBlank()) return false;
            return allowedDomains.stream().anyMatch(domain ->
                    host.equals(domain) || host.endsWith("." + domain));
        } catch (Exception ignored) {
            return false;
        }
    }

    List<String> selectPassages(String pageText, String query) {
        if (pageText == null || pageText.isBlank()) return List.of();
        Set<String> ngrams = queryNgrams(query);
        return SENTENCE_SPLIT.splitAsStream(pageText)
                .map(String::trim)
                .filter(sentence -> sentence.length() >= 20)
                .map(sentence -> new ScoredPassage(sentence,
                        ngrams.stream().filter(sentence::contains).count()))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingLong(ScoredPassage::score).reversed())
                .map(item -> abbreviate(item.text(), 700))
                .distinct().limit(4).toList();
    }

    private List<SearchCandidate> discover(String query) throws Exception {
        String scopedQuery = query + " (site:flk.npc.gov.cn OR site:gov.cn OR site:court.gov.cn"
                + " OR site:spp.gov.cn OR site:moj.gov.cn)";
        String url = searchEndpoint + URLEncoder.encode(scopedQuery, StandardCharsets.UTF_8);
        HttpResponse<String> response = send(url, "application/rss+xml, application/xml, text/xml");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("搜索服务HTTP " + response.statusCode());
        }
        return parseRss(response.body());
    }

    private OfficialEvidence fetchAndValidate(SearchCandidate candidate, String query) throws Exception {
        if (!isAllowedUrl(candidate.url())) return null;
        HttpResponse<String> response = send(candidate.url(), "text/html, text/plain, application/xhtml+xml");
        if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
        String finalUrl = response.uri().toString();
        if (!isAllowedUrl(finalUrl)) return null;
        String text = extractReadableText(response.body());
        if (text.length() < MIN_PAGE_TEXT_LENGTH) return null;
        if (text.length() > MAX_PAGE_TEXT_LENGTH) text = text.substring(0, MAX_PAGE_TEXT_LENGTH);
        List<String> passages = selectPassages(text, query);
        if (passages.isEmpty() && !candidate.description().isBlank()) {
            passages = List.of(abbreviate(candidate.description(), 700));
        }
        // 搜索摘要只能帮助定位。抓到正文但正文没有任何匹配片段时，不把该页面作为证据。
        if (passages.isEmpty()) return null;
        String host = URI.create(finalUrl).getHost().toLowerCase(Locale.ROOT);
        return new OfficialEvidence(
                candidate.title().isBlank() ? host : candidate.title(), finalUrl,
                authority(host), detectDate(text), detectValidity(text),
                Instant.now().toString(), passages);
    }

    private HttpResponse<String> send(String url, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", accept)
                .header("User-Agent", "laws-rag-official-search/1.0")
                .GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String extractReadableText(String html) throws Exception {
        StringBuilder text = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private boolean ignored;

            @Override
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) ignored = true;
                if (tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.BR
                        || tag == HTML.Tag.LI || tag == HTML.Tag.TR) text.append('\n');
            }

            @Override
            public void handleEndTag(HTML.Tag tag, int position) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) ignored = false;
            }

            @Override
            public void handleText(char[] data, int position) {
                if (!ignored) text.append(data).append(' ');
            }
        }, true);
        return text.toString().replace('\u00a0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private Set<String> queryNgrams(String query) {
        String normalized = query == null ? "" : query.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        Set<String> values = new LinkedHashSet<>();
        for (int size : List.of(4, 3, 2)) {
            for (int i = 0; i + size <= normalized.length(); i++) {
                values.add(normalized.substring(i, i + size));
            }
        }
        return values;
    }

    private String sanitizeQuery(String query) {
        if (query == null) return "";
        return query.replaceAll("(?i)https?://\\S+", " ")
                .replaceAll("\\b\\d{17}[0-9Xx]\\b", " ")
                .replaceAll("\\b1\\d{10}\\b", " ")
                .replaceAll("\\b\\d{12,19}\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String authority(String host) {
        if (host.endsWith("npc.gov.cn")) return "全国人大及其常委会";
        if (host.endsWith("court.gov.cn")) return "人民法院";
        if (host.endsWith("spp.gov.cn")) return "人民检察院";
        if (host.endsWith("moj.gov.cn")) return "司法行政机关";
        if (host.endsWith("gov.cn")) return "政府机关";
        return "官方机关";
    }

    private String detectDate(String text) {
        Matcher matcher = DATE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "未识别";
    }

    private String detectValidity(String text) {
        String head = text.substring(0, Math.min(text.length(), 8_000));
        if (head.contains("已废止") || head.contains("失效")) return "可能已废止或失效";
        if (head.contains("现行有效")) return "现行有效";
        if (head.contains("尚未生效")) return "尚未生效";
        if (head.contains("已修改")) return "已修改，需核对最新文本";
        return "页面未明确标注，需结合官方元数据核验";
    }

    private String childText(org.w3c.dom.Node node, String name) {
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeName().equalsIgnoreCase(name)) {
                return children.item(i).getTextContent().trim();
            }
        }
        return "";
    }

    private String stripHtml(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private long elapsedMillis(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static List<String> splitDomains(String value) {
        if (value == null) return List.of();
        return Pattern.compile("[,;\\s]+").splitAsStream(value)
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    public record SearchCandidate(String title, String url, String description) {}

    public record OfficialEvidence(
            String title,
            String url,
            String issuingAuthority,
            String publicationDate,
            String validityStatus,
            String accessedAt,
            List<String> matchedPassages) {}

    public record SearchResponse(
            String originalQuery,
            String executedQuery,
            List<OfficialEvidence> evidence,
            List<String> failures,
            String summary,
            long durationMs,
            boolean executed) {
        static SearchResponse disabled(String query) {
            return new SearchResponse(query, "", List.of(), List.of(),
                    "官方搜索工具未启用", 0, false);
        }
    }

    private record ScoredPassage(String text, long score) {}
}
