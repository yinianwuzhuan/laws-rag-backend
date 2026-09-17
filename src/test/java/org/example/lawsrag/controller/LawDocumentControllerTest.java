package org.example.lawsrag.controller;

import org.example.lawsrag.enums.SourceType;
import org.example.lawsrag.service.LawDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LawDocumentControllerTest {

    @Test
    void batchUploadContinuesWhenOneFileIsInvalid() throws Exception {
        LawDocumentService service = mock(LawDocumentService.class);
        when(service.ingestFromStream(any(InputStream.class), eq("01-general.md"),
                eq(SourceType.LAW), eq("中华人民共和国测试法"))).thenReturn(12);
        var controller = new LawDocumentController(service);
        var valid = new MockMultipartFile(
                "files", "01-general.md", "text/markdown", "**第一条** 内容".getBytes());
        var invalid = new MockMultipartFile(
                "files", "notes.txt", "text/plain", "not markdown".getBytes());

        var response = controller.uploadBatch(
                List.of(valid, invalid), "law", "中华人民共和国测试法");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertFalse((Boolean) body.get("success"));
        assertEquals(2, body.get("totalFiles"));
        assertEquals(1, body.get("successCount"));
        assertEquals(1, body.get("failureCount"));
        assertEquals(12, body.get("articleCount"));
        assertEquals(2, ((List<?>) body.get("results")).size());
        verify(service).ingestFromStream(any(InputStream.class), eq("01-general.md"),
                eq(SourceType.LAW), eq("中华人民共和国测试法"));
    }
}
