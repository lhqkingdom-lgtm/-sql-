package com.slowsql.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全组件测试：MybatisLogParser + SqlAstValidator + SqlDataMasker。
 * 全部纯静态方法，无需 Mock，无外部依赖。
 */
class SecurityComponentsTest {

    // ==================== MybatisLogParser ====================

    @Test
    void parse_shouldReplacePlaceholders() {
        String log = "Preparing: SELECT * FROM orders WHERE id = ? AND status = ?\n"
                   + "Parameters: 1(Long), done(String)";
        String result = MybatisLogParser.parse(log);
        assertEquals("SELECT * FROM orders WHERE id = 1 AND status = 'done'", result);
    }

    @Test
    void parse_shouldReturnOriginalWhenNoMybatisLog() {
        String sql = "SELECT * FROM orders WHERE id = 1";
        assertEquals(sql, MybatisLogParser.parse(sql));
    }

    @Test
    void parse_shouldReturnNullForNullInput() {
        assertNull(MybatisLogParser.parse(null));
    }

    @Test
    void parse_shouldReturnOriginalForEmptyString() {
        assertEquals("", MybatisLogParser.parse(""));
    }

    @Test
    void parse_shouldReturnTemplateWhenNoParameters() {
        String log = "Preparing: SELECT * FROM orders WHERE id = ?";
        String result = MybatisLogParser.parse(log);
        assertEquals("SELECT * FROM orders WHERE id = ?", result);
    }

    @Test
    void parse_shouldHandleNullParameter() {
        String log = "Preparing: SELECT * FROM orders WHERE id = ?\nParameters: null";
        String result = MybatisLogParser.parse(log);
        assertEquals("SELECT * FROM orders WHERE id = null", result);
    }

    @Test
    void parse_shouldQuoteTimestampParameter() {
        String log = "Preparing: SELECT * FROM orders WHERE created_at > ?\nParameters: 2024-01-01 00:00:00(Timestamp)";
        String result = MybatisLogParser.parse(log);
        assertTrue(result.contains("'2024-01-01 00:00:00'"));
    }

    @Test
    void parse_shouldNotQuoteIntegerParameter() {
        String log = "Preparing: SELECT * FROM orders WHERE id = ?\nParameters: 100(Long)";
        String result = MybatisLogParser.parse(log);
        assertEquals("SELECT * FROM orders WHERE id = 100", result);
    }

    @Test
    void parse_shouldHandleMultipleParametersWithDifferentTypes() {
        String log = "Preparing: INSERT INTO orders (id, name, price, created_at) VALUES (?, ?, ?, ?)\n"
                   + "Parameters: 1(Long), 测试订单(String), 99.99(BigDecimal), 2024-01-01(Timestamp)";
        String result = MybatisLogParser.parse(log);
        assertTrue(result.contains("1"));
        assertTrue(result.contains("'测试订单'"));
        assertTrue(result.contains("99.99"));
        assertTrue(result.contains("'2024-01-01'"));
    }

    // ==================== SqlAstValidator ====================

    @Test
    void validate_shouldAcceptSimpleSelect() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "SELECT * FROM orders WHERE id = 1");
        assertTrue(r.isSafe());
        assertEquals(1, r.tableNames().size());
        assertTrue(r.tableNames().contains("orders"));
    }

    @Test
    void validate_shouldBlockMultiStatement() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "SELECT 1; DROP TABLE orders");
        assertFalse(r.isSafe());
        assertTrue(r.errorMessage().contains("多条"));
    }

    @Test
    void validate_shouldBlockInsert() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "INSERT INTO orders VALUES (1, 'test')");
        assertFalse(r.isSafe());
        assertTrue(r.errorMessage().contains("仅支持 SELECT"));
    }

    @Test
    void validate_shouldBlockDelete() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "DELETE FROM orders WHERE id = 1");
        assertFalse(r.isSafe());
        assertTrue(r.errorMessage().contains("仅支持 SELECT"));
    }

    @Test
    void validate_shouldBlockUpdate() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "UPDATE orders SET status = 'done' WHERE id = 1");
        assertFalse(r.isSafe());
    }

    @Test
    void validate_shouldBlockDrop() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "DROP TABLE orders");
        assertFalse(r.isSafe());
    }

    @Test
    void validate_shouldReportSyntaxError() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "SELEC * FROM orders");
        assertFalse(r.isSafe());
        assertTrue(r.errorMessage().contains("语法错误"));
    }

    @Test
    void validate_shouldExtractMultipleTableNames() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "SELECT * FROM orders o JOIN users u ON o.uid = u.id");
        assertTrue(r.isSafe());
        assertTrue(r.tableNames().contains("orders"));
        assertTrue(r.tableNames().contains("users"));
    }

    @Test
    void validate_shouldExtractCrossDbTableNames() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract(
                "SELECT * FROM orders o JOIN archive_db.old_orders a ON o.id = a.order_id");
        assertTrue(r.isSafe());
        assertTrue(r.tableNames().stream().anyMatch(t -> t.contains("old_orders")));
    }

    @Test
    void validate_shouldRejectEmptySql() {
        SqlAstValidator.ValidationResult r = SqlAstValidator.validateAndExtract("");
        assertFalse(r.isSafe());
    }

    // ==================== SqlDataMasker ====================

    @Test
    void mask_shouldMaskPhoneNumber() {
        String sql = "SELECT * FROM users WHERE phone = '13812345678'";
        String result = SqlDataMasker.mask(sql, java.util.List.of("users"));
        assertTrue(result.contains("****"));
        assertFalse(result.contains("13812345678"));
        assertTrue(result.contains("138****5678"));
    }

    @Test
    void mask_shouldMaskIdCard() {
        String sql = "SELECT * FROM users WHERE id_card = '310101199001011234'";
        String result = SqlDataMasker.mask(sql, java.util.List.of("users"));
        assertTrue(result.contains("***"));
        assertFalse(result.contains("310101199001011234"));
    }

    @Test
    void mask_shouldMaskEmail() {
        String sql = "SELECT * FROM users WHERE email = 'test@example.com'";
        String result = SqlDataMasker.mask(sql, java.util.List.of("users"));
        assertTrue(result.contains("***@example.com"));
    }

    @Test
    void mask_shouldPreserveNonSensitiveFields() {
        String sql = "SELECT * FROM orders WHERE status = 'done'";
        String result = SqlDataMasker.mask(sql, java.util.List.of("orders"));
        assertEquals(sql, result);
    }

    @Test
    void mask_shouldReturnNullForNullInput() {
        assertNull(SqlDataMasker.mask(null, null));
    }

    @Test
    void mask_shouldMaskMultipleSensitiveFields() {
        String sql = "SELECT * FROM users WHERE phone = '13812345678' AND id_card = '310101199001011234'";
        String result = SqlDataMasker.mask(sql, java.util.List.of("users"));
        assertFalse(result.contains("13812345678"));
        assertFalse(result.contains("310101199001011234"));
    }

    @Test
    void mask_shouldMaskPasswordField() {
        String sql = "SELECT * FROM users WHERE password = 'secret123'";
        String result = SqlDataMasker.mask(sql, java.util.List.of("users"));
        assertTrue(result.contains("***"));
    }

    @Test
    void mask_shouldHandleEmptySql() {
        String result = SqlDataMasker.mask("", java.util.List.of());
        assertEquals("", result);
    }
}
