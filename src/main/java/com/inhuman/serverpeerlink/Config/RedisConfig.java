package com.inhuman.serverpeerlink.Config;

import com.inhuman.serverpeerlink.Models.Room;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration
@RequiredArgsConstructor
public class RedisConfig {
    private final ObjectMapper mapper;
    @Bean
    public RedisTemplate<String, Room> redisTemplate(RedisConnectionFactory redisConnectionFactory){
        RedisTemplate<String, Room> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        redisTemplate.setKeySerializer(new StringRedisSerializer());

        JacksonJsonRedisSerializer<Room> valueSerializer = new JacksonJsonRedisSerializer<>(mapper, Room.class);
        redisTemplate.setValueSerializer(valueSerializer);
        redisTemplate.setHashValueSerializer(valueSerializer);

        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

}
