package org.example.lawsrag.util;

import java.util.Map;

/**
 * 中文数字转阿拉伯数字工具类
 * 支持：一千零四十六 → 1046
 */
public class ChineseNumberConverter {

    private static final Map<Character, Integer> DIGIT_MAP = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0),
            Map.entry('一', 1), Map.entry('二', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9)
    );

    private static final Map<Character, Integer> UNIT_MAP = Map.of(
            '十', 10, '百', 100, '千', 1000, '万', 10000
    );

    /**
     * 从条号字符串中提取中文数字并转为阿拉伯数字
     * 例: "第一千零四十六条" → 1046
     */
    public static int convert(String chineseArticleNo) {
        // 去掉 "第" 和 "条/编/章/节"
        String num = chineseArticleNo
                .replaceAll("^第", "")
                .replaceAll("[条编章节]$", "")
                .trim();
        return chineseToInt(num);
    }

    /**
     * 纯中文数字字符串转 int
     * 例: "一千零四十六" → 1046
     */
    public static int chineseToInt(String cn) {
        if (cn == null || cn.isEmpty()) {
            return 0;
        }

        int result = 0;
        int current = 0;
        int wanPart = 0; // 万以上部分

        for (int i = 0; i < cn.length(); i++) {
            char c = cn.charAt(i);

            if (DIGIT_MAP.containsKey(c)) {
                current = DIGIT_MAP.get(c);
            } else if (UNIT_MAP.containsKey(c)) {
                int unit = UNIT_MAP.get(c);
                if (unit == 10000) {
                    // 万位：把之前累积的结果乘以万
                    wanPart = (result + (current == 0 ? 0 : current)) * unit;
                    result = 0;
                    current = 0;
                } else {
                    // 十/百/千位
                    if (current == 0 && unit == 10) {
                        // 处理 "十六" 这种省略写法
                        current = 1;
                    }
                    result += current * unit;
                    current = 0;
                }
            }
        }

        // 加上最后一个未乘单位的数字（个位数）
        result += current;
        return wanPart + result;
    }

    private ChineseNumberConverter() {
    }
}

