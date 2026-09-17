package org.example.lawsrag.enums;

/**
 * 文档来源类型枚举
 */
public enum SourceType {

    LAW("law", "法条"),
    JUDICIAL_INTERPRETATION("judicial_interpretation", "司法解释"),
    QA("qa", "法律问答"),
    CASE_SUMMARY("case_summary", "判例摘要"),
    BOOK("book", "法律书籍");

    private final String code;
    private final String label;

    SourceType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 根据 code 查找枚举值，找不到则抛异常
     */
    public static SourceType fromCode(String code) {
        for (SourceType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的 source_type: " + code);
    }
}

