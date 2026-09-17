package org.example.lawsrag.enums;

import java.util.Arrays;

/** 法律文件的法定分类；与描述内容性质的 SourceType 相互独立。 */
public enum LegalDocumentType {
    LAW("law", "法律", SourceType.LAW),
    ADMINISTRATIVE_REGULATION("administrative-regulations", "行政法规", SourceType.LAW),
    SUPERVISION_REGULATION("supervision-regulations", "监察法规", SourceType.LAW),
    LEGAL_INTERPRETATION("legal-interpretations", "法律解释", SourceType.LAW),
    LEGAL_DECISION("legal-decisions", "有关法律问题和重大问题的决定", SourceType.LAW),
    JUDICIAL_INTERPRETATION("judicial-interpretations", "司法解释", SourceType.JUDICIAL_INTERPRETATION),
    SUPREME_COURT_INTERPRETATION("supreme-court-interpretations", "最高人民法院司法解释", SourceType.JUDICIAL_INTERPRETATION),
    SUPREME_PROCURATORATE_INTERPRETATION("supreme-procuratorate-interpretations", "最高人民检察院司法解释", SourceType.JUDICIAL_INTERPRETATION),
    JOINT_JUDICIAL_INTERPRETATION("joint-judicial-interpretations", "联合发布司法解释", SourceType.JUDICIAL_INTERPRETATION);

    private final String code;
    private final String label;
    private final SourceType sourceType;

    LegalDocumentType(String code, String label, SourceType sourceType) {
        this.code = code;
        this.label = label;
        this.sourceType = sourceType;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public SourceType getSourceType() { return sourceType; }

    public static LegalDocumentType fromCode(String code, SourceType fallback) {
        if (code != null && !code.isBlank()) {
            return Arrays.stream(values())
                    .filter(type -> type.code.equalsIgnoreCase(code.trim()))
                    .findFirst().orElseGet(() -> defaultFor(fallback));
        }
        return defaultFor(fallback);
    }

    private static LegalDocumentType defaultFor(SourceType sourceType) {
        return sourceType == SourceType.JUDICIAL_INTERPRETATION
                ? JUDICIAL_INTERPRETATION : LAW;
    }
}
