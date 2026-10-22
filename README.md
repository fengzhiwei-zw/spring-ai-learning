# Spring AI Learning

一个使用 Java 和 Spring AI 学习 AI 应用开发的 Demo，逐步练习模型调用、Prompt、结构化输出、对话记忆、RAG、工具调用与 Advisor。

当前目标：在已有示例上完成一个能检索项目知识、调用工具、保持会话并处理失败的单 Agent。

> 本文以当前源码和 `pom.xml` 为准。`doc/` 中保留了早期学习笔记，其中的版本说明、API 示例和能力描述需要结合当前依赖核对。

## 当前进度

| 能力 | 当前实现 | 状态与边界 |
| --- | --- | --- |
| 模型调用 | `ChatClient` 同步调用、响应元数据、SSE | 已有接口，默认连接 DashScope 的 OpenAI 兼容服务 |
| Prompt | 角色设置、外部 `code-review.st` 模板 | 已有示例 |
| 结构化输出 | `Book`、`List<Book>`、`StockInfo`、`UserProfile` | ChatClient 示例显式启用 Schema 反馈重试，解析后执行业务校验 |
| 多模态 | 图片理解接口 | 已附带三色测试图片；可用 `AI_IMAGE_MODEL` 选择支持视觉输入的模型 |
| 短期记忆 | Redis 或本地内存 + `MessageWindowChatMemory` | `local` 模式使用内存；窗口上限为 20 条消息，不是 20 轮对话 |
| 文档入库 | Tika → 分块 → Embedding → Chroma | 校验资源、过滤空内容、稳定 ID 去重及同源版本清理；尚无对外导入入口 |
| RAG | 查询改写 + 向量检索 + 上下文增强 | 对话客户端启用；空检索允许继续回答；`topK=5`、阈值 `0.55` |
| 工具调用 | `WeatherTool` 的天气与时间工具 | 天气明确标为模拟数据；同步和流式工具循环均限制轮数与调用次数 |
| 长期记忆 | `ProductionVectorMemoryAdvisor`、`LongTermMemoryWriter` | 检索 Advisor 未启用；Writer 尚未注册为 Bean，也没有业务调用入口 |
| 日志 | `ProductionLoggingAdvisor` | 支持同步、流式完成、错误及取消；默认不记录正文，开启正文日志时做常见凭据脱敏 |
| Agent 闭环 | 模型与工具基础能力 | 已有工具调用预算，尚缺少明确任务场景、任务级结果及端到端验收 |

## 技术栈

以下是项目声明的版本，不代表最新版本：

| 组件 | 版本 / 用途 |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.0.8 |
| Spring AI | 2.0.0，通过 BOM 管理 |
| Maven Wrapper | 3.9.16 |
| Spring MVC | HTTP 接口与 SSE |
| DashScope | OpenAI 兼容的对话与 Embedding 服务 |
| Redis / Jedis | 会话记忆；Jedis 显式声明为 7.2.1 |
| Chroma | 知识库与长期记忆分别使用独立 collection |
| Apache Tika | 文档内容读取 |
| Actuator | 已引入依赖，监控导出和追踪仍需配置 |

## 项目结构

```text
src/main/java/com/feng/springailearning/
├── SpringAiDemoApplication.java
├── config/ChatClientConfig.java          # 模型、向量库、记忆、Advisor 和工具装配
├── controller/
│   ├── ChatController.java              # 基础对话、SSE、角色和参数示例
│   ├── Stage3Controller.java            # Prompt、结构化输出、多模态
│   └── Stage4Controller.java            # 直接调用 ChatModel，手动管理记忆
├── service/DocumentIngestionService.java # 文档读取、分块、入库
├── tool/WeatherTool.java                 # 模拟天气、系统时间
└── advisor/
    ├── ProductionLoggingAdvisor.java
    ├── ProductionVectorMemoryAdvisor.java
    └── LongTermMemoryWriter.java
src/main/resources/
├── application.yaml
├── application-local.yaml               # 无 Redis / Chroma 的临时学习模式
├── prompts/code-review.st
└── static/demo.png                       # 红、绿、蓝三色测试图片
src/test/java/com/feng/springailearning/
├── SpringAiDemoApplicationTests.java     # 隔离外部依赖的装配测试
└── advisor/、config/、controller/、service/ # 现有功能回归测试
doc/
├── ReadMe.md                            # 原始学习路线
└── step/                                # 阶段 1～3 学习笔记
```

## 本地运行

### 无中间件临时启动（local）

只想先测试聊天功能时，不必启动 Redis、Chroma 或其他数据库。使用 `local` Profile：

```bash
export DASHSCOPE_API_KEY='替换为你的 API Key'
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

IDEA 中打开 **Run → Edit Configurations → SpringAiDemoApplication**，将 **Active profiles** 设为 `local`，点击 Run。当前本机运行配置已经这样设置。

| 功能 | local 模式 |
| --- | --- |
| 普通聊天、SSE、角色与创意调用 | 保留，仍调用配置的真实模型 |
| 结构化输出、代码审查、图片理解 | 保留；图片仍需视觉模型支持 |
| 天气 / 时间工具 | 保留；天气仍是模拟数据 |
| 会话记忆 | 使用 `InMemoryChatMemoryRepository`，保留 20 条消息窗口 |
| Redis / Chroma / Embedding Bean | 不创建，也不连接这些服务 |
| RAG、向量记忆、文档入库 Service | 在此 Profile 下停用 |

内存会话会在应用重启后丢失，多个应用进程之间也不共享。`local` 免除存储中间件，但不是离线模型模式，仍需要可用的模型服务和 API Key。这里使用 Spring AI 提供的 [内存记忆仓库](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)。

验证启动：

```bash
curl http://localhost:8080/actuator/health
```

正常返回 HTTP 200 和 `"status":"UP"`。聊天及会话记忆的验证命令见下方“验证调用”。恢复 Redis + Chroma 模式时，移除 `local` Profile，并准备下面的依赖服务。

### 1. 准备依赖服务

本节及下面未指定 Profile 的启动命令使用默认完整模式。

- JDK 21，以及可下载 Maven 依赖的网络环境。
- 可用的 DashScope API Key，以及账号有权限调用的对话模型和 Embedding 模型。
- Redis：默认 `localhost:6379`。需要支持 RedisJSON 和搜索索引能力，例如 Redis Stack；仅有基础 Redis 命令的实例不足以支持当前记忆仓库。
- Chroma：默认 `http://localhost:8000`，需与当前客户端的 `/api/v2` 接口兼容。

Redis 和 Chroma 的安装可参考 [Spring AI 对话记忆文档](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) 与 [Chroma 集成文档](https://docs.spring.io/spring-ai/reference/api/vectordbs/chroma.html)。在线文档可能随版本更新，配置项应优先核对项目使用的 2.0.0 依赖。

### 2. 配置模型

```bash
export DASHSCOPE_API_KEY='替换为你的 API Key'
```

启动 IDE 中的应用时，也需要在 Run Configuration 中设置此环境变量。

模型和服务地址均从 `application.yaml` 注入，Java 配置负责装配：

| 配置 | 当前值 | 位置 |
| --- | --- | --- |
| 对话服务地址 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `application.yaml` |
| 对话模型 | `qwen3.8-27b` | `application.yaml` |
| Embedding 地址 | 同上 | 复用 `spring.ai.openai.base-url` |
| Embedding 模型 | `qwen3.7-text-embedding-flash` | `spring.ai.openai.embedding.options.model` |
| 向量维度 | `1024` | `spring.ai.openai.embedding.dimensions` |
| Redis 地址 | `localhost:6379` | `application.yaml` |
| Chroma 地址 | `http://localhost:8000` | `app.ai.chroma-base-url` |

模型名称是配置中的当前值，请先确认账号权限、模型可用性及 Embedding 维度支持情况。可通过 `AI_EMBEDDING_MODEL`、`AI_EMBEDDING_DIMENSIONS`、`CHROMA_BASE_URL`、`AI_IMAGE_MODEL` 覆盖对应参数。对话与 Embedding 请求超时均配置为 60 秒；这是单次外部请求超时，不是整个对话任务的总时限。

`spring.ai.model.embedding: none` 和 `spring.ai.vectorstore.type: none` 用来关闭相应自动配置；默认模式仍通过 `@Bean` 手动创建 Embedding 和两个 Chroma 向量库，因此需要这些服务。`local` 模式同时停用这些手工 Bean。

> 知识库 collection 为 `knowledge-base-text-embedding-v4-1024-v1`，记忆 collection 为 `chat-memory-text-embedding-v4-1024-v1`。名称中的模型标识与当前 Embedding 配置不一致。切换 Embedding 模型或维度时，建议新建 collection 并重新入库，避免混用不同模型生成的向量。

### 3. 启动应用

在项目根目录执行：

```bash
./mvnw spring-boot:run
```

Windows 使用 `mvnw.cmd spring-boot:run`。默认 HTTP 端口为 `8080`。

打包运行：

```bash
./mvnw -DskipTests package
java -jar target/spring-ai-learning-0.0.1-SNAPSHOT.jar
```

`-DskipTests` 只跳过测试执行；应用启动仍需要所选模式的配置和服务。打包后使用 local 模式可执行 `java -jar target/spring-ai-learning-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`。

### 4. 验证调用

下面是当前接口的调用示例。默认完整模式的对话客户端使用 RAG，空召回允许继续普通回答，但不会对向量服务故障自动降级。`local` 模式不装配 RAG，直接使用模型和内存会话。代码审查、结构化提取和图片接口使用独立客户端。

同步对话与会话记忆：

```bash
curl --get 'http://localhost:8080/chat' \
  --data-urlencode 'msg=我叫小明，正在学习 Spring AI' \
  --data-urlencode 'sessionId=demo-001'

curl --get 'http://localhost:8080/chat' \
  --data-urlencode 'msg=我叫什么名字？' \
  --data-urlencode 'sessionId=demo-001'
```

SSE 流式输出：

```bash
curl -N --get 'http://localhost:8080/chat/stream' \
  --data-urlencode 'msg=解释一下 Spring AI 的 Advisor' \
  --data-urlencode 'sessionId=demo-stream-001'
```

工具调用：

```bash
curl --get 'http://localhost:8080/chat' \
  --data-urlencode 'msg=请调用工具查询上海当前天气，再给出出行建议' \
  --data-urlencode 'sessionId=demo-tool-001'
```

所有天气结果均标记为模拟数据。应结合服务端的 `weather.tool.called` 日志确认工具确实执行，不能只根据回答文字判断。

## 接口速查

目前业务接口均为 `GET`，并接受可选的 `sessionId`。省略时使用当前 HTTP Session 的独立 ID，浏览器会自动携带 Cookie；curl 需要保存并回传 Cookie，或像上例一样显式传入会话 ID。显式 ID 保留原有语义，尚未引入身份认证或归属授权，不应当作多人服务的访问控制。文本参数拒绝空白，普通输入最长 16000 字符，代码最长 64000 字符，`count` 范围为 1～10。

| 路径 | 其他参数 | 返回 / 用途 |
| --- | --- | --- |
| `/chat` | `msg` | 同步文本 |
| `/chat/detail` | `msg` | `content`、模型和 Token 用量 |
| `/chat/stream` | `msg` | `text/event-stream` |
| `/chat/role` | `msg`、`role`（可选） | 请求级角色设定 |
| `/chat/creative` | `msg` | 高温度调用，旧路径 `/chat/chat/creative` 保留为别名 |
| `/api/v3/review` | `code` | 使用外部模板进行代码审查 |
| `/api/v3/book` | `topic` | `Book` 对象 |
| `/api/v3/books` | `topic`、`count` | `List<Book>` |
| `/api/v3/stock` | `text` | 提取 `ticker` 与 `price`，不查询实时行情 |
| `/api/v3/image` | 无 | 描述自带测试图片，使用 `AI_IMAGE_MODEL` 指定的模型 |
| `/api/v3/chat` | `msg` | 使用共享 `ChatClient` 的会话示例 |
| `/api/v4/model` | `msg` | 直接调用 `ChatModel`，返回模型文本 |
| `/api/v4/format` | `msg` | 手动使用 `BeanOutputConverter` 提取 `UserProfile` |

`/api/v4/*` 直接使用 `ChatModel`，没有经过默认 `ChatClient` 的 RAG、自定义日志和工具装配。它们是底层 API 学习示例。

## 当前调用链路

```text
ChatController / Stage3Controller
  → ChatClient（默认系统提示词、模型选项、天气和时间工具）
  → MessageChatMemoryAdvisor：读取 Redis 会话，完成后写回
  → RetrievalAugmentationAdvisor：查询改写 → Embedding → Chroma 知识库检索
  → ToolCallingAdvisor：模型请求工具 → 执行工具 → 回传结果 → 继续调用模型
  → 模型服务 → 最终回答

+50 的 ProductionLoggingAdvisor 位于上述链路外层，记录客户端请求耗时；
代码审查、结构化提取和图片理解使用独立的 structuredChatClient。
```

在本项目使用的 Spring AI 2.0.0 中，`ChatClient` 会自动注册 `ToolCallingAdvisor`。对话客户端使用其子类 `LimitedToolCallingAdvisor`，沿用框架工具循环并增加上限：默认最多 5 轮工具执行、累计 10 次调用，在超限批次执行前拒绝；同步和流式均生效。框架机制可参考 [Tool Calling 文档](https://docs.spring.io/spring-ai/reference/api/tools.html)。日志中的 Token 来自最终响应元数据，不是查询改写和所有工具轮次的累计费用。

文档入库链路为：`Resource → TikaDocumentReader → TokenTextSplitter → 元数据 → knowledgeVectorStore.add()`。当前分块目标为 800 Token，元数据包含 `filename`、`ingest_time`、`source_id`、`ingestion_version`。相同来源和内容使用稳定片段 ID；更新时先写新版本，再清理带有相同 `source_id` 的旧版本。旧代码写入的历史数据没有来源标记，不会被自动删除。可以通过注入 `DocumentIngestionService` 调用 `ingestFromClasspath()` 或 `ingestFromFileSystem()`，但仓库尚未提供直接可用的导入接口。

长期记忆与知识库使用不同 collection。将来启用 `ProductionVectorMemoryAdvisor` 时，还需要传入 `app.memory.tenant-id`、`app.memory.owner-id`，并实现事实提取及 Writer 调用；只取消 Advisor 的注释并不能完成长期记忆闭环。

## 下一步：先完成一个完整 Agent

建议选择 **“Spring AI 项目学习助手”**：用户提出项目问题，Agent 按需检索学习文档、查询项目事实，再返回带来源的解释和可执行的下一步。已有代码审查模板、RAG 和会话记忆都能复用。

第一版范围保持在单 Agent、只读工具和短期记忆。天气工具保留为独立的机制演示；长期记忆、MCP、多 Agent 和前端可以放到后续迭代。

### P0：跑通一条可验收的任务链路

1. **建立独立的 Agent 入口。** 新增 `POST /api/agent/chat`，由 `AgentService` 组织请求。请求包含 `message`、`conversationId`，响应包含 `answer`、`sources`、`toolExecutions`、`status`、`requestId`。这些是建议新增的设计，当前尚未实现。
2. **让检索按需执行。** 将知识检索封装为 `searchKnowledge(query)` 工具，返回片段及来源；保留独立 RAG 接口做对照实验。普通聊天、时间查询和结构化提取不必都先经过知识检索。
3. **提供一个真实的项目工具。** 例如 `getProjectInfo()`，返回经过校验的项目版本及模块信息；结合知识检索，可以完成“读取项目版本，再查找匹配的说明”这类多步骤任务。工具只暴露明确允许读取的数据。
4. **补齐入库入口和来源信息。** 先提供一次性导入命令或受控接口，导入 `doc/` 和少量项目说明；保留文档 ID、文件名、片段 ID，按内容哈希去重。最终引用只能来自实际检索结果，不能由模型编造。
5. **完善任务预算。** 已有最多 5 轮工具执行、累计 10 次工具调用及单次模型请求超时；后续 Agent 还需要任务总时限、总 Token 预算和取消传播，不能只写在 Prompt 中。
6. **处理失败和信息不足。** 缺参数时追问；检索不到时说明缺少依据；工具超时或模型失败时返回统一错误。重试限定次数，结束后可区分成功、需要补充信息和失败。

### P1：后续扩展时保留的边界

现有功能的缺陷与修复详见 [现有功能修复说明](doc/现有功能修复说明.md)。后续构建 Agent 时，再考虑 POST JSON 入口、身份认证、会话管理、跨轮 Token 汇总及任务级结果。长期记忆类名中的 `Production` 不代表已经完成生产环境验证。

### 第一版验收清单

- [ ] “项目使用哪个 Java / Spring AI 版本？”：调用真实项目工具，结果与 `pom.xml` 一致。
- [ ] “结合本项目版本说明 Advisor 如何使用”：执行项目查询和知识检索，再生成可核对的来源引用。
- [ ] 同一会话能理解“刚才提到的版本”，不同会话之间不共享上下文。
- [ ] 知识库没有答案时明确说明；不伪造文档、版本或执行成功记录。
- [ ] 工具返回失败时能给出明确状态；在隔离测试环境中模拟超时、重复调用，执行能按预算结束。
- [ ] 流式调用可正常结束，异常能传递给客户端，取消后停止后续调用。
- [ ] 用固定模型响应或工具替身做可重复测试，再用真实模型完成一次端到端演示。

完成以上用例，就具备了一个目标明确、能使用工具、能保留上下文且行为可检查的第一版 Agent。

## 测试与学习资料

```bash
./mvnw test
```

当前有 33 项可重复测试，覆盖会话隔离、输入与输出校验、Prompt 顺序、失败记忆、RAG 空检索、真实 Java 工具执行、工具预算、SSE 取消、日志脱敏、Redis 配置及文档入库。新增 `LocalProfileTests` 不替换中间件 Bean，验证 local 的真实装配、内存窗口和健康接口，确认没有创建 Redis、Chroma、Embedding、RAG 和入库 Service。测试不需要真实 API Key 或正在运行的依赖服务。

本机在 IDEA 的 local 模式下已完成真实启动验证：健康接口返回 UP，连续两轮模型调用返回 200，第二轮能够回忆第一轮提供的名字。Redis、Chroma 完整模式仍需另外准备服务。

- [现有功能修复说明](doc/现有功能修复说明.md)
- [原始学习路线](doc/ReadMe.md)
- [阶段 1：环境搭建](doc/step/阶段1.md)
- [阶段 2：ChatClient](doc/step/阶段2.md)
- [阶段 3：Prompt 与结构化输出](doc/step/阶段3.md)
- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
