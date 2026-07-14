# 🧠 Slow SQL Analyzer V5.0

<div align="center">

**AI 驱动的 MySQL 慢查询智能诊断平台**

[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Python](https://img.shields.io/badge/Python-3.12-3776AB?logo=python&logoColor=white)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.110-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com/)
[![Vue.js](https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![Vite](https://img.shields.io/badge/Vite-6.2-646CFF?logo=vite&logoColor=white)](https://vitejs.dev/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3-FF6600?logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)
[![LangChain](https://img.shields.io/badge/LangChain-1.0-1C3C3C?logo=langchain&logoColor=white)](https://www.langchain.com/)
[![DeepSeek](https://img.shields.io/badge/DeepSeek-Chat-4D6BFE?logo=openai&logoColor=white)](https://platform.deepseek.com/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## 📖 目录

- [项目简介](#-项目简介)
- [架构设计](#-架构设计)
- [核心业务与高并发设计](#-核心业务与高并发设计)
- [AI 智能诊断引擎](#-ai-智能诊断引擎)
- [项目结构](#-项目结构)
- [极速部署](#-极速部署)
- [效果展示](#-效果展示)
- [配置说明](#-配置说明)

---

## 🎯 项目简介

**Slow SQL Analyzer V5.0** 是一款面向 MySQL 的 AI 智能慢查询诊断平台。它通过自动采集线上慢 SQL，利用 **DeepSeek 大语言模型** 模拟资深 DBA 的诊断思维——自动获取 DDL、执行计划、索引统计等元数据，经过多轮工具调用与推理，输出三段式结构化诊断报告（**核心瓶颈 → 数据证据 → 优化建议**）。

> **核心理念**：Java 管数据，Python 管推理。让开发人员无需深厚的 DBA 经验即可快速定位和解决数据库性能瓶颈。

### 为什么选 V5.0？

| 痛点 | 传统方案 | V5.0 方案 |
|------|---------|----------|
| 慢 SQL 发现 | 手动翻慢日志 | **自动采集**（3 种源） |
| SQL 诊断 | DBA 手工 EXPLAIN | **AI 大模型自动诊断** |
| 诊断门槛 | 需要多年 DBA 经验 | **零门槛，自动推理** |
| 重复诊断 | 无去重机制 | **指纹去重 + 结果缓存** |
| 环境部署 | 手动配置中间件 | **Docker 一键启动** |

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                     Vue3 前端 ( :5173 )                   │
│         采集记录 │ 诊断历史 │ 轮询管理 │ 手动诊断          │
└──────────────────┬──────────────────────────────────────┘
                   │ HTTP RESTful
┌──────────────────┴──────────────────────────────────────┐
│              Java Spring Boot 网关 ( :8080 )              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐ │
│  │ 数据 API  │ │ 采集调度  │ │ 规则引擎  │ │ 安全网关   │ │
│  │ (DDL/EXP) │ │ (3种源)  │ │ (YAML)   │ │ (Token)    │ │
│  └──────────┘ └──────────┘ └──────────┘ └────────────┘ │
│                    │ RMQ Producer                        │
└────────────────────┼────────────────────────────────────┘
                     │ task.normal
              ┌──────┴────────┐
              │   RabbitMQ    │  异步解耦 + 削峰填谷
              └──────┬────────┘
                     │
┌────────────────────┴────────────────────────────────────┐
│             Python FastAPI Agent ( :8000 )                │
│  ┌──────────────────────────────────────────────────┐   │
│  │ LangChain + DeepSeek Chat                        │   │
│  │ 14 个诊断 Tool (Function Calling)                 │   │
│  │ Token Budget + RepeatGuard + ChatMemory           │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────┬────────────────────────────────────┘
                     │ done.queue
              ┌──────┴────────┐
              │   RabbitMQ    │
              └──────┬────────┘
                     │ Java 消费 → Redis 缓存 → 落库
              ┌──────┴────────┐
              │  Redis ( :6379 ) │ 缓存 / 去重 / ChatMemory / 降级队列
              └───────────────┘
```

### 🔐 安全双层架构

```
Python Agent  ──HTTP + Token──▶  Java 网关  ──JDBC──▶  MySQL 实例
   ❌ 永不直连 MySQL              ✅ 唯一数据库访问入口
```

- Python 侧所有 SQL 注入防护：正则校验表名、拦截 DROP/DELETE/UPDATE 等危险关键字
- Java 侧 `X-Internal-Token` 认证，保证只有受信的 Agent 能访问数据 API
- 被监控实例的连接池 **按 host:port 维度动态隔离**，互不影响

---

## ⚡ 核心业务与高并发设计

### 1. 异步解耦 —— RabbitMQ 削峰填谷

```
采集调度 ──task.normal──▶ RMQ ──▶ Agent 消费 ──done.queue──▶ RMQ ──▶ Java 结果处理
```

- **Prefetch = 1**：每条消息消费完成并 ACK 后才拉取下一条，防止 Agent 过载
- **手动 ACK**：诊断失败可 REQUEUE 或降级到 Redis 备份队列
- **降级队列恢复**：Agent 启动时自动检查 Redis 中的积压任务，逐条重新投递

### 2. 多层缓存策略 —— Redis 热数据加速

| 缓存层 | Key Pattern | TTL | 作用 |
|--------|------------|-----|------|
| **诊断结果缓存** | `diagnosis:cache:{fingerprint}:{explainKey}` | 7 天 | 同 SQL + 同执行计划命中直接返回 |
| **指纹去重** | `diagnosis:dedup:{fingerprint}` | 30 分钟 | 短时间内不重复诊断 |
| **ChatMemory** | `diagnosis:memory:{session_id}` | 1 小时 | LangChain 多轮对话上下文 |
| **诊断进度** | `diagnosis:task:{taskId}` | 30 分钟 | SSE 实时推送诊断步骤 |
| **降级队列** | `diagnosis:fallback:queue` | 持久 | RMQ 不可用时兜底 |

> **设计精髓**：诊断缓存不只是按 SQL 指纹，而是 **指纹 + EXPLAIN 的 type/key/Extra** 作为复合键。因为同一条 SQL 在不同数据量下执行计划可能不同，只有计划一致时缓存才有意义。P0（最严重）不缓存，保证紧急问题每次走 LLM 实时分析。

### 3. 并发控制与锁机制

- **指纹去重锁**：高并发下同一条慢 SQL 可能在短时间内被多次采集。`FingerprintDedupService` 基于 Redis `SETNX` 原子操作，保证同一指纹 30 分钟内只有首次通过
- **Prefecth=1 串行消费**：诊断任务是 CPU/LLM 密集型，单消费者 + `prefetch_count=1` 避免并发诊断导致 LLM API 限流
- **规则引擎线程安全**：`@PostConstruct` 加载 YAML 规则到不可变 List，运行时不修改，天然线程安全

### 4. 服务降级

```
正常链路：采集 → RMQ → Agent → RMQ → Java → 落库
降级链路：采集 → Redis List (LPUSH) → Agent 定时恢复 (RPOP)
```

RMQ 连接异常时，采集任务写入 Redis List 作为降级队列；Agent 恢复后逐条消费，保证 **零丢失**。

---

## 🤖 AI 智能诊断引擎

### 技术栈

```yaml
LLM: DeepSeek Chat (via LangChain OpenAI-compatible interface)
框架: LangChain 1.0 + langchain-classic AgentExecutor
工具: 14 个自定义 @tool (Function Calling)
记忆: RedisChatMessageHistory (多轮对话上下文)
防护: TokenBudget + RepeatGuard + 输入安全校验
```

### 14 个诊断工具（Function Calling）

Agent 自主决策调用哪些工具，模拟 DBA 五步诊断流程：

| 阶段 | 工具 | 用途 |
|------|------|------|
| **① 信息获取** | `get_table_ddl` / `get_execution_plan` | 获取表结构和执行计划 |
| **② 快速扫描** | `check_missing_indexes` / `check_actual_row_count` | 索引缺失 + 行数偏差检查 |
| **③ 防漏检查** | `check_type_mismatch` | **最常见漏判**：VARCHAR 传数字导致索引失效 |
| **④ 深层排查** | `get_table_statistics` / `check_active_locks` | 统计信息 + 锁等待 |
| **⑤ 环境检查** | `get_buffer_pool_hit_rate` / `get_process_list` | 内存不足 / 连接耗尽 |
| **重型探针** | `get_innodb_status` / `compare_execution_plan` | 仅前几步无法定位时才调用 |

### 安全防护三层

```python
# 1. 输入校验：拒绝敏感 SQL 关键字
DANGEROUS_SQL = r"\b(update|delete|drop|insert|truncate|alter)\b"

# 2. Token 预算：超过限制自动终止，防止 LLM 无限循环
token_handler = TokenBudgetHandler(max_tokens=8000)

# 3. 重复调用保护：连续 3 次调用同一工具 → 终止并报错
repeat = RepeatGuardHandler(max_repeat=3)
```

### 规则引擎 —— 前置知识匹配

基于 `rules.yaml` 的精确规则匹配，在 AI 诊断之前拦截已知问题模式：

```yaml
rules:
  - id: R001
    pattern: "SELECT.*WHERE.*LIKE '%xxx%'"
    severity: P2
    suggestion: "前缀模糊查询无法使用索引，考虑使用全文索引或 Elasticsearch"
```

命中规则时在诊断报告中引用规则编号，未命中才交由 AI 分析——兼具**速度**和**深度**。

---

## 📁 项目结构

```
slow-sql-analyzer-v5/
├── slow-sql-gateway/              # ☕ Java Spring Boot 数据网关
│   ├── src/main/java/com/slowsql/
│   │   ├── api/                   #   REST 数据 API + 诊断历史 + RMQ 状态
│   │   ├── capture/               #   🔄 采集模块（3 种源 + 去重 + 规则引擎 + 诊断缓存）
│   │   ├── config/                #   ⚙️ 多数据源管理 + Schema 自动初始化
│   │   ├── gateway/               #   📨 RMQ 结果消费 + SSE 推送
│   │   ├── persistence/           #   💾 诊断记录 + 采集记录 CRUD
│   │   ├── rag/                   #   📚 知识库（RAG 文档检索）
│   │   └── security/              #   🔐 内部 Token 认证
│   └── Dockerfile
│
├── slow-sql-agent/                # 🐍 Python AI 诊断引擎
│   ├── main.py                    #   FastAPI 入口（含 HTTP 直连诊断）
│   ├── config.py                  #   Pydantic Settings 配置
│   ├── models.py                  #   诊断任务/结果数据模型
│   ├── agent/
│   │   ├── factory.py             #   Agent 工厂（LLM + 工具 + 记忆 + 回调）
│   │   └── callbacks.py           #   Token预算 / 指标 / 重复保护 / 进度回调
│   ├── tools/
│   │   ├── definitions.py         #   14 个 @tool 工具定义（Function Calling）
│   │   └── data_client.py         #   HTTP 客户端（调 Java 网关）
│   ├── mq/
│   │   ├── consumer.py            #   RMQ 消费者 + 降级队列恢复
│   │   └── publisher.py           #   诊断结果发布者
│   ├── prompts/diagnosis.yaml     #   System Prompt（DBA 诊断流程）
│   └── Dockerfile
│
├── slow-sql-web/                  # 🖥️ Vue3 前端 SPA
│   ├── src/views/
│   │   ├── CapturedRecords.vue    #   采集记录（分页/筛选/批量操作）
│   │   ├── DiagnosisHistory.vue   #   诊断历史（查看报告/状态追踪）
│   │   ├── PollingManage.vue      #   轮询管理（3 种采集源独立开关）
│   │   └── ManualDiagnose.vue     #   手动诊断（粘贴 SQL 即时分析）
│   ├── nginx.conf                 #   Nginx 反向代理配置
│   └── Dockerfile                 #   多阶段构建（Node build → Nginx serve）
│
├── sql/init.sql                   # 数据库初始化（自动建库建表）
├── docker-compose.yml             # 6 容器编排
└── .env.example                   # 环境变量模板
```

---

## 🚀 极速部署

### 前置条件

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) 已安装并运行
- DeepSeek API Key（[获取地址](https://platform.deepseek.com/api_keys)）

### 1. 配置环境变量

```powershell
# 复制模板
copy .env.example .env

# 编辑 .env，填入你的 API Key（必改项）
DEEPSEEK_API_KEY=sk-your-real-key-here
```

> 其他配置项（数据库密码等）本地开发无需修改。

### 2. 一键启动

```bash
docker compose up -d
```

6 个容器自动启动，首次运行会自动拉取镜像并初始化数据库。

### 3. 验证

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端界面 | http://localhost:5173 | Vue3 SPA |
| 网关健康检查 | http://localhost:8080/actuator/health | Spring Boot Actuator |
| Agent 健康检查 | http://localhost:8000/health | FastAPI Health |
| RabbitMQ 管理 | http://localhost:15672 | guest/guest |

### 国内用户注意

如果 Docker Hub 拉取镜像失败，需要在 Docker Desktop 设置中添加国内镜像源：

```json
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://registry.cn-hangzhou.aliyuncs.com"
  ]
}
```

（Docker Desktop → Settings → Docker Engine → 修改后 Apply & Restart）

---

## 📸 效果展示

> 💡 以下截图展示了系统的核心操作流程。

### 采集记录 —— 三种采集源自动入库

支持慢日志表（Performance Schema）、日志文件（tail -f 实时解析）、HTTP 端点（外部推送）三种方式。支持按库名/来源/严重级别/关键字多维筛选。

### AI 诊断报告 —— 三段式专业分析

Agent 自动调用 DDL、EXPLAIN、索引检查等工具，输出**核心瓶颈 → 数据证据 → 优化建议**三段式报告，含具体 SQL 示例。

### 轮询管理 —— 采集源独立开关

慢日志表、日志文件、HTTP 端点三种采集源可独立启停，支持自定义采集间隔、最小查询时间、回溯窗口等参数。

### Docker 部署 —— 6 容器健康运行

MySQL + Redis + RabbitMQ + Gateway + Agent + Frontend，一键启动，健康检查自动等待依赖就绪。

---

## ⚙️ 配置说明

### 必改项（.env）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 | **无，必须填写** |
| `MYSQL_PASSWORD` | MySQL root 密码 | `123456` |

### 核心参数（application.yml）

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `sql-monitor.capture.filter.min-query-time-sec` | 最小采集查询时间（秒） | `0.5` |
| `sql-monitor.capture.filter.min-rows-examined` | 最小扫描行数 | `0` |
| `sql-monitor.capture.dedup-window-minutes` | 指纹去重窗口（分钟） | `30` |
| `sql-monitor.capture.max-per-round` | 每轮采集最大投递数 | `50` |
| `sql-monitor.rate-limit.max-per-minute` | API 限流（次/分钟） | `100` |

### Agent 参数（config.py）

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `AGENT_TOKEN_BUDGET` | LLM Token 预算上限 | `8000` |
| `AGENT_MAX_ITERATIONS` | Agent 最大迭代次数 | `10` |
| `LLM_TEMPERATURE` | LLM 温度 | `0.1` |
| `LLM_TIMEOUT` | LLM 调用超时（秒） | `60` |

---

## 🛠️ 本地开发

```bash
# 1. Java 网关（需要 JDK 17+）
cd slow-sql-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 2. Python Agent（需要 Python 3.12+）
cd slow-sql-agent
pip install -r requirements.txt
python main.py

# 3. Vue 前端（需要 Node 20+）
cd slow-sql-web
npm install
npm run dev
```

> 本地开发需要自行启动 MySQL、Redis、RabbitMQ，或使用 `docker compose up mysql redis rabbitmq` 仅启动基础设施。

---

<div align="center">
  <sub>Built with ❤️ by Lan Haiqi (23281080)</sub>
</div>
