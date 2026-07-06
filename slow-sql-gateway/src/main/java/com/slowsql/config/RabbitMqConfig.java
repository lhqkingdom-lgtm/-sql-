package com.slowsql.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "diagnosis.exchange";
    public static final String DLX_EXCHANGE = "diagnosis.dlx";

    public static final String QUEUE_TASK_HIGH = "diagnosis.task.high";
    public static final String QUEUE_TASK_NORMAL = "diagnosis.task.normal";
    public static final String QUEUE_DONE = "diagnosis.done.queue";
    public static final String QUEUE_DLQ = "diagnosis.dlq";

    public static final String ROUTING_TASK_HIGH = "task.high";
    public static final String ROUTING_TASK_NORMAL = "task.normal";
    public static final String ROUTING_DONE = "done.*";
    public static final String ROUTING_DLQ = "dlq.#";

    private static final int TTL_TASK_MS = 30 * 60 * 1000;   // 30min
    private static final int TTL_DONE_MS = 10 * 60 * 1000;   // 10min

    // ===== 死信交换机 =====

    @Bean
    public TopicExchange dlxExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlxExchange()).with(ROUTING_DLQ);
    }

    // ===== 诊断交换机 =====

    @Bean
    public TopicExchange diagnosisExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    // ===== 高优队列（用户手动诊断） =====

    @Bean
    public Queue taskHighQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", "dlq.task");
        args.put("x-message-ttl", TTL_TASK_MS);
        return QueueBuilder.durable(QUEUE_TASK_HIGH).withArguments(args).build();
    }

    @Bean
    public Binding taskHighBinding() {
        return BindingBuilder.bind(taskHighQueue()).to(diagnosisExchange()).with(ROUTING_TASK_HIGH);
    }

    // ===== 普通队列（自动采集） =====

    @Bean
    public Queue taskNormalQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", "dlq.task");
        args.put("x-message-ttl", TTL_TASK_MS);
        return QueueBuilder.durable(QUEUE_TASK_NORMAL).withArguments(args).build();
    }

    @Bean
    public Binding taskNormalBinding() {
        return BindingBuilder.bind(taskNormalQueue()).to(diagnosisExchange()).with(ROUTING_TASK_NORMAL);
    }

    // ===== 完成队列（Python→Java 回调） =====

    @Bean
    public Queue doneQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", "dlq.done");
        args.put("x-message-ttl", TTL_DONE_MS);
        return QueueBuilder.durable(QUEUE_DONE).withArguments(args).build();
    }

    @Bean
    public Binding doneBinding() {
        return BindingBuilder.bind(doneQueue()).to(diagnosisExchange()).with(ROUTING_DONE);
    }

    // ===== RabbitTemplate（Publisher Confirm + mandatory + ReturnCallback） =====

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setExchange(EXCHANGE);

        // Publisher Confirm + ReturnCallback 回调由 DiagnosisTaskProducer 注入

        return template;
    }

}
