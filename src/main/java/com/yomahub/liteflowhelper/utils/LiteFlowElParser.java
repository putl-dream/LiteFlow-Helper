package com.yomahub.liteflowhelper.utils;

import com.intellij.openapi.util.TextRange;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LiteFlow EL 表达式解析工具
 */
public class LiteFlowElParser {

    /**
     * 用于查找字符串字面量、注释和占位符的正则表达式
     * 匹配优先级：字符串 > 占位符 > 注释
     */
    private static final Pattern MIXED_PATTERN = Pattern.compile(
            "(\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"|'([^'\\\\]*(\\\\.[^'\\\\]*)]*)')|" +  // 字符串
            "(\\{\\{[^}]*\\}\\})|" +  // 占位符 {{...}}
            "//[^\\n]*|" +  // 行注释
            "/\\*.*?\\*/",   // 块注释
            Pattern.DOTALL);

    /** 占位符分组索引 */
    private static final int PLACEHOLDER_GROUP_INDEX = 6;

    public enum TokenType {
        STRING,
        PLACEHOLDER,
        COMMENT
    }

    public static class ElToken {
        public final TokenType type;
        public final TextRange range;
        public final String text;

        public ElToken(TokenType type, TextRange range, String text) {
            this.type = type;
            this.range = range;
            this.text = text;
        }
    }

    public static class MaskedResult {
        public final String maskedText;
        public final List<ElToken> tokens;

        public MaskedResult(String maskedText, List<ElToken> tokens) {
            this.maskedText = maskedText;
            this.tokens = tokens;
        }
    }

    /**
     * 解析并屏蔽 EL 表达式中的注释和占位符
     *
     * @param expressionText 原始 EL 表达式
     * @return 包含屏蔽后文本和提取的 token 列表的结果
     */
    public static MaskedResult parse(String expressionText) {
        if (expressionText == null) {
            return new MaskedResult("", new ArrayList<>());
        }

        StringBuilder maskedExpressionBuilder = new StringBuilder(expressionText);
        List<ElToken> tokens = new ArrayList<>();
        Matcher matcher = MIXED_PATTERN.matcher(expressionText);

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            TextRange range = new TextRange(start, end);
            String matchedText = expressionText.substring(start, end);

            if (matcher.group(1) != null) {
                // 字符串：保留不屏蔽
                tokens.add(new ElToken(TokenType.STRING, range, matchedText));
                continue;
            }

            if (matcher.group(PLACEHOLDER_GROUP_INDEX) != null) {
                // 占位符 {{...}}
                tokens.add(new ElToken(TokenType.PLACEHOLDER, range, matchedText));
                maskPlaceholder(expressionText, maskedExpressionBuilder, start, end);
            } else {
                // 注释：替换为空格
                tokens.add(new ElToken(TokenType.COMMENT, range, matchedText));
                replaceRangeWithSpaces(expressionText, maskedExpressionBuilder, start, end);
            }
        }

        String maskedText = maskedExpressionBuilder.toString();
        // 清理屏蔽后可能留下的多余逗号（例如：a, ,b 或 a,,b）
//        maskedText = cleanupExtraCommas(maskedText);

        return new MaskedResult(maskedText, tokens);
    }

    /**
     * 清理屏蔽后留下的多余逗号
     */
    private static String cleanupExtraCommas(String text) {
        // 替换连续的逗号为单个逗号（包括逗号中间有空格的情况）
        String cleaned = text.replaceAll(",\\s*,", ",");
        // 替换逗号后只跟空格和右括号的情况
        cleaned = cleaned.replaceAll(",\\s*\\)", ")");
        // 替换逗号后只跟空格和分号的情况
        cleaned = cleaned.replaceAll(",\\s*;", ";");
        return cleaned;
    }

    /**
     * 检查屏蔽后的表达式是否为空或无效
     *
     * @param maskedText 屏蔽后的表达式
     * @return 如果表达式为空或只包含空白字符/标点符号，返回 true
     */
    public static boolean isMaskedExpressionEmpty(String maskedText) {
        if (maskedText == null) {
            return true;
        }
        String trimmed = maskedText.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        // 检查是否只包含空白字符和标点符号（允许单个分号，这可能是有效的空语句）
        String contentOnly = trimmed.replaceAll("[\\s,;]+", "");
        return contentOnly.isEmpty();
    }

    /**
     * 屏蔽占位符及其前面的逗号（如果有）
     */
    private static void maskPlaceholder(String expressionText, StringBuilder maskedBuilder, int start, int end) {
        // 检查占位符前是否有逗号
        int actualStart = findStartIncludingComma(expressionText, start);

        // 检查占位符后是否有 '='（{{var}}=value 格式）
        int checkEnd = skipWhitespace(expressionText, end);
        boolean isAssignment = checkEnd < expressionText.length() && expressionText.charAt(checkEnd) == '=';

        if (isAssignment) {
            // 屏蔽整个占位符赋值语句
            int statementEnd = findStatementEnd(expressionText, checkEnd);
            replaceRangeWithSpaces(expressionText, maskedBuilder, actualStart, statementEnd);
        } else {
            // 只屏蔽占位符（及前面的逗号）
            replaceRangeWithSpaces(expressionText, maskedBuilder, actualStart, end);
        }
    }

    /**
     * 查找起始位置，包括前面的逗号
     */
    private static int findStartIncludingComma(String text, int start) {
        int checkBefore = start - 1;
        while (checkBefore >= 0 && Character.isWhitespace(text.charAt(checkBefore))) {
            checkBefore--;
        }
        return (checkBefore >= 0 && text.charAt(checkBefore) == ',') ? checkBefore : start;
    }

    /**
     * 跳过空白字符
     */
    private static int skipWhitespace(String text, int start) {
        int pos = start;
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    /**
     * 查找语句结束位置（分号或表达式结尾）
     */
    private static int findStatementEnd(String text, int start) {
        int pos = start + 1;
        while (pos < text.length()) {
            if (text.charAt(pos) == ';') {
                return pos + 1; // 包含分号
            }
            pos++;
        }
        return text.length();
    }

    /**
     * 将指定范围内的字符替换为空格（保留换行符）
     */
    private static void replaceRangeWithSpaces(String text, StringBuilder builder, int start, int end) {
        for (int i = start; i < end && i < text.length(); i++) {
            char c = text.charAt(i);
            builder.setCharAt(i, c == '\n' ? '\n' : ' ');
        }
    }
}
