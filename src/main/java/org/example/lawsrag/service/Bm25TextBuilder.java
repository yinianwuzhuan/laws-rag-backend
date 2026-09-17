package org.example.lawsrag.service;

import org.springframework.ai.document.Document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为中文法律文本补充BM25可稳定识别的结构化词元。
 * 中文数字条号会被分词器拆开，lawarticle316这类唯一词元可避免316条与216条混淆。
 */
final class Bm25TextBuilder {

    private static final Pattern CHINESE_ARTICLE = Pattern.compile(
            "第([零〇一二三四五六七八九十百千万两]+)条(?:之([零〇一二三四五六七八九十百千万两]+))?");
    private static final Pattern ARABIC_ARTICLE = Pattern.compile(
            "第?\\s*(\\d+)\\s*条(?:之([零〇一二三四五六七八九十百千万两\\d]+))?");

    private Bm25TextBuilder() {
    }

    static String documentText(Document document) {
        return documentText(
                stringValue(document.getMetadata().get("source_name")),
                stringValue(document.getMetadata().get("article_no")),
                stringValue(document.getMetadata().get("article_no_arabic")),
                document.getText());
    }

    static String documentText(String sourceName, String articleNo, String articleNoArabic, String content) {
        String token = tokenFromArabicMetadata(articleNoArabic);
        if (token.isEmpty()) token = extractArticleToken(articleNo);
        return String.join(" ", safe(sourceName), safe(articleNo), token, safe(content)).trim();
    }

    static String queryText(String query) {
        String token = extractArticleToken(query);
        return token.isEmpty() || query.contains(token) ? query : query + " " + token;
    }

    static String extractArticleToken(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher chinese = CHINESE_ARTICLE.matcher(text);
        if (chinese.find()) {
            int main = chineseNumber(chinese.group(1));
            int sub = chinese.group(2) == null ? 0 : chineseNumber(chinese.group(2));
            return articleToken(main, sub);
        }
        Matcher arabic = ARABIC_ARTICLE.matcher(text);
        if (arabic.find()) {
            int main = Integer.parseInt(arabic.group(1));
            String suffix = arabic.group(2);
            int sub = suffix == null ? 0
                    : suffix.chars().allMatch(Character::isDigit)
                    ? Integer.parseInt(suffix) : chineseNumber(suffix);
            return articleToken(main, sub);
        }
        return "";
    }

    private static String tokenFromArabicMetadata(String articleNoArabic) {
        if (articleNoArabic == null || articleNoArabic.isBlank()) return "";
        Matcher numbers = Pattern.compile("(\\d+)(?:\\D+(\\d+))?").matcher(articleNoArabic);
        if (!numbers.find()) return "";
        int main = Integer.parseInt(numbers.group(1));
        int sub = numbers.group(2) == null ? 0 : Integer.parseInt(numbers.group(2));
        return articleToken(main, sub);
    }

    private static String articleToken(int main, int sub) {
        if (main <= 0) return "";
        return sub > 0 ? "lawarticle" + main + "sub" + sub : "lawarticle" + main;
    }

    static int chineseNumber(String value) {
        int result = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = digit(value.charAt(i));
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = unit(value.charAt(i));
            if (unit == 10_000) {
                section = (section + number) * unit;
                result += section;
                section = 0;
            } else if (unit > 0) {
                if (number == 0) number = 1;
                section += number * unit;
            }
            number = 0;
        }
        return result + section + number;
    }

    private static int digit(char value) {
        return switch (value) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static int unit(char value) {
        return switch (value) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1_000;
            case '万' -> 10_000;
            default -> 0;
        };
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
