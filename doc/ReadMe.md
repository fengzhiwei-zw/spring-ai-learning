> 这是早期学习路线，当前配置、API 和已修复问题请优先参考 [项目 README](../README.md) 与 [现有功能修复说明](现有功能修复说明.md)。

好的，作为 Spring AI 专家，我来为你量身定制一套**从零到精通**的系统学习方案。Spring AI 2.0.0 GA 已于 2026 年 6 月 12 日正式发布，这是 Java AI 开发的里程碑版本，现在正是入手的最佳时机。

---

### 版本选择与技术栈基线

Spring AI 2.0.0 GA 基于全新的技术栈构建，与 1.x 系列**不兼容**，以下是硬性要求：

| 项目 | 要求 |
|------|------|
| Java | **21+**（强制，利用虚拟线程等特性） |
| Spring Boot | **4.0+** |
| Spring Framework | **7.0+** |
| Jackson | **3.x**（从 2.x 迁移需注意） |

> 建议直接使用 Spring AI 2.0 GA 版本开始学习。如需确认当前最新稳定版号，可查阅 [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/) 的 Release Notes 页面。

---

### 学习全景图

我将整个学习过程划分为 **8 个阶段**，覆盖 Spring AI 的全部核心功能。每个阶段包含：学习目标、核心知识点、实战任务、预计时长。

```
阶段1 → 环境搭建 & Hello World
阶段2 → ChatClient 与模型调用
阶段3 → Prompt 工程 & 结构化输出
阶段4 → 对话记忆（Chat Memory）
阶段5 → RAG 检索增强生成
阶段6 → Tool Calling 工具调用
阶段7 → Advisor 机制 & 可观测性
阶段8 → MCP 协议 & Agent 智能体
```

---

### 阶段 1：环境搭建 & Hello World（1~2 天）

#### 学习目标
搭建开发环境，跑通第一个 AI 对话应用。

#### 环境准备

**1. 安装 JDK 21+**

推荐使用 SDKMAN 管理多版本 JDK：
```bash
sdk install java 21.0.4-tem
sdk use java 21.0.4-tem
```

**2. 选择 AI 模型（三选一）**

| 方案 | 适用场景 | 成本 |
|------|---------|------|
| **Ollama（本地）** | 学习/离线开发 | 免费 |
| **DeepSeek API** | 性价比高，国产首选 | 极低 |
| **OpenAI API** | 功能最全 | 中等 |

推荐学习阶段使用 **Ollama**，零成本、无网络依赖：
```bash
# 安装 Ollama 后拉取模型
ollama pull qwen2.5:7b
```

**3. 创建 Spring Boot 项目**

通过 [Spring Initializr](https://start.spring.io) 创建项目，选择：
- Spring Boot 4.0.x
- 依赖：`Spring AI` → 选择对应模型的 Starter（如 `spring-ai-ollama-spring-boot-starter`）

**4. 配置文件 `application.yml`**
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen2.5:7b
```

#### 实战任务：Hello World

```java
@RestController
public class HelloController {

    private final ChatClient chatClient;

    public HelloController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/hello")
    public String hello(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
```

启动后访问 `http://localhost:8080/hello?message=你好`，即可看到 AI 回复。

---

### 阶段 2：ChatClient 与模型调用（3~5 天）

#### 学习目标
深入理解 ChatClient 统一 API，掌握同步/流式调用、多模型切换。

#### 核心知识点

**1. ChatClient 统一门面**

Spring AI 2.0 将同步和流式调用统一到 ChatClient 中，只需切换 `.call()` 和 `.stream()`：

```java
// 同步调用
String content = chatClient.prompt()
        .user("解释什么是微服务")
        .call()
        .content();

// 流式调用（打字机效果）
Flux<String> stream = chatClient.prompt()
        .user("讲一个故事")
        .stream()
        .content();
```

**2. 系统消息与角色设定**
```java
chatClient.prompt()
        .system("你是一位资深Java架构师，回答要简洁专业")
        .user("如何设计高可用系统？")
        .call()
        .content();
```

**3. 多模型切换**

只需修改配置即可切换模型，业务代码无需改动：
```yaml
# 切换到 DeepSeek
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-chat
```

**4. ChatModel 底层接口**

了解 `ChatModel` 接口是 ChatClient 的底层实现，支持 `call(Prompt)` 和 `stream(Prompt)` 两种模式，理解 `Prompt`、`ChatResponse`、`Generation` 等核心对象的关系。

#### 实战任务
- 实现一个支持 SSE 流式输出的聊天接口（配合前端打字机效果）
- 分别用 Ollama 和 DeepSeek 运行同一套代码，体验"换模型不换代码"

---

### 阶段 3：Prompt 工程 & 结构化输出（1 周）

#### 学习目标
掌握提示词模板管理和将模型输出映射为 Java 类型。

#### 核心知识点

**1. PromptTemplate 模板引擎**
```java
PromptTemplate template = new PromptTemplate(
    "请用{language}语言解释{concept}，要求{level}水平能理解"
);

Prompt prompt = template.create(Map.of(
    "language", "中文",
    "concept", "依赖注入",
    "level", "初学者"
));

String result = chatClient.prompt(prompt).call().content();
```

**2. 结构化输出（Structured Output）**

Spring AI 2.0 支持直接将模型返回映射为 Java POJO/Record，自动生成 JSON Schema：

```java
record Movie(String title, int year, String genre, double rating) {}

// 一行代码，直接获取类型安全的对象
Movie movie = chatClient.prompt()
        .user("推荐一部经典科幻电影")
        .call()
        .entity(Movie.class);

System.out.println(movie.title()); // 直接访问字段
```

**3. 自纠错结构化输出**

显式启用 `validateSchema()` 后，框架会将不符合 Schema 的输出反馈给模型并有限次重试。重试不保证成功，DTO 的 Jakarta 业务约束仍需显式校验；参见当前项目的 `AiResponses` 和 Controller 实现：

```java
// 框架自动处理格式错误，要求模型重新生成
List<Movie> movies = chatClient.prompt()
        .user("推荐3部经典科幻电影")
        .call()
        .entity(new ParameterizedTypeReference<List<Movie>>() {}, spec -> spec.validateSchema());
```

**4. 多模态输入**
```java
// 图文理解
chatClient.prompt()
        .user(u -> u.text("这张图片里有什么？")
                     .media(MimeTypeUtils.IMAGE_PNG, imageResource))
        .call()
        .content();
```

#### 实战任务
- 构建一个"电影推荐器"，输出结构化的电影列表（含评分、类型、简介）
- 实现一个图片分析接口，上传截图返回页面元素描述

---

### 阶段 4：对话记忆 Chat Memory（3~5 天）

#### 学习目标
实现多轮对话的上下文保持，理解记忆的存储与持久化。

#### 核心知识点

**1. ChatMemory 基础概念**

ChatMemory 负责管理对话历史，让模型"记住"之前说过的话：

```java
ChatMemory memory = MessageChatMemory.builder()
        .chatMemory(InMemoryChatMemory.builder().build())
        .build();

ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(ChatMemoryAdvisor.builder(memory).build())
        .build();

// 第一轮
client.prompt().user("我叫张三").call().content();
// 第二轮 —— 模型记住了你的名字
client.prompt().user("我叫什么名字？").call().content(); // 返回"张三"
```

**2. 持久化方案**

Spring AI 支持多种持久化后端：
- **JDBC**（MySQL/PostgreSQL）
- **Redis**（2.0 新增，支持全文检索和范围查询）
- **MongoDB**
- **Azure Cosmos DB**
- **Cassandra**

```yaml
# Redis 持久化示例
spring:
  ai:
    chat:
      memory:
        repository:
          redis:
            url: redis://localhost:6379
```

**3. 会话隔离**

通过 `conversationId` 实现多用户/多会话的记忆隔离：
```java
client.prompt()
        .user("帮我记住这个")
        .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, "user-123"))
        .call()
        .content();
```

> 📌 Spring AI 2.1（预计 2026 年 11 月发布）将引入全新的 Session API，并正式废弃 ChatMemory。

#### 实战任务
- 实现一个多轮对话聊天机器人，支持 Redis 持久化
- 实现多用户会话隔离

---

### 阶段 5：RAG 检索增强生成（2~3 周）⭐ 核心重点

#### 学习目标
构建企业级知识库问答系统，这是 Spring AI 最核心的应用场景。

#### 核心知识点

**1. RAG 完整流水线**

```
文档读取 → 文档切片 → 向量化 → 存入向量库 → 检索 → 注入Prompt → 生成回答
```

**2. DocumentReader（文档读取）**

支持 PDF、Word、HTML、Markdown、PPT、EPUB 等格式：
```java
var reader = new TikaDocumentReader(new ClassPathResource("manual.pdf"));
List<Document> documents = reader.get();
```

**3. DocumentTransformer（文档切片）**
```java
var splitter = new TokenTextSplitter(
    800,    // 目标块大小
    400,    // 最小块大小
    100,    // 块重叠
    5,      // 最大块数
    true    // 保留元数据
);
List<Document> chunks = splitter.apply(documents);
```

**4. EmbeddingModel（向量化）**

将文本转为向量，存入向量数据库：
```java
embeddingModel.embed(chunks); // 自动向量化
vectorStore.add(chunks);       // 写入向量库
```

**5. VectorStore（向量数据库）**

Spring AI 2.0 统一了 20 余种向量数据库的 API，包括 PGVector、Milvus、Redis、Neo4j、Chroma、MongoDB、Elasticsearch、Qdrant、Pinecone、Weaviate 等：

```yaml
# PGVector 配置
spring:
  ai:
    vectorstore:
      pgvector:
        dimensions: 1536
        distance-type: COSINE_DISTANCE
```

支持类 SQL 风格的元数据过滤：
```java
vectorStore.similaritySearch(
    SearchRequest.query("如何配置连接池")
        .withTopK(5)
        .withSimilarityThreshold(0.7)
        .withFilterExpression("category == 'database' && year > 2024")
);
```

**6. 声明式 RAG（Advisor 方式）**

Spring AI 2.0 将 RAG 封装为 Advisor，一行配置自动完成检索+注入+生成：

```java
ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
            RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .topK(5)
                    .build())
                .build()
        )
        .build();

// 自动检索相关文档并注入上下文
String answer = client.prompt()
        .user("如何配置HikariCP连接池？")
        .call()
        .content();
```

**7. 进阶 RAG 技巧**
- **Pre-Retrieval**：查询改写、查询扩展
- **Post-Retrieval**：重排序（Reranking）、去重
- **混合检索**：向量检索 + 关键词检索组合

#### 实战任务
- 构建一个"企业文档问答系统"：上传 PDF 技术文档，基于文档内容回答问题
- 实现多文档源管理（不同文档分类存储、按分类检索）
- 对比不同切片策略对回答质量的影响

---

### 阶段 6：Tool Calling 工具调用（1~2 周）⭐ 核心重点

#### 学习目标
让 AI 模型能调用你的 Java 业务方法，这是构建 Agent 的基础。

#### 核心知识点

**1. @Tool 注解定义工具**

Spring AI 2.0 使用 `@Tool` 注解，自动生成 JSON Schema 传递给模型：

```java
@Component
public class OrderTools {

    @Tool(description = "根据订单号查询订单详情")
    public OrderInfo queryOrder(
            @ToolParam(description = "订单编号") String orderId) {
        return orderService.findById(orderId);
    }

    @Tool(description = "查询指定城市的实时天气")
    public String getWeather(
            @ToolParam(description = "城市名称") String city) {
        return weatherApi.fetch(city);
    }
}
```

**2. 注入工具到 ChatClient**
```java
String response = chatClient.prompt()
        .user("帮我查一下订单 ORD-20260827 的状态")
        .tools(orderTools)  // 注入工具实例
        .call()
        .content();
// 模型自动决定调用 queryOrder 工具，获取结果后生成自然语言回复
```

**3. ToolCallingAdvisor 机制**

Spring AI 2.0 将工具调用循环从模型内部提升为 Advisor 链中的一等公民。ToolCallingAdvisor 被 ChatClient 自动注册，不需要手动配置，它自动管理完整的工具执行生命周期——从提取工具定义、注入上下文，到循环执行工具调用直至模型不再请求工具：

```
用户请求 → Advisor链 → [ToolCallingAdvisor] → 模型
    ↑                                            ↓
    └──── 工具执行 ←── 模型返回工具调用请求 ←─────┘
```

**4. 流式工具调用**

支持"打字机输出 + 工具调用并行"，提升交互体验：
```java
Flux<String> stream = chatClient.prompt()
        .user("查一下北京天气，如果下雨就帮我叫车")
        .tools(weatherTools, rideTools)
        .stream()
        .content();
```

**5. 动态工具发现（省 Token 神器）**

当工具数量很多时，2.0 引入了 `ToolSearchToolCallingAdvisor`，实现渐进式工具披露，模型每次只收到一个"搜索工具"，按需动态发现其他工具，可节省 34%~64% 的 Token：

```yaml
spring:
  ai:
    chat:
      client:
        tool-search-advisor:
          enabled: true
```

#### 实战任务
- 构建一个"智能客服助手"：能查订单、查物流、查库存、发起退款
- 实现多工具组合调用（如"查天气→如果下雨→叫车"的链式调用）

---

### 阶段 7：Advisor 机制 & 可观测性（1 周）

#### 学习目标
掌握 Spring AI 的横切关注点处理机制和生产级监控能力。

#### 核心知识点

**1. Advisor 链（类似 Servlet Filter）**

Advisor 是 Spring AI 的核心扩展机制，每次请求都会经过 Advisor 链处理：

```java
ChatClient client = ChatClient.builder(chatModel)
        .defaultAdvisors(
            ChatMemoryAdvisor.builder(memory).build(),      // 对话记忆
            RetrievalAugmentationAdvisor.builder()...build(), // RAG
            new LoggingAdvisor(),                            // 日志
            new SafetyAdvisor()                              // 安全过滤
        )
        .build();
```

**2. 内置 Advisor 类型**

| Advisor | 功能 |
|---------|------|
| `ChatMemoryAdvisor` | 对话上下文管理 |
| `RetrievalAugmentationAdvisor` | RAG 检索注入 |
| `ToolCallingAdvisor` | 工具调用循环（自动注册） |
| `LoggingAdvisor` | 请求/响应日志 |
| `SafetyAdvisor` | 内容安全过滤 |
| `RateLimitingAdvisor` | 限流保护 |

**3. 自定义 Advisor**
```java
public class AuditAdvisor implements CallAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        log.info("AI请求: {}", request.userText());
        AdvisedResponse response = chain.nextAroundCall(request);
        log.info("AI响应: {}", response.response());
        auditService.record(request, response);
        return response;
    }
}
```

**4. 可观测性（Observability）**

Spring AI 原生集成 Spring Boot Actuator + Micrometer：
- **指标（Metrics）**：调用次数、延迟、Token 消耗
- **日志（Logging）**：请求/响应详情
- **追踪（Tracing）**：OTLP + Jaeger 全链路追踪

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  tracing:
    export:
      otlp:
        endpoint: http://jaeger:4318
```

**5. AI 模型评估（Evaluation）**

内置评估工具，量化生成质量：
- `RelevancyEvaluator`：回答相关性评估
- `FactCheckingEvaluator`：事实准确性校验
- 运行时自动评估 + 告警

#### 实战任务
- 为之前的聊天机器人添加审计日志 Advisor
- 接入 Prometheus + Grafana 监控 AI 调用指标
- 实现内容安全过滤 Advisor（敏感词检测）

---

### 阶段 8：MCP 协议 & Agent 智能体（2~3 周）

#### 学习目标
掌握 Model Context Protocol，构建可自主决策的 AI Agent。

#### 核心知识点

**1. MCP 协议概述**

MCP（Model Context Protocol）是连接模型与外部工具/数据源的标准协议，Spring AI 2.0 已原生集成：

- **传输方式**：STDIO（本地进程）和 Streamable HTTP（远程调用，2.0 中已成为默认传输协议）
- **角色**：MCP Client（消费工具）和 MCP Server（暴露工具）

**2. 构建 MCP Client**

连接外部 MCP Server（如 GitHub MCP Server、文件系统 MCP Server）：
```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers:
            github:
              command: npx
              args: ["-y", "@modelcontextprotocol/server-github"]
```

**3. 构建 MCP Server**

用 `@McpTool` 注解将你的 Java 服务暴露为标准化的 MCP 工具，供任何 MCP Client 调用：

```java
@McpTool
public String searchDocs(String query, McpSyncRequestContext ctx) {
    ctx.reportProgress("正在搜索：" + query, 0.3);
    return docSearchService.search(query);
}
```

**4. Agent Skills 模块化技能系统**

Spring AI 2.0 引入了 Agent Skills，将 Agent 的能力定义为可组合、可复用的模块：

内置 Skill 包括：
- `file`：文件读写
- `shell`：执行 Shell 命令
- `web-fetch`：抓取网页内容
- `task`：任务管理
- `auto-memory`：自动记忆持久化

Skill 是**跨模型可移植**的，写一次即可在 OpenAI、Anthropic、Google Gemini 等模型上运行。

**5. 多 Agent 编排**

了解 ReAct 等 Agent 模式，学习如何组合多个工具实现复杂任务的自动拆解和执行。Spring AI Alibaba 还提供了 Graph 编排能力，适合复杂多 Agent 场景。

#### 实战任务
- 构建一个 MCP Server，暴露你的业务 API（如订单查询、库存管理）
- 构建一个 MCP Client，连接外部工具（如 GitHub、文件系统）
- 实现一个能自主规划、调用工具、迭代优化的 Agent

---

### 学习资源推荐

| 类型 | 资源 | 说明 |
|------|------|------|
| **官方文档** | [Spring AI Reference](https://docs.spring.io/spring-ai/reference/) | 最权威的学习入口 |
| **官方示例** | [spring-ai-examples (GitHub)](https://github.com/spring-projects/spring-ai-examples) | 每个功能都有可运行代码 |
| **中文文档** | [Spring AI 中文文档](https://springdoc.cn/spring-ai/) | 翻译版官方文档 |
| **深度文章** | [Spring AI 2.0 深度解析与实战（腾讯云）](https://cloud.tencent.com/developer/article/2654321) | 架构层面深入分析 |
| **进阶框架** | [Spring AI Alibaba](https://sca.aliyun.com/docs/ai/) | Agent 全家桶 + Graph 编排 |

---

### 学习节奏建议

```
第1周：  阶段1 + 阶段2（环境搭建 + ChatClient）
第2~3周：阶段3（Prompt + 结构化输出）
第4周：  阶段4（对话记忆）
第5~7周：阶段5（RAG）⭐ 投入最多时间
第8~9周：阶段6（Tool Calling）⭐
第10周： 阶段7（Advisor + 可观测性）
第11~13周：阶段8（MCP + Agent）
```

**总计约 3 个月**，从零到掌握 Spring AI 全部核心功能。

---

### 最终实战项目建议

学完全部阶段后，建议做一个综合性项目把所有知识串联起来：

> **智能企业助手**：一个能读取公司内部文档（RAG）、查询业务系统（Tool Calling）、保持多轮对话记忆（Chat Memory）、通过 MCP 协议对外暴露能力（MCP Server）、具备完整监控和评估体系（Observability + Evaluation）的 AI Agent。

---

你可以从**阶段 1**开始，遇到任何具体问题随时问我。准备好了就告诉我，我可以帮你一步步搭建环境、写代码、调试问题。你想从哪个阶段开始？