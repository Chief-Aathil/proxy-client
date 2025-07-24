package com.proxy.client.config;

import com.proxy.client.task.ProxyRequestTask;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Configuration
public class AppConfig {

    @Bean
    public BlockingQueue<ProxyRequestTask> requestQueue() {
        return new LinkedBlockingQueue<>(10);
    }
}