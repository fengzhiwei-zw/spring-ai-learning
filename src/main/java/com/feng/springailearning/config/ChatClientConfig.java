package com.feng.springailearning.config;

import com.feng.springailearning.advisor.ProductionLoggingAdvisor;
import com.feng.springailearning.advisor.ProductionVectorMemoryAdvisor;
import com.feng.springailearning.advisor.LimitedToolCallingAdvisor;
import com.feng.springailearning.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryProperties;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.RedisClient;

import java.time.Duration;

@Configuration
public class ChatClientConfig {

    @Bean
    @Profile("!local")
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model}") String model,
            @Value("${spring.ai.openai.embedding.dimensions}") int dimensions,
            @Value("${spring.ai.openai.embedding.timeout:60s}") Duration timeout) {
        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .dimensions(dimensions)
                .timeout(timeout)
                .build();
        return OpenAiEmbeddingModel.builder()
                .options(embeddingOptions)
                .build();
    }

    @Bean
    @Profile("!local")
    public ChromaApi chromaApi(@Value("${app.ai.chroma-base-url}") String baseUrl) {
        return ChromaApi.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 知识库向量存储（RAG 专用）
     */
    @Bean
    @Profile("!local")
    public VectorStore knowledgeVectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel,
            @Value("${app.ai.knowledge-collection}") String collection) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collection)
                .initializeSchema(true)
                .build();
    }

    /**
     * 对话记忆专用向量存储（与知识库分开，避免污染）
     */
    @Bean
    @Profile("!local")
    public VectorStore memoryVectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel,
            @Value("${app.ai.memory-collection}") String collection) {
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collection)
                .initializeSchema(true)
                .build();
    }

    /** 查询改写、知识检索与空上下文回退。 */
    @Bean
    @Profile("!local")
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            @Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
            ChatClient.Builder chatClientBuilder) {
        ChatClient.Builder builder = chatClientBuilder.clone()
                .defaultOptions(ChatOptions.builder().temperature(0.0));
        // 可选：查询改写（强烈推荐，提升召回质量）
        var rewriteTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(builder) // 改写时温度要低
                .build();

        var documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(knowledgeVectorStore)
                .similarityThreshold(0.55)
                .topK(5)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(rewriteTransformer)          // 查询改写
                .documentRetriever(documentRetriever)
                // 空知识库时保留原问题，使普通聊天和工具调用仍可继续。
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                // .documentPostProcessors(...)               // 可加重排、去重等
                .order(Ordered.HIGHEST_PRECEDENCE + 200)        // 优先执行 RAG
                .build();
    }

    /**
     * 基于向量的长期对话记忆
     */
    @Bean
    @Profile("!local")
    public ProductionVectorMemoryAdvisor vectorStoreChatMemoryAdvisor(
            @Qualifier("memoryVectorStore") VectorStore memoryVectorStore) {
        return new ProductionVectorMemoryAdvisor(memoryVectorStore);
    }

    @Bean
    @Profile("!local")
    public RedisChatMemoryRepository redisChatMemoryRepository(
            RedisClient jedisClient, RedisChatMemoryProperties properties) {
        // 手动声明仓库会使自动配置退让，因此必须在这里应用仓库属性。
        var builder = RedisChatMemoryRepository.builder().jedisClient(jedisClient);
        if (properties.getInitializeSchema() != null) {
            builder.initializeSchema(properties.getInitializeSchema());
        }
        if (properties.getIndexName() != null) {
            builder.indexName(properties.getIndexName());
        }
        if (properties.getKeyPrefix() != null) {
            builder.keyPrefix(properties.getKeyPrefix());
        }
        if (properties.getTimeToLive() != null) {
            builder.timeToLive(properties.getTimeToLive());
        }
        if (properties.getMaxConversationIds() != null) {
            builder.maxConversationIds(properties.getMaxConversationIds());
        }
        if (properties.getMaxMessagesPerConversation() != null) {
            builder.maxMessagesPerConversation(properties.getMaxMessagesPerConversation());
        }
        if (properties.getMetadataFields() != null) {
            builder.metadataFields(properties.getMetadataFields());
        }
        return builder.build();
    }

    @Bean
    @Profile("local")
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                 @Nullable RetrievalAugmentationAdvisor retrievalAugmentationAdvisor,
                                 @Value("${app.ai.max-tool-rounds:5}") int maxToolRounds,
                                 @Value("${app.ai.max-tool-calls:10}") int maxToolCalls) {
        var client = baseClient(builder, chatMemory)
                .defaultAdvisors(new LimitedToolCallingAdvisor(maxToolRounds, maxToolCalls));
        if (retrievalAugmentationAdvisor != null) {
            client.defaultAdvisors(retrievalAugmentationAdvisor);
        }
        return client.build();
    }

    /** 提取、代码审查和多模态不能被知识库内容改写，也不需要天气工具。 */
    @Bean
    public ChatClient structuredChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("你是一位资深Java架构师，回答要专业，有重点，能指出难点和易错点")
                .defaultOptions(ChatOptions.builder().temperature(0.0))
                .defaultAdvisors(memoryAdvisor(chatMemory), new ProductionLoggingAdvisor())
                .build();
    }

    private ChatClient.Builder baseClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("你是一位资深Java架构师，回答要专业，有重点，能指出难点和易错点")
                .defaultTools(new WeatherTool())
                .defaultOptions(ChatOptions.builder().temperature(0.6))
                .defaultAdvisors(memoryAdvisor(chatMemory), new ProductionLoggingAdvisor());
    }

    private MessageChatMemoryAdvisor memoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .order(Ordered.HIGHEST_PRECEDENCE + 100).build();
    }
}
