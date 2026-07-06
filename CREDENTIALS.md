# V5.0 全栈凭据清单

> 测试窗口可直接复制使用

---

## 环境变量

```powershell
# DeepSeek LLM
$env:DEEPSEEK_API_KEY = "sk-a8626034a5714edca3a23ca63fc462f82"
```

---

## MySQL

| 项目 | 值 |
|------|-----|
| 平台库地址 | `localhost:3306` |
| 平台库名 | `slow_sql_platform` |
| 用户名 | `root` |
| 密码 | `123456` |

| 目标实例 | 值 |
|----------|-----|
| 测试库地址 | `localhost:3306` |
| 测试库名 | `test_sql` |
| 测试表 | `_slow_test`（id, name, status, 索引 idx_status） |
| 用户名 | `root` |
| 密码 | `123456` |

```powershell
# 建库（如未建）
mysql -u root -p123456 -e "CREATE DATABASE IF NOT EXISTS slow_sql_platform DEFAULT CHARACTER SET utf8mb4"
```

---

## Redis

| 项目 | 值 |
|------|-----|
| 地址 | `localhost:6379` |
| 密码 | `123456` |
| 库号 | `2` |

```
redis-cli -a 123456 -n 2 ping
```

---

## RabbitMQ

| 项目 | 值 |
|------|-----|
| 地址 | `localhost:5672` |
| 管理面板 | `http://localhost:15672` |
| 用户名 | `guest` |
| 密码 | `guest` |
| Java 内部令牌 | `slow-sql-internal-token-v5` |

---

## DeepSeek LLM

| 项目 | 值 |
|------|-----|
| API 地址 | `https://api.deepseek.com/v1` |
| 模型名 | `deepseek-v4-pro` |
| API Key | `sk-a8626034a5714edca3a23ca63fc462f82` |

---

## 启动命令

### 0. 必须先设环境变量（ym配了 ENC 占位，本地用明文覆盖）

```powershell
$env:MYSQL_PASSWORD = "123456"
$env:INSTANCE_PASSWORD = "123456"
$env:REDIS_PASSWORD = "123456"
$env:JASYPT_ENCRYPTOR_PASSWORD = ""   # 空=不解密, 密码走环境变量
```

### 1. 启动 Java Gateway

```powershell
cd D:\slow-sql-analyzer-v5\slow-sql-gateway
mvn spring-boot:run
```

健康检查：`curl http://localhost:8080/api/data/tc-dev-mysql/locks -H "X-Internal-Token: slow-sql-internal-token-v5"`

### 2. 启动 Python Agent

```powershell
$env:DEEPSEEK_API_KEY = "sk-a8626034a5714edca3a23ca63fc462f82"
cd D:\slow-sql-analyzer-v5\slow-sql-agent
python main.py
```

健康检查：`curl http://localhost:8000/health`

### 3. 端到端测试

```powershell
curl -X POST http://localhost:8080/api/sql/analyze `
  -H "Content-Type: application/json" `
  -d '{"instanceId":"tc-dev-mysql","sql":"SELECT * FROM _slow_test WHERE status=''done''","projectCode":"test"}'
# → 202 {"taskId":"xxx","status":"pending"}

curl http://localhost:8080/api/sql/result/{taskId}
# → {"status":"completed","report":"## 核心瓶颈..."}
```

---

## 快速验证清单

- [ ] MySQL: `mysql -u root -p123456 -e "SELECT 1"`
- [ ] Redis: `redis-cli -a 123456 -n 2 PING` → `PONG`
- [ ] RabbitMQ: `http://localhost:15672` guest/guest
- [ ] Java: 启动后 `curl localhost:8080/api/data/tc-dev-mysql/locks -H "X-Internal-Token: slow-sql-internal-token-v5"`
- [ ] Python: 启动后 `curl localhost:8000/health`
- [ ] 全链路: POST analyze → 202 → 等 30s → result 返回 completed
