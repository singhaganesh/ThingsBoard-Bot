package com.seple.ThingsBoard_Bot.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "iot.exchange";
    public static final String QUEUE_NAME = "iot.events";
    public static final String ROUTING_KEY = "iot.event.#";

    @Bean
    public TopicExchange iotExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public Queue iotEventsQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding iotEventsBinding(Queue iotEventsQueue, TopicExchange iotExchange) {
        return BindingBuilder.bind(iotEventsQueue).to(iotExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setExchange(EXCHANGE_NAME);
        template.setRoutingKey("iot.event.device");
        return template;
    }
}