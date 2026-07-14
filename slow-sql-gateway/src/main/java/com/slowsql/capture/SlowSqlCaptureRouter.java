package com.slowsql.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 采集路由工具——构建诊断上下文（含规则引擎匹配）。
 */
@Component
public class SlowSqlCaptureRouter {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlCaptureRouter.class);
    private static RuleEngine ruleEngine;

    public SlowSqlCaptureRouter(RuleEngine ruleEngine) {
        SlowSqlCaptureRouter.ruleEngine = ruleEngine;
    }

    public static String buildDiagnosisContext(SlowSqlEvent event) {
        StringBuilder ctx = new StringBuilder("【自动采集信息】\n");
        if (event.getMetrics() != null) {
            ctx.append(String.format("- 耗时: %.2f 秒\n", event.getMetrics().getQueryTimeSec()));
            ctx.append(String.format("- 扫描: %d 行\n", event.getMetrics().getRowsExamined()));
        }
        ctx.append(String.format("- 来源: %s\n", event.getSource()));
        ctx.append(String.format("- 库: %s\n", event.getDbName() != null ? event.getDbName() : "unknown"));
        ctx.append("\n【待分析SQL】\n").append(event.getSqlText());

        // 规则引擎匹配
        try {
            if (ruleEngine != null && event.getSqlText() != null) {
                String rules = ruleEngine.match(event.getSqlText());
                if (!rules.isEmpty()) {
                    ctx.append("\n").append(rules);
                }
            }
        } catch (Exception e) {
            log.debug("规则引擎匹配异常(降级): {}", e.getMessage());
        }

        return ctx.toString();
    }
}
