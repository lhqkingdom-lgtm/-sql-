package com.slowsql.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置——精简版。
 * 只保留诊断交换机 + task.normal + done.queue，无 DLQ/DLX/TTL。
 * DB 是状态源，消息丢了前端重诊即可。
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "diagnosis.exchange";

    public static final String QUEUE_TASK_NORMAL = "diagnosis.task.normal";
    public static final String QUEUE_DONE = "diagnosis.done.queue";

    public static final String ROUTING_TASK_NORMAL = "task.normal";
    public static final String ROUTING_DONE = "done.*";

    // ===== 诊断交换机 =====

    @Bean
    public TopicExchange diagnosisExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    // ===== 自动采集队列（无 TTL，Agent 跟不上就排着） =====

    @Bean
    public Queue taskNormalQueue() {
        return QueueBuilder.durable(QUEUE_TASK_NORMAL).build();
    }

    @Bean
    public Binding taskNormalBinding() {
        return BindingBuilder.bind(taskNormalQueue()).to(diagnosisExchange()).with(ROUTING_TASK_NORMAL);
    }

    // ===== 完成队列（Agent → Gateway 可靠回调，无 TTL） =====

    @Bean
    public Queue doneQueue() {
        return QueueBuilder.durable(QUEUE_DONE).build();
    }

    @Bean
    public Binding doneBinding() {
        return BindingBuilder.bind(doneQueue()).to(diagnosisExchange()).with(ROUTING_DONE);
    }

    // ===== RabbitTemplate =====

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setExchange(EXCHANGE);
        return template;
    }

}
