package org.example.lawsrag.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DocumentIdentityTest {

    @Test
    void buildsBusinessIdFromSourceTypeAndNormalizedSourceName() {
        assertEquals("law_中华人民共和国民法典_1047_0",
                DocumentIdentity.businessId("LAW", " 中华人民共和国民法典 ", "1047", 0));
    }

    @Test
    void contentHashIgnoresPlatformLineEndingsAndOuterWhitespace() {
        assertEquals(DocumentIdentity.contentHash("第一行\r\n第二行"),
                DocumentIdentity.contentHash("  第一行\n第二行  "));
    }

    @Test
    void contentDedupKeyIsScopedByNormalizedSourceName() {
        String hash = DocumentIdentity.contentHash("相同条文");

        assertEquals(DocumentIdentity.contentDedupKey(" 法律甲 ", hash),
                DocumentIdentity.contentDedupKey("法律甲", hash));
        assertNotEquals(DocumentIdentity.contentDedupKey("法律甲", hash),
                DocumentIdentity.contentDedupKey("法律乙", hash));
    }

    @Test
    void pointIdIsStableForTheSameBusinessId() {
        String businessId = "law_中华人民共和国民法典_1047_0";
        assertEquals(DocumentIdentity.pointId(businessId), DocumentIdentity.pointId(businessId));
    }
}
