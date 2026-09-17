package org.example.lawsrag.service;

import org.example.lawsrag.enums.LegalDocumentType;
import org.example.lawsrag.enums.SourceType;
import org.example.lawsrag.util.ChineseNumberConverter;
import org.example.lawsrag.util.DocumentIdentity;
import org.springframework.ai.document.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 法律 Markdown 解析器：兼容传统法条文件和国家法律法规数据库导出的官方文件。 */
public class LawMarkdownParser {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)");
    private static final Pattern ARTICLE = Pattern.compile(
            "^(?:\\*\\*)?(第[一二三四五六七八九十百千万零〇\\d]+条(?:之[一二三四五六七八九十百千万零〇\\d]+)?)(?:\\*\\*)?[\\s　]*(.*)");
    private static final Pattern ARTICLE_PARTS = Pattern.compile(
            "^第([一二三四五六七八九十百千万零〇\\d]+)条(?:之([一二三四五六七八九十百千万零〇\\d]+))?$");
    private static final Pattern PART = Pattern.compile("第[一二三四五六七八九十百千万零〇\\d]+编(?:[\\s　].*)?");
    private static final Pattern CHAPTER = Pattern.compile("第[一二三四五六七八九十百千万零〇\\d]+章(?:[\\s　].*)?");
    private static final Pattern SECTION = Pattern.compile("第[一二三四五六七八九十百千万零〇\\d]+节(?:[\\s　].*)?");
    private static final Pattern ATTACHMENT = Pattern.compile("^(?:附件|附录|附表|附[：:]).*");
    private static final int PARAGRAPH_TARGET_CHARS = 1000;
    private static final int PARAGRAPH_OVERLAP_CHARS = 120;

    private final SourceType sourceType;
    private final String sourceName;

    public LawMarkdownParser(SourceType sourceType, String sourceName) {
        this.sourceType = sourceType;
        this.sourceName = DocumentIdentity.normalizeSourceName(sourceName);
    }

    @Deprecated
    public LawMarkdownParser(SourceType sourceType, String sourceName, String ignoredSubdomain) {
        this(sourceType, sourceName);
    }

    public List<Document> parse(Path filePath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return doParse(reader);
        }
    }

    public List<Document> parse(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return doParse(reader);
        }
    }

    /** 供批量导入器在解析前自动选择 law / judicial_interpretation。 */
    public static SourceType detectSourceType(Path file, SourceType fallback) throws IOException {
        FrontMatter metadata = splitFrontMatter(Files.readAllLines(file, StandardCharsets.UTF_8)).frontMatter();
        return LegalDocumentType.fromCode(metadata.value("documentType"), fallback).getSourceType();
    }

    public static boolean isOfficialDocument(Path file) throws IOException {
        return splitFrontMatter(Files.readAllLines(file, StandardCharsets.UTF_8))
                .frontMatter().isOfficialDocument();
    }

    private List<Document> doParse(BufferedReader reader) {
        SourceFile source = splitFrontMatter(reader.lines().toList());
        List<Line> lines = parseLines(source.bodyLines());
        boolean hasArticles = lines.stream().anyMatch(line -> line.type() == LineType.ARTICLE);
        return hasArticles ? parseArticles(lines, source.frontMatter())
                : parseParagraphDocument(source.bodyLines(), source.frontMatter());
    }

    private List<Line> parseLines(List<String> rawLines) {
        List<Line> lines = new ArrayList<>();
        for (String raw : rawLines) {
            String trimmed = stripBom(raw).trim();
            if (trimmed.isEmpty()) continue;
            Matcher article = ARTICLE.matcher(trimmed);
            if (article.matches()) {
                lines.add(new Line(LineType.ARTICLE, 0, article.group(2).trim(), article.group(1)));
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                String content = heading.group(2).replaceAll("[　\\s]+", " ").trim();
                Matcher headingArticle = ARTICLE.matcher(content);
                lines.add(headingArticle.matches()
                        ? new Line(LineType.ARTICLE, 0, headingArticle.group(2).trim(), headingArticle.group(1))
                        : new Line(LineType.HEADING, heading.group(1).length(), content, ""));
                continue;
            }
            lines.add(new Line(LineType.TEXT, 0, trimmed, ""));
        }
        return lines;
    }

    private List<Document> parseArticles(List<Line> lines, FrontMatter metadata) {
        List<Document> result = new ArrayList<>();
        Map<Integer, String> inferredRoles = inferHeadingRoles(lines, metadata);
        String part = "", chapter = "", section = "";
        String articleNo = null;
        int articleVariantIndex = 0;
        Map<String, Integer> articleOccurrences = new HashMap<>();
        StringBuilder articleText = new StringBuilder();
        StringBuilder preamble = new StringBuilder();
        StringBuilder attachments = new StringBuilder();
        boolean seenArticle = false;
        boolean collectingAttachment = false;

        for (Line line : lines) {
            if (line.type() == LineType.HEADING) {
                if (line.level() == 1 && metadata.isOfficialDocument()) continue;
                if (articleNo != null) {
                    result.add(articleDocument(articleNo, articleVariantIndex, articleText.toString().trim(), part, chapter, section, metadata));
                    articleNo = null;
                    articleText.setLength(0);
                }
                if (seenArticle && ATTACHMENT.matcher(line.content()).matches()) {
                    collectingAttachment = true;
                    append(attachments, line.content());
                    continue;
                }
                String role = PART.matcher(line.content()).matches() ? "part"
                        : CHAPTER.matcher(line.content()).matches() ? "chapter"
                        : SECTION.matcher(line.content()).matches() ? "section"
                        : inferredRoles.get(line.level());
                if ("part".equals(role)) {
                    part = line.content(); chapter = ""; section = "";
                } else if ("chapter".equals(role)) {
                    chapter = line.content(); section = "";
                } else if ("section".equals(role)) {
                    section = line.content();
                } else if (!seenArticle && line.level() > 1) {
                    append(preamble, line.content());
                }
                collectingAttachment = false;
                continue;
            }
            if (line.type() == LineType.ARTICLE) {
                if (articleNo != null) {
                    result.add(articleDocument(articleNo, articleVariantIndex, articleText.toString().trim(), part, chapter, section, metadata));
                }
                articleNo = line.articleNo();
                articleVariantIndex = articleOccurrences.merge(articleNo, 1, Integer::sum) - 1;
                articleText.setLength(0);
                append(articleText, line.content());
                seenArticle = true;
                collectingAttachment = false;
            } else if (articleNo != null) {
                append(articleText, line.content());
            } else if (!seenArticle) {
                append(preamble, line.content());
            } else if (collectingAttachment) {
                append(attachments, line.content());
            }
        }
        if (articleNo != null) {
            result.add(articleDocument(articleNo, articleVariantIndex, articleText.toString().trim(), part, chapter, section, metadata));
        }
        if (!preamble.isEmpty()) {
            result.addFirst(supplementDocument("preamble", 0, preamble.toString().trim(), metadata));
        }
        if (!attachments.isEmpty()) {
            List<String> chunks = splitByLength(List.of(attachments.toString()));
            for (int i = 0; i < chunks.size(); i++) {
                result.add(supplementDocument("attachment", i, chunks.get(i), metadata));
            }
        }
        return result;
    }

    private List<Document> parseParagraphDocument(List<String> bodyLines, FrontMatter metadata) {
        List<String> contentLines = new ArrayList<>();
        for (String raw : bodyLines) {
            String value = stripBom(raw).trim();
            if (value.isEmpty()) continue;
            Matcher heading = HEADING.matcher(value);
            if (heading.matches() && heading.group(1).length() == 1) continue;
            contentLines.add(value);
        }
        List<String> chunks = splitByLength(contentLines);
        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            result.add(supplementDocument("paragraph", i, chunks.get(i), metadata));
        }
        return result;
    }

    private List<String> splitByLength(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        String content = String.join("\n", lines).trim();
        if (content.isEmpty()) return chunks;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + PARAGRAPH_TARGET_CHARS);
            chunks.add(content.substring(start, end).trim());
            if (end == content.length()) break;
            start = Math.max(start + 1, end - PARAGRAPH_OVERLAP_CHARS);
        }
        return chunks;
    }

    /** 保留旧解析器的“标题层级推断”能力，官方文件的一级标题不参与层级推断。 */
    private Map<Integer, String> inferHeadingRoles(List<Line> lines, FrontMatter metadata) {
        Map<Integer, String> roles = new HashMap<>();
        Set<String> used = new java.util.HashSet<>();
        TreeSet<Integer> levels = new TreeSet<>();
        for (Line line : lines) {
            if (line.type() != LineType.HEADING || (metadata.isOfficialDocument() && line.level() == 1)) continue;
            levels.add(line.level());
            String role = PART.matcher(line.content()).matches() ? "part"
                    : CHAPTER.matcher(line.content()).matches() ? "chapter"
                    : SECTION.matcher(line.content()).matches() ? "section" : null;
            if (role != null) {
                roles.putIfAbsent(line.level(), role);
                used.add(role);
            }
        }
        List<String> remaining = new ArrayList<>();
        if (!used.contains("section")) remaining.add("section");
        if (!used.contains("chapter")) remaining.add("chapter");
        if (!used.contains("part")) remaining.add("part");
        int index = 0;
        for (int level : levels.descendingSet()) {
            if (!roles.containsKey(level) && index < remaining.size()) roles.put(level, remaining.get(index++));
        }
        return roles;
    }

    private Document articleDocument(String articleNo, int variantIndex, String text, String part, String chapter,
                                     String section, FrontMatter frontMatter) {
        String arabic = toArabicArticleNo(articleNo);
        String identity = variantIndex == 0 ? arabic : arabic + "-variant-" + variantIndex;
        String businessId = DocumentIdentity.businessId(sourceType.getCode(), sourceName, identity, 0);
        Map<String, Object> metadata = baseMetadata(businessId, text, frontMatter,
                variantIndex == 0 ? "article" : "article_variant", 0);
        metadata.put("part", part);
        metadata.put("chapter", chapter);
        metadata.put("section", section);
        metadata.put("article_no", articleNo);
        metadata.put("article_label", articleNo);
        metadata.put("article_no_arabic", arabic);
        String[] numberParts = arabic.split("-", 2);
        metadata.put("article_number", numberParts[0]);
        metadata.put("article_sub_number", numberParts.length == 2 ? numberParts[1] : "");
        metadata.put("article_variant_index", variantIndex);
        return new Document(DocumentIdentity.pointId(businessId), text, metadata);
    }

    private Document supplementDocument(String chunkType, int chunkIndex, String text, FrontMatter frontMatter) {
        String identity = chunkType + "-" + chunkIndex + "-" + DocumentIdentity.contentHash(text).substring(0, 12);
        String businessId = DocumentIdentity.businessId(sourceType.getCode(), sourceName, identity, 0);
        Map<String, Object> metadata = baseMetadata(businessId, text, frontMatter, chunkType, chunkIndex);
        for (String key : List.of("part", "chapter", "section", "article_no", "article_label",
                "article_no_arabic", "article_number", "article_sub_number")) metadata.put(key, "");
        return new Document(DocumentIdentity.pointId(businessId), text, metadata);
    }

    private Map<String, Object> baseMetadata(String businessId, String text, FrontMatter frontMatter,
                                             String chunkType, int chunkIndex) {
        LegalDocumentType documentType = LegalDocumentType.fromCode(frontMatter.value("documentType"), sourceType);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("business_id", businessId);
        metadata.put("content_hash", DocumentIdentity.contentHash(text));
        metadata.put("source_type", sourceType.getCode());
        metadata.put("source_type_name", sourceType.getLabel());
        metadata.put("source_name", sourceName);
        metadata.put("book", sourceName);
        metadata.put("document_type", documentType.getCode());
        metadata.put("document_type_name", frontMatter.valueOr("documentTypeName", documentType.getLabel()));
        metadata.put("document_id", frontMatter.value("officialId"));
        metadata.put("official_id", frontMatter.value("officialId"));
        metadata.put("official_category", frontMatter.value("officialCategory"));
        metadata.put("issuer", frontMatter.value("issuer"));
        metadata.put("promulgated_on", frontMatter.value("promulgatedOn"));
        metadata.put("effective_from", frontMatter.value("effectiveFrom"));
        metadata.put("status", frontMatter.value("status"));
        metadata.put("source_url", frontMatter.value("sourceUrl"));
        metadata.put("source_scope", frontMatter.isOfficialDocument() ? "central" : "");
        metadata.put("source_database", frontMatter.isOfficialDocument() ? "国家法律法规数据库" : "");
        metadata.put("chunk_type", chunkType);
        metadata.put("chunk_index", chunkIndex);
        metadata.put("char_count", text.length());
        metadata.put("parser_version", "official-markdown-v2");
        return metadata;
    }

    private String toArabicArticleNo(String articleNo) {
        Matcher matcher = ARTICLE_PARTS.matcher(articleNo);
        if (!matcher.matches()) throw new IllegalArgumentException("无法识别条文号: " + articleNo);
        int base = numberToInt(matcher.group(1));
        String suffix = matcher.group(2);
        return suffix == null ? String.valueOf(base) : base + "-" + numberToInt(suffix);
    }

    private int numberToInt(String number) {
        return number.chars().allMatch(Character::isDigit)
                ? Integer.parseInt(number) : ChineseNumberConverter.chineseToInt(number);
    }

    private static SourceFile splitFrontMatter(List<String> input) {
        List<String> lines = new ArrayList<>(input);
        if (lines.isEmpty() || !"---".equals(stripBom(lines.getFirst()).trim())) {
            return new SourceFile(new FrontMatter(Map.of()), lines);
        }
        Map<String, String> values = new HashMap<>();
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if ("---".equals(line)) { end = i; break; }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(line.substring(0, colon).trim(), value);
        }
        return end < 0 ? new SourceFile(new FrontMatter(Map.of()), lines)
                : new SourceFile(new FrontMatter(Map.copyOf(values)), new ArrayList<>(lines.subList(end + 1, lines.size())));
    }

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) return;
        if (!builder.isEmpty()) builder.append('\n');
        builder.append(value.trim());
    }

    private enum LineType { HEADING, ARTICLE, TEXT }
    private record Line(LineType type, int level, String content, String articleNo) {}
    private record SourceFile(FrontMatter frontMatter, List<String> bodyLines) {}
    private record FrontMatter(Map<String, String> values) {
        String value(String key) { return values.getOrDefault(key, ""); }
        String valueOr(String key, String fallback) {
            String value = value(key); return value.isBlank() ? fallback : value;
        }
        boolean isOfficialDocument() { return !value("officialId").isBlank(); }
    }
}
