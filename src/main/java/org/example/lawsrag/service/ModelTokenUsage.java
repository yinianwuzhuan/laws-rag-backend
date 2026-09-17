package org.example.lawsrag.service;

/**
 * 一次模型调用返回的真实 Token 用量。
 * available=false 表示上游没有返回 Usage，不能把它误当成 0 Token。
 */
public record ModelTokenUsage(long inputTokens,
                              long outputTokens,
                              long totalTokens,
                              boolean available) {

    public static ModelTokenUsage unavailable() {
        return new ModelTokenUsage(0, 0, 0, false);
    }

    public static ModelTokenUsage of(Number input, Number output, Number total) {
        if (input == null && output == null && total == null) {
            return unavailable();
        }
        long inputTokens = nonNegative(input);
        long outputTokens = nonNegative(output);
        long totalTokens = total == null
                ? inputTokens + outputTokens
                : nonNegative(total);
        return new ModelTokenUsage(inputTokens, outputTokens, totalTokens, true);
    }

    private static long nonNegative(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }
}
