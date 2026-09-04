package com.xuan.boot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    public static final String EXCHANGE = "classroom.reserve.exchange";
    public static final String NOTIFY_QUEUE = "classroom.reserve.notify.queue";
    public static final String NOTIFY_ROUTING_KEY = "reserve.notify";
    public static final String DEAD_EXCHANGE = "classroom.reserve.dlx";
    public static final String NOTIFY_DEAD_QUEUE = "classroom.reserve.notify.dlq";
    public static final String NOTIFY_DEAD_ROUTING_KEY = "reserve.notify.dead";

    @Bean
    public DirectExchange reserveExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange reserveDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(NOTIFY_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(NOTIFY_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding notifyBinding(@Qualifier("notifyQueue") Queue notifyQueue,
                                 @Qualifier("reserveExchange") DirectExchange reserveExchange) {
        return BindingBuilder.bind(notifyQueue).to(reserveExchange).with(NOTIFY_ROUTING_KEY);
    }

    @Bean
    public Queue notifyDeadQueue() {
        return QueueBuilder.durable(NOTIFY_DEAD_QUEUE).build();
    }

    @Bean
    public Binding notifyDeadBinding(@Qualifier("notifyDeadQueue") Queue notifyDeadQueue,
                                     @Qualifier("reserveDeadExchange") DirectExchange reserveDeadExchange) {
        return BindingBuilder.bind(notifyDeadQueue).to(reserveDeadExchange).with(NOTIFY_DEAD_ROUTING_KEY);
    }

    @Bean
    public SmartInitializingSingleton rabbitTemplateCallbacks(RabbitTemplate rabbitTemplate) {
        return () -> {
            rabbitTemplate.setMandatory(true);
            rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
                if (!ack) {
                    log.warn("RabbitMQ publish confirm failed. correlationData={}, cause={}", correlationData, cause);
                }
            });
            rabbitTemplate.setReturnsCallback(returned -> log.warn(
                    "RabbitMQ returned message. exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        };
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
