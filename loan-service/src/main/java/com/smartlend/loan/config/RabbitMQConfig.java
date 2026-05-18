package com.smartlend.loan.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queue.loan-events}")
    private String loanEventsQueue;

    @Value("${rabbitmq.routing-key.loan-approved}")
    private String approvedKey;

    @Value("${rabbitmq.routing-key.loan-rejected}")
    private String rejectedKey;

    @Value("${rabbitmq.routing-key.loan-applied}")
    private String appliedKey;

    @Value("${rabbitmq.routing-key.user-registered}")
    private String userRegisteredKey;

    @Bean
    public TopicExchange smartlendExchange() {
        return new TopicExchange(exchange);
    }

    @Bean
    public Queue loanEventsQueue() {
        return QueueBuilder.durable(loanEventsQueue).build();
    }

    @Bean
    public Binding approvedBinding(Queue loanEventsQueue, TopicExchange smartlendExchange) {
        return BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with(approvedKey);
    }

    @Bean
    public Binding rejectedBinding(Queue loanEventsQueue, TopicExchange smartlendExchange) {
        return BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with(rejectedKey);
    }

    @Bean
    public Binding appliedBinding(Queue loanEventsQueue, TopicExchange smartlendExchange) {
        return BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with(appliedKey);
    }

    @Bean
    public Binding userRegisteredBinding(Queue loanEventsQueue, TopicExchange smartlendExchange) {
        return BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with(userRegisteredKey);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
