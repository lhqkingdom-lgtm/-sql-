package com.slowsql.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏引擎。
 * 在 SQL 发往 Python Agent 前，基于字段名识别敏感列并掩码其字符串字面量。
 */
public final class SqlDataMasker {

    private static final Logger log = LoggerFactory.getLogger(SqlDataMasker.class);

    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\d{3})\\d+(\\d{4})$");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^(\\d{3})\\d+(\\d{4})$");

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "phone", "mobile", "telephone", "tel",
            "id_card", "idcard", "id_no", "idno",
            "password", "pwd", "passwd", "secret",
            "email", "mail",
            "bank", "bank_card", "card_no", "cardno",
            "ssn", "social_security");

    private SqlDataMasker() {}

    /**
     * 对 SQL 中敏感字段的字符串字面量执行脱敏。
     */
    public static String mask(String sql, List<String> tableNames) {
        if (sql == null || sql.isBlank()) return sql;
        try {
            StringBuilder result = new StringBuilder(sql);
            boolean hasSensitive = false;

            for (String keyword : SENSITIVE_KEYWORDS) {
                Pattern p = Pattern.compile(
                        "(?i)([`\"]?" + Pattern.quote(keyword) + "[`\"]?\\s*=\\s*)'([^']*)'");
                java.util.regex.Matcher m = p.matcher(result.toString());
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String value = m.group(2);
                    String masked = maskValue(value);
                    m.appendReplacement(sb, "$1'" + masked + "'");
                    hasSensitive = true;
                }
                m.appendTail(sb);
                result = sb;
            }

            if (hasSensitive) {
                log.info("敏感数据脱敏完成");
            }
            return result.toString();
        } catch (Exception e) {
            log.warn("脱敏过程异常，返回原始 SQL: {}", e.getMessage());
            return sql;
        }
    }

    /**
     * 手机号保留前3后4，身份证保留前3后4，邮箱保留前2+域名，其他全掩码。
     */
    static String maskValue(String value) {
        if (value == null || value.isEmpty()) return value;

        if (value.length() == 11 && value.matches("\\d{11}")) {
            return PHONE_PATTERN.matcher(value).replaceFirst("$1****$2");
        }
        if ((value.length() == 15 || value.length() == 18) && value.matches("\\d{15}|\\d{17}[\\dXx]")) {
            return ID_CARD_PATTERN.matcher(value).replaceFirst("$1***********$2");
        }
        if (value.contains("@") && value.length() > 3) {
            int at = value.indexOf('@');
            if (at >= 2) {
                return value.substring(0, 2) + "***" + value.substring(at);
            }
        }
        return "***";
    }
}
