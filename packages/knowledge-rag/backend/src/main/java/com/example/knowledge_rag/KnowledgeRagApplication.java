package com.example.knowledge_rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync   // 启用 @Async 支持，必须加在主类或 @Configuration 类上
public class KnowledgeRagApplication {
	public static void main(String[] args) {
		SpringApplication.run(KnowledgeRagApplication.class, args);
	}
}