# CLAUDE.md — Slow SQL Analyzer V5.0

## 项目概述

**slow-sql-analyzer-v5** — AI 驱动的 MySQL 慢 SQL 智能诊断平台。V5.0 全新架构：
- **Java (Spring Boot 3.5)** — 数据网关 + 安全网关 + 采集调度平台
- **Python (FastAPI)** — Agent 诊断引擎（LLM 调用 + 工具编排）
- **Vue3 (Vite)** — 前端 SPA
- **RabbitMQ** — 异步任务队列
- **Redis** — 缓存/去重/ChatMemory/降级队列

## 核心设计原则

- **按 MySQL 实例建模，不按库建模** — JDBC 连接的是实例（host:port），一个实例一个 HikariCP
- **Java 管数据，Python 管推理** — Python 永远不碰 MySQL，所有数据获取经 Java 安全网关
- **配置两层** — `projects` + `instances`，无环境/角色概念

## 项目结构

```
D:\slow-sql-analyzer-v5\
├── slow-sql-gateway\       # Java Spring Boot 网关
├── slow-sql-agent\         # Python FastAPI Agent（待建）
├── slow-sql-web\           # Vue3 前端（待建）
├── docs\                   # 架构/接口/部署文档
├── docker-compose.yml      # 待建
└── CLAUDE.md
```

## 开发铁律

**每个 Step 必须按顺序执行，缺一不可：**

1. **先展示测试计划** — 列出所有测试用例（正常/边界/异常/极端），等我确认
2. **写代码** — 先底层（实体→配置→服务）→ 再上层（Controller）
3. **写测试** — Mock 单测 + MySQL 集成测试（涉及 DB 操作时）+ 边界覆盖
4. **mvn clean test** — 全量单测必须通过，集成测试单独跑
5. **全部通过后**：
   - Step 打勾 ✅
   - `git add` + `git commit`（格式：`feat(V5.0): Step1 - 描述`）
   - 更新 `AI_CONTEXT.md`

**测试原则：**
- 单元测试 Mock 所有外部依赖（DataSource / JdbcTemplate / Redis / RabbitMQ）
- 涉及 DB 操作 → 补 MySQL 集成测试，连真实 `slow_sql_platform` 库
- 集成测试文件名用 `*IntegrationTest.java`，Surefire 已排除（`mvn test` 不跑）
- 边界覆盖：空值、null、空列表、连接失败、超时、非法参数
- **不使用旧项目的 `test_sql` 库，V5 使用独立库 `slow_sql_platform`**

**Git 仓库：**
- 新项目使用独立 Git 仓库（`D:\slow-sql-analyzer-v5\`）
- 不与旧项目（`D:\慢sql解析\`）混杂

**Commit 格式：**
```
feat(V5.0): Step1 - 配置模型（projects+instances两层）
test(V5.0): Step1 - DataSourceManager测试补充
docs(V5.0): 更新AI_CONTEXT架构文档
```

## 数据库

- **平台库**：`slow_sql_platform`（独立，不与旧项目共用）
- **目标库**：各 MySQL 实例上的业务库（只读诊断）
- **表**：`diagnosis_record` `captured_sql` `rag_document`

## 构建命令

```bash
# 全量单测（无需 MySQL）
cd slow-sql-gateway && mvn clean test

# 单个测试
mvn test -Dtest=DataSourceManagerTest

# 集成测试（需要 MySQL + Redis）
mvn test -Dtest=DataSourceManagerIntegrationTest

# 启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## 测试后清理（铁律）

**启动任何进程测试完后，必须在同一个 turn 内关掉，不能留僵尸进程。**

```powershell
# 关 Java
$p = (Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue).OwningProcess
if ($p) { Stop-Process -Id $p -Force }

# 关 Python
$ports = @(8000, 8001, 8002)
foreach ($port in $ports) {
    $p = (Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue).OwningProcess
    if ($p) { Stop-Process -Id $p -Force }
}
```

**不关的后果：下次测试端口冲突、RMQ 队列被旧消费者抢占、Redis 连接泄漏。**
