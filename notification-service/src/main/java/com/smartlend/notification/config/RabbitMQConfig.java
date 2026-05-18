package com.smartlend.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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

    @Bean
    public TopicExchange smartlendExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue loanEventsQueue() {
        return QueueBuilder.durable(loanEventsQueue).build();
    }

    @Bean
    public Declarables bindings(Queue loanEventsQueue, TopicExchange smartlendExchange) {
        return new Declarables(
            BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with("loan.approved"),
            BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with("loan.rejected"),
            BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with("loan.applied"),
            BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with("user.registered"),
            BindingBuilder.bind(loanEventsQueue).to(smartlendExchange).with("loan.emi.due")
        );
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        return factory;
    }
}