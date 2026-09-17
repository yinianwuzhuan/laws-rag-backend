package org.example.lawsrag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.enums.SourceType;
import org.example.lawsrag.util.DocumentIdentity;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 将 just-laws 的现行法律 Markdown 目录导入 Qdrant。
 * 排除站点导航和 versions 历史版本，避免历史条文以相同 business_id 覆盖现行条文。
 */
@Slf4j
@Service
public class FullLawCorpusImportService {

    private static final Pattern LEGAL_TITLE = Pattern.compile(
            "^(中华人民共和国|全国人民代表大会|最高人民法院|最高人民检察院|国务院|中央军事委员会).+");
    private static final Pattern REPEALED_SUFFIX = Pattern.compile("（废止提示）$");

    private final LawDocumentService lawDocumentService;

    public FullLawCorpusImportService(LawDocumentService lawDocumentService) {
        this.lawDocumentService = lawDocumentService;
    }

    public ImportReport scan(Path docsRoot) throws IOException {
        Path root = validateRoot(docsRoot);
        List<Path> files = discoverMarkdownFiles(root);
        Map<Path, String> sourceNames = new HashMap<>();
        Map<Path, Boolean> officialFiles = new HashMap<>();
        Set<String> officialSourceNames = new HashSet<>();
        for (Path file : files) {
            String sourceName = resolveSourceName(root, file);
            sourceNames.put(file, sourceName);
            boolean official = LawMarkdownParser.isOfficialDocument(file);
            officialFiles.put(file, official);
            if (official) officialSourceNames.add(sourceName);
        }
        Map<String, Document> uniqueByBusinessId = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        int filesWithArticles = 0;
        int parsedArticles = 0;
        int duplicateArticles = 0;

        for (Path file : files) {
            String sourceName = sourceNames.get(file);
            // 同名时整部采用国家法律法规数据库版本，禁止与旧开源版本混编。
            if (officialSourceNames.contains(sourceName) && !officialFiles.get(file)) {
                continue;
            }
            SourceType sourceType = LawMarkdownParser.detectSourceType(file, SourceType.LAW);
            List<Document> documents = new LawMarkdownParser(sourceType, sourceName).parse(file);
            if (!documents.isEmpty()) filesWithArticles++;
            parsedArticles += documents.size();
            for (Document document : documents) {
                String businessId = String.valueOf(document.getMetadata().get("business_id"));
                Document existing = uniqueByBusinessId.putIfAbsent(businessId, document);
                if (existing == null) continue;
                String existingHash = String.valueOf(existing.getMetadata().get(DocumentIdentity.CONTENT_HASH_FIELD));
                String incomingHash = String.valueOf(document.getMetadata().get(DocumentIdentity.CONTENT_HASH_FIELD));
                if (existingHash.equals(incomingHash)) {
                    duplicateArticles++;
                } else {
                    conflicts.add(new Conflict(
                            businessId,
                            String.valueOf(existing.getMetadata().get("source_name")),
                            String.valueOf(existing.getMetadata().get("article_no")),
                            abbreviate(existing.getText()),
                            abbreviate(document.getText())));
                }
            }
        }

        return new ImportReport(root, files.size(), filesWithArticles, parsedArticles,
                uniqueByBusinessId.size(), duplicateArticles, List.copyOf(conflicts),
                List.copyOf(uniqueByBusinessId.values()), 0);
    }

    public ImportReport importAll(Path docsRoot) throws IOException {
        ImportReport scanned = scan(docsRoot);
        if (!scanned.conflicts().isEmpty()) {
            throw new IllegalStateException("语料存在 " + scanned.conflicts().size()
                    + " 个 business_id 正文冲突，已停止导入。首个冲突: " + scanned.conflicts().getFirst());
        }
        log.info("[FullCorpusImport] 扫描完成: files={}, filesWithArticles={}, parsed={}, unique={}, duplicates={}",
                scanned.filesScanned(), scanned.filesWithArticles(), scanned.parsedArticles(),
                scanned.uniqueArticles(), scanned.duplicateArticles());
        int inserted = lawDocumentService.ingestDocuments(scanned.documents());
        return scanned.withInsertedArticles(inserted);
    }

    private Path validateRoot(Path docsRoot) {
        Path root = docsRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("法律语料目录不存在: " + root);
        }
        return root;
    }

    private List<Path> discoverMarkdownFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .filter(path -> isCorpusFile(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private boolean isCorpusFile(Path root, Path file) {
        Path relative = root.relativize(file);
        if (relative.getNameCount() == 1) return false;
        for (Path segment : relative) {
            String value = segment.toString();
            if ("versions".equalsIgnoreCase(value)
                    || "category".equalsIgnoreCase(value)
                    || "MessageBoard".equalsIgnoreCase(value)
                    || "document-types".equalsIgnoreCase(value)
                    || ".vuepress".equalsIgnoreCase(value)) {
                return false;
            }
        }
        return true;
    }

    private String resolveSourceName(Path root, Path file) throws IOException {
        String ownHeading = firstLevelOneHeading(file);
        if ("README.md".equalsIgnoreCase(file.getFileName().toString())) {
            return normalizeHeading(ownHeading, file);
        }
        if (ownHeading != null && LEGAL_TITLE.matcher(ownHeading).matches()) {
            return normalizeHeading(ownHeading, file);
        }

        // 宪法、刑法等目录中的“修正案”是独立法律文书，条号从第一条重新开始，
        // 不能沿用母法 source_name，否则会以相同 business_id 覆盖母法正文。
        String fileStem = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileStem.contains("amendment") || "修正案".equals(ownHeading)) {
            String parentTitle = findParentLegalTitle(root, file.getParent());
            return parentTitle + "修正案";
        }

        return findParentLegalTitle(root, file.getParent());
    }

    private String findParentLegalTitle(Path root, Path startDirectory) throws IOException {
        Path directory = startDirectory;
        while (directory != null && directory.startsWith(root)) {
            Path readme = directory.resolve("README.md");
            if (Files.isRegularFile(readme)) {
                String heading = firstLevelOneHeading(readme);
                if (heading != null && LEGAL_TITLE.matcher(heading).matches()) {
                    return normalizeHeading(heading, readme);
                }
            }
            if (directory.equals(root)) break;
            directory = directory.getParent();
        }
        throw new IllegalStateException("无法确定法律名称: " + startDirectory);
    }

    private String firstLevelOneHeading(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith("# "))
                    .map(line -> line.substring(2).trim())
                    .findFirst().orElse(null);
        }
    }

    private String normalizeHeading(String heading, Path file) {
        if (heading == null || heading.isBlank()) {
            throw new IllegalStateException("Markdown缺少一级标题: " + file);
        }
        return REPEALED_SUFFIX.matcher(heading.trim()).replaceFirst("");
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    public record Conflict(String businessId, String sourceName, String articleNo,
                           String existingText, String incomingText) {}

    public record ImportReport(
            Path docsRoot,
            int filesScanned,
            int filesWithArticles,
            int parsedArticles,
            int uniqueArticles,
            int duplicateArticles,
            List<Conflict> conflicts,
            List<Document> documents,
            int insertedArticles
    ) {
        ImportReport withInsertedArticles(int inserted) {
            return new ImportReport(docsRoot, filesScanned, filesWithArticles, parsedArticles,
                    uniqueArticles, duplicateArticles, conflicts, documents, inserted);
        }
    }
}
