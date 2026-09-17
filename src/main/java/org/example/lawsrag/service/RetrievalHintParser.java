package org.example.lawsrag.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯规则检索提示解析器。所有结果只用于BM25查询增强和Reranker上下文，
 * 永远不会转换为Qdrant Filter。
 */
@Component
public class RetrievalHintParser {

    private static final Pattern ARTICLE_PATTERN = Pattern.compile(
            "第\\s*([零〇一二三四五六七八九十百千万两\\d]+)\\s*条"
                    + "(?:\\s*之\\s*([零〇一二三四五六七八九十百千万两\\d]+))?");

    private static final List<LawAliases> LAW_ALIASES = List.of(
            law("中华人民共和国民法典", "民法典"),
            law("中华人民共和国刑法", "刑法"),
            law("中华人民共和国刑事诉讼法", "刑事诉讼法", "刑诉法"),
            law("中华人民共和国民事诉讼法", "民事诉讼法", "民诉法"),
            law("中华人民共和国劳动法", "劳动法"),
            law("中华人民共和国劳动合同法", "劳动合同法"),
            law("中华人民共和国劳动争议调解仲裁法", "劳动争议调解仲裁法", "劳动仲裁法"),
            law("中华人民共和国消费者权益保护法", "消费者权益保护法", "消保法"),
            law("中华人民共和国个人信息保护法", "个人信息保护法", "个保法"),
            law("中华人民共和国未成年人保护法", "未成年人保护法", "未保法"),
            law("中华人民共和国道路交通安全法", "道路交通安全法", "道交法"),
            law("中华人民共和国反家庭暴力法", "反家庭暴力法", "反家暴法"),
            law("中华人民共和国反电信网络诈骗法", "反电信网络诈骗法", "反诈法"),
            law("中华人民共和国网络安全法", "网络安全法", "网安法"),
            law("中华人民共和国电子商务法", "电子商务法", "电商法"),
            law("中华人民共和国社会保险法", "社会保险法", "社保法"),
            law("中华人民共和国公司法", "公司法"),
            law("中华人民共和国企业破产法", "企业破产法", "破产法"),
            law("中华人民共和国企业所得税法", "企业所得税法"),
            law("中华人民共和国个人所得税法", "个人所得税法", "个税法"),
            law("中华人民共和国反垄断法", "反垄断法"),
            law("中华人民共和国保险法", "保险法"),
            law("中华人民共和国产品质量法", "产品质量法"),
            law("中华人民共和国著作权法", "著作权法"),
            law("中华人民共和国专利法", "专利法"),
            law("中华人民共和国商标法", "商标法"),
            law("中华人民共和国行政处罚法", "行政处罚法"),
            law("中华人民共和国行政诉讼法", "行政诉讼法", "行诉法"),
            law("中华人民共和国仲裁法", "仲裁法"),
            law("中华人民共和国人民调解法", "人民调解法"),
            law("中华人民共和国就业促进法", "就业促进法"),
            law("中华人民共和国工会法", "工会法"),
            law("中华人民共和国反有组织犯罪法", "反有组织犯罪法")
    );

    private static final List<ConceptAliases> CONCEPT_ALIASES = List.of(
            concept("拖欠劳动报酬", "欠薪", "拖欠工资", "不发工资", "没发工资", "克扣工资"),
            concept("未及时足额支付劳动报酬", "工资没发", "工资不发", "少发工资"),
            concept("劳动者解除劳动合同", "直接走", "不干了", "辞职", "离职", "解除劳动合同"),
            concept("提前三十日通知", "提前30天", "提前三十天", "提前三十日", "提前一个月"),
            concept("社会保险", "社保", "五险"),
            concept("延长工作时间工资报酬", "加班费", "加班工资"),
            concept("经济补偿", "补偿金", "经济补偿"),
            concept("劳动争议仲裁", "劳动仲裁", "仲裁老板", "仲裁公司"),
            concept("家庭暴力", "家暴"),
            concept("离婚", "离婚"),
            concept("子女抚养", "抚养权", "孩子归谁"),
            concept("遗产继承", "继承遗产", "遗产怎么分", "遗产继承"),
            concept("诉讼时效", "诉讼时效", "过了几年还能起诉", "还能不能起诉"),
            concept("消费者退货", "七天无理由", "退货", "退款"),
            concept("个人信息处理", "个人信息", "隐私泄露"),
            concept("著作权侵权", "抄袭", "盗版", "著作权侵权"),
            concept("专利侵权", "专利侵权"),
            concept("交通事故责任", "交通事故", "车祸"),
            concept("正当防卫", "正当防卫"),
            concept("未成年人保护", "未成年人", "小孩", "孩子被欺负")
    );

    public RetrievalHintPlan parse(String originalQuery, String bm25BaseQuery) {
        String original = safe(originalQuery);
        String base = safe(bm25BaseQuery).isBlank() ? original : safe(bm25BaseQuery);
        Set<String> laws = new LinkedHashSet<>();
        Set<String> articles = new LinkedHashSet<>();
        Set<String> articleTokens = new LinkedHashSet<>();
        Set<String> concepts = new LinkedHashSet<>();

        List<MatchedLaw> matchedLaws = LAW_ALIASES.stream()
                .map(law -> new MatchedLaw(law.sourceName(), law.longestMatch(original)))
                .filter(match -> !match.alias().isBlank())
                .toList();
        for (MatchedLaw candidate : matchedLaws) {
            boolean shadowedBySpecificAlias = matchedLaws.stream().anyMatch(other ->
                    !other.sourceName().equals(candidate.sourceName())
                            && other.alias().length() > candidate.alias().length()
                            && other.alias().contains(candidate.alias()));
            if (!shadowedBySpecificAlias) laws.add(candidate.sourceName());
        }

        Matcher articleMatcher = ARTICLE_PATTERN.matcher(original);
        while (articleMatcher.find()) {
            int main = number(articleMatcher.group(1));
            int sub = articleMatcher.group(2) == null ? 0 : number(articleMatcher.group(2));
            if (main <= 0) continue;
            articles.add(sub > 0 ? main + "-" + sub : String.valueOf(main));
            articleTokens.add(sub > 0
                    ? "lawarticle" + main + "sub" + sub
                    : "lawarticle" + main);
        }

        for (ConceptAliases concept : CONCEPT_ALIASES) {
            if (concept.matches(original)) concepts.add(concept.canonical());
        }

        List<String> additions = new ArrayList<>();
        additions.addAll(laws);
        additions.addAll(articleTokens);
        additions.addAll(concepts);
        String enhanced = additions.isEmpty()
                ? base.trim()
                : (base.trim() + " " + String.join(" ", additions)).trim();
        return new RetrievalHintPlan(
                List.copyOf(laws), List.copyOf(articles), List.copyOf(articleTokens),
                List.copyOf(concepts), enhanced);
    }

    private static LawAliases law(String sourceName, String... aliases) {
        List<String> values = new ArrayList<>();
        values.add(sourceName);
        values.addAll(List.of(aliases));
        return new LawAliases(sourceName, List.copyOf(values));
    }

    private static ConceptAliases concept(String canonical, String... aliases) {
        return new ConceptAliases(canonical, List.of(aliases));
    }

    private int number(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        return Bm25TextBuilder.chineseNumber(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record RetrievalHintPlan(
            List<String> lawNames,
            List<String> articleNumbers,
            List<String> articleTokens,
            List<String> concepts,
            String enhancedBm25Query
    ) {
        public static RetrievalHintPlan empty(String query) {
            return new RetrievalHintPlan(List.of(), List.of(), List.of(), List.of(), safe(query));
        }

        public boolean hasHints() {
            return !lawNames.isEmpty() || !articleNumbers.isEmpty() || !concepts.isEmpty();
        }

        public String rerankerContext() {
            if (!hasHints()) return "";
            List<String> lines = new ArrayList<>();
            if (!lawNames.isEmpty()) lines.add("明确提及的法律：" + String.join("、", lawNames));
            if (!articleNumbers.isEmpty()) lines.add("明确提及的条号：" + String.join("、", articleNumbers));
            if (!concepts.isEmpty()) lines.add("规则识别的法律概念：" + String.join("、", concepts));
            return String.join("\n", lines);
        }
    }

    private record LawAliases(String sourceName, List<String> aliases) {
        private String longestMatch(String query) {
            return aliases.stream().filter(query::contains)
                    .max((left, right) -> Integer.compare(left.length(), right.length()))
                    .orElse("");
        }
    }

    private record MatchedLaw(String sourceName, String alias) {}

    private record ConceptAliases(String canonical, List<String> aliases) {
        private boolean matches(String query) {
            return aliases.stream().anyMatch(query::contains);
        }
    }
}
