package com.slowsql.security;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.List;

/**
 * 基于 JSqlParser AST 的 SQL 安全校验器。
 * 拦截多语句拼接和非 SELECT 操作，提取物理表名。
 */
public final class SqlAstValidator {

    private SqlAstValidator() {}

    /**
     * 校验 SQL 是否为单条 SELECT，提取涉及的物理表名。
     */
    public static ValidationResult validateAndExtract(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() > 1) {
                return new ValidationResult(
                        false,
                        "安全拦截：禁止执行多条拼接的 SQL 语句",
                        List.of(),
                        sql);
            }

            Statement statement = statements.getStatements().get(0);
            if (!(statement instanceof Select)) {
                return new ValidationResult(
                        false,
                        "安全拦截：系统仅支持 SELECT 查询语句的分析，禁止包含 DML/DDL 等写操作",
                        List.of(),
                        sql);
            }

            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
            List<String> tableNames = tablesNamesFinder.getTableList(statement);
            return new ValidationResult(true, null, tableNames, sql);
        } catch (JSQLParserException e) {
            return new ValidationResult(
                    false,
                    "语法错误：无法解析该 SQL，请检查语法",
                    List.of(),
                    sql);
        }
    }

    public record ValidationResult(boolean isSafe, String errorMessage, List<String> tableNames, String cleanSql) {}
}
