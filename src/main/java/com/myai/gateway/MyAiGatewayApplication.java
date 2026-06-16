package com.myai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MyAiGatewayApplication {

    public static void main(String[] args) {
        // 设置文件编码为 UTF-8（作为防御措施，配合 server.servlet.encoding.force=true 和
        // Dockerfile 中的 -Dfile.encoding=UTF-8 使用）
        System.setProperty("file.encoding", "UTF-8");
        
        SpringApplication.run(MyAiGatewayApplication.class, args);
    }
}
