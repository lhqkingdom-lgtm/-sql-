package com.slowsql.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 控制台日志解析工具，把 Preparing/Parameters 日志还原为可执行 SQL。
 */
public final class MybatisLogParser {

    private static final Pattern PREPARING_PATTERN = Pattern.compile("(?im)Preparing:\\s*(.+)$");
    private static final Pattern PARAMETERS_PATTERN = Pattern.compile("(?im)Parameters:\\s*(.*)$");
    private static final Pattern PARAMETER_ITEM_PATTERN = Pattern.compile("\\s*(null|.+?\\([^()]*\\))\\s*(?:,|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPED_PARAMETER_PATTERN = Pattern.compile("(.+)\\(([^()]*)\\)$");

    private MybatisLogParser() {}

    public static String parse(String logContent) {
        if (logContent == null) return null;

        Matcher preparingMatcher = PREPARING_PATTERN.matcher(logContent);
        if (!preparingMatcher.find()) return logContent;

        String sqlTemplate = preparingMatcher.group(1).trim();
        Matcher parametersMatcher = PARAMETERS_PATTERN.matcher(logContent);
        if (!parametersMatcher.find()) return sqlTemplate;

        List<String> parameters = parseParameters(parametersMatcher.group(1).trim());
        if (parameters.isEmpty()) return sqlTemplate;

        StringBuilder sqlBuilder = new StringBuilder();
        int parameterIndex = 0;
        for (int i = 0; i < sqlTemplate.length(); i++) {
            char current = sqlTemplate.charAt(i);
            if (current == '?' && parameterIndex < parameters.size()) {
                sqlBuilder.append(parameters.get(parameterIndex++));
            } else {
                sqlBuilder.append(current);
            }
        }
        return sqlBuilder.toString();
    }

    private static List<String> parseParameters(String parameterContent) {
        List<String> result = new ArrayList<>();
        if (parameterContent == null || parameterContent.isBlank()) return result;

        for (String parameter : splitParameters(parameterContent)) {
            String trimmedParameter = parameter.trim();
            if (!trimmedParameter.isEmpty()) {
                result.add(formatParameter(trimmedParameter));
            }
        }
        return result;
    }

    private static List<String> splitParameters(String parameterContent) {
        List<String> parameters = new ArrayList<>();
        Matcher matcher = PARAMETER_ITEM_PATTERN.matcher(parameterContent);
        int end = 0;
        while (matcher.find()) {
            parameters.add(matcher.group(1));
            end = matcher.end();
        }
        String remainingContent = parameterContent.substring(end).trim();
        if (!remainingContent.isEmpty()) parameters.add(remainingContent);
        return parameters;
    }

    private static String formatParameter(String rawParameter) {
        if ("null".equalsIgnoreCase(rawParameter)) return "null";

        Matcher matcher = TYPED_PARAMETER_PATTERN.matcher(rawParameter);
        if (!matcher.matches()) return rawParameter;

        String value = matcher.group(1).trim();
        String type = matcher.group(2).trim();
        if ("null".equalsIgnoreCase(value)) return "null";

        if (shouldQuote(type)) {
            return "'" + value.replace("'", "''") + "'";
        }
        return value;
    }

    private static boolean shouldQuote(String type) {
        String normalizedType = type.toLowerCase(Locale.ROOT);
        return normalizedType.contains("string")
                || normalizedType.contains("char")
                || normalizedType.contains("timestamp")
                || normalizedType.contains("date")
                || normalizedType.contains("time")
                || normalizedType.contains("localdate")
                || normalizedType.contains("localdatetime");
    }
}
