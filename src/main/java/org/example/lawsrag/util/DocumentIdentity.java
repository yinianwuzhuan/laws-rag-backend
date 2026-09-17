package org.example.lawsrag.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 法律向量文档的稳定身份与内容指纹规则。
 */
public final class DocumentIdentity {

    public static final String BUSINESS_ID_FIELD = "business_id";
    public static final String CONTENT_HASH_FIELD = "content_hash";

    private DocumentIdentity() {}

    /**
     * source_name 只做 Unicode、首尾和连续空白规范化，不丢弃法律名称中的标点与日期。
     */
    public static String normalizeSourceName(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName 不能为空");
        }
        return Normalizer.normalize(sourceName, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ");
    }

    public static String businessId(String sourceType,
                                    String sourceName,
                                    String articleNoArabic,
                                    int chunkIndex) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType 不能为空");
        }
        if (articleNoArabic == null || articleNoArabic.isBlank()) {
            throw new IllegalArgumentException("articleNoArabic 不能为空");
        }
        return sourceType.trim().toLowerCase(Locale.ROOT)
                + "_" + normalizeSourceName(sourceName)
                + "_" + articleNoArabic.trim()
                + "_" + chunkIndex;
    }

    /**
     * 使用业务身份生成Qdrant可接受的稳定UUID，使重复写入变为同Point upsert。
     */
    public static String pointId(String businessId) {
        return UUID.nameUUIDFromBytes(("laws-rag:" + businessId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 内容指纹使用SHA-256；统一换行并去除首尾空白，避免不同平台换行导致假差异。
     */
    public static String contentHash(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JDK不支持SHA-256", e);
        }
    }

    /**
     * 内容去重只在同一来源内生效。不同法律即使存在完全相同的条文，也必须保留各自的引用身份。
     */
    public static String contentDedupKey(String sourceName, String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash 不能为空");
        }
        return normalizeSourceName(sourceName) + "\u0000" + contentHash.trim();
    }
}
