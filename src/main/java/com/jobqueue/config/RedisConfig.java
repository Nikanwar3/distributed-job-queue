package com.jobqueue.config;

import com.jobqueue.queue.CancellationSubscriber;
import com.jobqueue.queue.RedisKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer cancellationListenerContainer(
            RedisConnectionFactory connectionFactory, CancellationSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisKeys.CANCELLATION_CHANNEL));
        return container;
    }
}
