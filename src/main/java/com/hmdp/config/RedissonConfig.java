package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.config.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){

        Config config = new Config();
        config.useSingleServer();


        return Redssion.create(config);
    }
}
