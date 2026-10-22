package com.feng.springailearning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-only-key")
class SpringAiDemoApplicationTests {

    @MockitoBean(name = "knowledgeVectorStore")
    VectorStore knowledgeVectorStore;

    @MockitoBean(name = "memoryVectorStore")
    VectorStore memoryVectorStore;

    @MockitoBean
    RedisChatMemoryRepository redisChatMemoryRepository;

    @MockitoBean
    EmbeddingModel embeddingModel;

	@Test
	void contextLoads() {
	}

}
