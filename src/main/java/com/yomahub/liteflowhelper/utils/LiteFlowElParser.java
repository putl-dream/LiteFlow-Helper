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

    // 用于查找 字符串字面量、注释 (//... 和 /*...*/) 以及占位符 ({{...}})
    private static final Pattern MIXED_PATTERN = Pattern.compile("(\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"|'([^'\\\\]*(\\\\.[^'\\\\]*)*)')|(\\{\\{.*?\\}\\})|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    public enum TokenType {
        STRING,
        PLACEHOLDER,
        COMMENT
    }

    public static class ElToken {
        public TokenType type;
        public TextRange range;
        public String text;

        public ElToken(TokenType type, TextRange range, String text) {
            this.type = type;
            this.range = range;
            this.text = text;
        }
    }

    public static class MaskedResult {
        public String maskedText;
        public List<ElToken> tokens;

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
                // 字符串
                tokens.add(new ElToken(TokenType.STRING, range, matchedText));
                // 字符串不屏蔽，保持原样 (或者根据需求屏蔽内容但保留引号?)
                // 目前逻辑是: 字符串字面量保留，不替换为空格，因为 QLExpress 需要解析字符串
                continue;
            }

            if (matcher.group(5) != null) {
                // 占位符 {{...}}
                tokens.add(new ElToken(TokenType.PLACEHOLDER, range, matchedText));
                // 替换 {{ 和 }} 为空格，保留中间内容
                for (int i = start; i < end; i++) {
                    char c = expressionText.charAt(i);
                    if (c == '\n') {
                        maskedExpressionBuilder.setCharAt(i, '\n');
                    } else if (c == '{' || c == '}') {
                        maskedExpressionBuilder.setCharAt(i, ' ');
                    }
                    // 中间内容保留
                }
            } else {
                // 注释
                tokens.add(new ElToken(TokenType.COMMENT, range, matchedText));
                // 替换为空格
                for (int i = start; i < end; i++) {
                    char c = expressionText.charAt(i);
                    if (c == '\n') {
                        maskedExpressionBuilder.setCharAt(i, '\n');
                    } else {
                        maskedExpressionBuilder.setCharAt(i, ' ');
                    }
                }
            }
        }

        return new MaskedResult(maskedExpressionBuilder.toString(), tokens);
    }
}
