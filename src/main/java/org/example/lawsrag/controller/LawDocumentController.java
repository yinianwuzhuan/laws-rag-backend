package org.example.lawsrag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.lawsrag.enums.SourceType;
import org.example.lawsrag.service.LawDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/law")
@RequiredArgsConstructor
public class LawDocumentController {

    private final LawDocumentService lawDocumentService;

    /**
     * 查询所有支持的 source_type 枚举值
     * GET /api/law/source-types
     */
    @GetMapping("/source-types")
    public ResponseEntity<Map<String, Object>> sourceTypes() {
        List<Map<String, String>> types = Arrays.stream(SourceType.values())
                .map(t -> Map.of("code", t.getCode(), "label", t.getLabel()))
                .toList();
        return ResponseEntity.ok(Map.of("success", true, "data", types));
    }

    /**
     * 上传法律 Markdown 文件并入库到向量数据库
     * POST /api/law/upload
     *
     * @param file       .md 文件
     * @param sourceType 来源类型 code，如 law、judicial_interpretation 等
     * @param sourceName 来源名称（用户填写，不可为空），如 "中华人民共和国民法典"
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceType") String sourceType,
            @RequestParam("sourceName") String sourceName) {

        String fileName = file.getOriginalFilename();
        if (!isSupportedMarkdown(fileName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "仅支持 .md / .markdown 格式文件"
            ));
        }
        if (sourceName == null || sourceName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "sourceName 不可为空"
            ));
        }

        SourceType type;
        try {
            type = SourceType.fromCode(sourceType);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        try {
            int count = lawDocumentService.ingestFromStream(
                    file.getInputStream(), fileName, type, sourceName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "入库成功",
                    "fileName", fileName,
                    "sourceType", type.getCode(),
                    "sourceName", sourceName,
                    "articleCount", count
            ));
        } catch (Exception e) {
            log.error("文件入库失败: {}", fileName, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "入库失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 批量上传法律Markdown文件。所有文件共用同一来源类型和来源名称；
     * 单个文件失败不会阻断其余文件处理。
     */
    @PostMapping("/upload/batch")
    public ResponseEntity<Map<String, Object>> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("sourceType") String sourceType,
            @RequestParam("sourceName") String sourceName) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "请至少选择一个文件"
            ));
        }
        if (sourceName == null || sourceName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "sourceName 不可为空"
            ));
        }

        SourceType type;
        try {
            type = SourceType.fromCode(sourceType);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int totalArticleCount = 0;
        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename();
            if (!isSupportedMarkdown(fileName)) {
                results.add(fileResult(fileName, false, 0, "仅支持 .md / .markdown 格式文件"));
                continue;
            }
            if (file.isEmpty()) {
                results.add(fileResult(fileName, false, 0, "文件内容为空"));
                continue;
            }

            try {
                int articleCount = lawDocumentService.ingestFromStream(
                        file.getInputStream(), fileName, type, sourceName);
                results.add(fileResult(fileName, true, articleCount,
                        articleCount == 0 ? "没有新增法条，内容可能已经入库" : "入库成功"));
                successCount++;
                totalArticleCount += articleCount;
            } catch (Exception e) {
                log.error("批量文件入库失败: {}", fileName, e);
                results.add(fileResult(fileName, false, 0, "入库失败: " + rootMessage(e)));
            }
        }

        int failureCount = files.size() - successCount;
        String message = failureCount == 0
                ? "全部文件入库成功"
                : successCount == 0
                ? "全部文件入库失败"
                : "批量入库完成，部分文件失败";
        return ResponseEntity.ok(Map.of(
                "success", failureCount == 0,
                "message", message,
                "sourceType", type.getCode(),
                "sourceName", sourceName,
                "totalFiles", files.size(),
                "successCount", successCount,
                "failureCount", failureCount,
                "articleCount", totalArticleCount,
                "results", results
        ));
    }

    /**
     * 上传并仅预览解析结果，不写入数据库
     * POST /api/law/preview
     *
     * @param file       .md 文件
     * @param sourceType 来源类型 code
     * @param sourceName 来源名称（用户填写，不可为空）
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceType") String sourceType,
            @RequestParam("sourceName") String sourceName) {

        String fileName = file.getOriginalFilename();
        if (!isSupportedMarkdown(fileName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "仅支持 .md / .markdown 格式文件"
            ));
        }
        if (sourceName == null || sourceName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "sourceName 不可为空"
            ));
        }

        SourceType type;
        try {
            type = SourceType.fromCode(sourceType);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        try {
            var documents = lawDocumentService.parseOnly(
                    file.getInputStream(), fileName, type, sourceName);
            var preview = documents.stream()
                    .limit(10)
                    .map(doc -> Map.of(
                            "metadata", doc.getMetadata(),
                            "text", doc.getText()
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileName", fileName,
                    "sourceType", type.getCode(),
                    "sourceName", sourceName,
                    "totalArticles", documents.size(),
                    "preview", preview
            ));
        } catch (Exception e) {
            log.error("文件预览失败: {}", fileName, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "解析失败: " + e.getMessage()
            ));
        }
    }

    private boolean isSupportedMarkdown(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".md") || lowerName.endsWith(".markdown");
    }

    private Map<String, Object> fileResult(String fileName, boolean success,
                                           int articleCount, String message) {
        return Map.of(
                "fileName", fileName == null ? "未知文件" : fileName,
                "success", success,
                "articleCount", articleCount,
                "message", message
        );
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}

