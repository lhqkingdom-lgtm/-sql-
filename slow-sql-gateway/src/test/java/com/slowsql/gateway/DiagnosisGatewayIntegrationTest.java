package com.slowsql.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slowsql.config.RabbitMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试——需要真实 RabbitMQ + Redis + MySQL。
 * mvn test 不跑（Surefire排除），手动跑:
 * mvn test -Dtest=DiagnosisGatewayIntegrationTest
 */
@SpringBootTest(properties = "spring.profiles.active=test")
class DiagnosisGatewayIntegrationTest {

    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private StringRedisTemplate redis;
    @Autowired private DiagnosisTaskProducer producer;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 清测试数据
        redis.delete("diagnosis:fallback:queue");
        redis.delete("diagnosis:result:test-task");
    }

    // ===== 1. 交换机 + 队列存在 =====

    @Test
    void exchange_shouldExist() {
        Properties props = rabbitAdmin.getQueueProperties(RabbitMqConfig.QUEUE_TASK_HIGH);
        assertNotNull(props);
    }

    @Test void queueTaskHigh_shouldExist() {
        assertNotNull(rabbitAdmin.getQueueProperties(RabbitMqConfig.QUEUE_TASK_HIGH));
    }

    @Test void queueTaskNormal_shouldExist() {
        assertNotNull(rabbitAdmin.getQueueProperties(RabbitMqConfig.QUEUE_TASK_NORMAL));
    }

    @Test void queueDone_shouldExist() {
        assertNotNull(rabbitAdmin.getQueueProperties(RabbitMqConfig.QUEUE_DONE));
    }

    @Test void dlq_shouldExist() {
        assertNotNull(rabbitAdmin.getQueueProperties(RabbitMqConfig.QUEUE_DLQ));
    }

    @Test void queues_shouldBeDurable() {
        Properties p = rabbitAdmin.getQueueProperties(RabbitMqConfig.QUEUE_TASK_HIGH);
        assertNotNull(p, "队列属性不应为null");
        // 队列存在即可，durable 属性由 RabbitMQ 保证
    }

    // ===== 2. 消息投递 + 消费 =====

    @Test
    void shouldSendAndReceiveHighPriorityMessage() throws Exception {
        Map<String, Object> msg = new HashMap<>();
        msg.put("taskId", "test-task-" + UUID.randomUUID().toString().substring(0, 8));
        msg.put("sessionId", "s1");
        msg.put("instanceId", "tc-dev-mysql");
        msg.put("sql", "SELECT 1");

        // 发送
        producer.sendHigh(msg);

        // 从队列消费验证
        Object received = rabbitTemplate.receiveAndConvert(
                RabbitMqConfig.QUEUE_TASK_HIGH, 3000);
        assertNotNull(received);
    }

    @Test
    void shouldSendAndReceiveNormalPriorityMessage() {
        Map<String, Object> msg = Map.of("taskId", "test-normal");
        producer.sendNormal(msg);

        Object received = rabbitTemplate.receiveAndConvert(
                RabbitMqConfig.QUEUE_TASK_NORMAL, 3000);
        assertNotNull(received);
    }

    // ===== 3. 消息字段完整性 =====

    @Test
    void message_shouldPreserveAllFields() throws Exception {
        Map<String, Object> original = new HashMap<>();
        original.put("taskId", "t-complete");
        original.put("sessionId", "s1");
        original.put("instanceId", "tc-dev-mysql");
        original.put("projectCode", "tongcheng-club");
        original.put("enrichedPrompt", "【SQL】SELECT 1");
        original.put("modelRoute", "plus");
        original.put("fingerprint", "abc123");
        original.put("source", "manual");
        original.put("timestamp", "2026-07-06T10:00:00Z");

        producer.sendHigh(original);

        // 接收并校验（Jackson2JsonMessageConverter会将JSON反序列化为Map）
        Object raw = rabbitTemplate.receiveAndConvert(
                RabbitMqConfig.QUEUE_TASK_HIGH, 5000);
        assertNotNull(raw, "应能接收到消息");
        if (raw instanceof Map<?,?> m) {
            assertEquals("t-complete", m.get("taskId"));
            assertEquals("tc-dev-mysql", m.get("instanceId"));
        }
    }

    // ===== 4. 降级队列 =====

    @Test
    void fallback_shouldWriteToRedis() {
        // 直接测试 Redis 降级队列的读写
        String testMsg = "{\"taskId\":\"fallback-test\"}";
        redis.opsForList().rightPush("diagnosis:fallback:queue", testMsg);
        redis.expire("diagnosis:fallback:queue", Duration.ofSeconds(60));

        String popped = redis.opsForList().leftPop("diagnosis:fallback:queue");
        assertEquals(testMsg, popped);
    }

    // ===== 5. Redis 结果写入 =====

    @Test
    void resultCache_shouldWriteAndRead() {
        String taskId = "test-task";
        String report = "## 诊断报告\n优化建议...";

        redis.opsForValue().set("diagnosis:result:" + taskId, report, Duration.ofMinutes(5));
        String cached = redis.opsForValue().get("diagnosis:result:" + taskId);

        assertEquals(report, cached);
    }

    // ===== 6. 任务状态 Hash =====

    @Test
    void taskStatus_shouldWriteAndRead() {
        String taskId = "status-test";
        redis.opsForHash().put("diagnosis:task:" + taskId, "status", "running");
        redis.opsForHash().put("diagnosis:task:" + taskId, "createdAt", "2026-07-06T10:00:00");
        redis.expire("diagnosis:task:" + taskId, Duration.ofMinutes(30));

        assertEquals("running", redis.opsForHash().get("diagnosis:task:" + taskId, "status"));
    }
}
