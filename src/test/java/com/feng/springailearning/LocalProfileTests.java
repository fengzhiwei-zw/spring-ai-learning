package com.feng.springailearning;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.feng.springailearning.service.DocumentIngestionService;
import redis.clients.jedis.RedisClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不使用中间件替身：验证 local 本身能完整装配，并且不产生外部存储 Bean。 */
@ActiveProfiles("local")
@SpringBootTest(properties = "spring.ai.openai.api-key=test-only-key")
class LocalProfileTests {

    @Autowired ApplicationContext context;
    @Autowired WebApplicationContext webContext;
    @Autowired ChatMemory memory;
    @Autowired ChatMemoryRepository repository;

    @Test
    void startsWithoutRedisChromaEmbeddingOrRag() {
        assertThat(repository).isInstanceOf(InMemoryChatMemoryRepository.class);
        assertThat(context.getBeansOfType(RedisClient.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisChatMemoryRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(ChromaApi.class)).isEmpty();
        assertThat(context.getBeansOfType(VectorStore.class)).isEmpty();
        assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();
        assertThat(context.getBeansOfType(RetrievalAugmentationAdvisor.class)).isEmpty();
        assertThat(context.getBeansOfType(DocumentIngestionService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChatClient.class)).contains("chatClient", "structuredChatClient");
    }

    @Test
    void retainsWindowedConversationMemoryAndSessionIsolation() {
        for (int turn = 0; turn < 12; turn++) {
            memory.add("local-one", List.of(new UserMessage("问题" + turn), new AssistantMessage("回答" + turn)));
        }
        assertThat(memory.get("local-one")).hasSize(20);
        assertThat(memory.get("local-one").getFirst().getText()).isEqualTo("问题2");
        assertThat(memory.get("local-two")).isEmpty();
        memory.clear("local-one");
        assertThat(memory.get("local-one")).isEmpty();
    }

    @Test
    void healthIsUpAndHttpValidationWorksWithoutCallingAModel() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/chat").param("msg", "   ")).andExpect(status().isBadRequest());
    }
}
