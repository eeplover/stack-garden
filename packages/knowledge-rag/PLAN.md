# 前端转全栈 AI 工程师：产品化 RAG 6 周详细计划（Java 后端版）

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 前端 | Next.js 16 + TypeScript | App Router（本仓库位于 monorepo `pnpm-workspace`，`frontend` 为独立 workspace 包） |
| AI UI | Vercel AI SDK 6（`ai`）+ `@ai-sdk/react` | `useChat` + `DefaultChatTransport`，服务端用 `createUIMessageStream` 适配 Spring SSE |
| 后端 | Java 21 + Spring Boot 3.3 | REST + SSE |
| AI 框架 | Spring AI 1.0 | ChatClient、EmbeddingModel、VectorStore |
| 向量数据库 | PostgreSQL 16 + pgvector | Postgres 内做向量搜索 |
| ORM | Spring Data JPA | Entity 管理、Repository 模式 |
| 文档解析 | Apache Tika（Spring AI 集成）| 解析 PDF、Word、txt |
| 构建工具 | Maven | 后端依赖管理 |
| 本地环境 | Docker Compose | PostgreSQL + Redis |
| AI 模型 | Ollama（本地）| 对话用 qwen2:7b，Embedding 用 nomic-embed-text |

## 架构图

```
Browser (Next.js :3000)
  ↕ HTTP + SSE
Next.js API Routes          ← 薄代理层，转发到 Spring Boot
  ↕
Spring Boot (:8080)
  ├── Controller             ← REST API + SSE 流
  ├── Service (RAG 逻辑)
  ├── Repository             ← Spring Data JPA
  └── Spring AI              ← ChatClient / EmbeddingModel / PgVectorStore
  ↕
PostgreSQL + pgvector (:5432)
  ├── documents
  ├── document_chunks
  ├── vector_store           ← Spring AI 自动建表
  ├── conversations
  └── messages
  ↕
Ollama (local, :11434)
  ├── qwen2:7b             ← 对话模型
  └── nomic-embed-text     ← Embedding 模型（768 维）
```

---

## 准备工作（开始前确认）

```bash
java -version       # 需要 21
mvn -version        # 需要 3.9+
node --version      # 需要 18+
docker --version    # 需要已启动 Docker Desktop
ollama --version    # 需要已安装 Ollama（https://ollama.com）
```

安装 Ollama 模型（首次，约 5GB，下载后可离线使用）：

```bash
ollama pull nomic-embed-text   # Embedding 模型，768 维，约 274MB
ollama pull qwen2:7b           # 对话模型，约 4.4GB（也可用 llama3:8b）
ollama serve                   # 启动服务，监听 :11434，保持终端开着
```

---

## 第 1 周：AI 应用基础 + 最小聊天界面

**目标：** 跑通 Browser → Next.js → Spring Boot → Ollama 完整链路，含流式响应。

**本周学习：** LLM API 调用、SSE 流式传输、System Prompt、Token、Context Window

---

### Day 1：环境搭建

**1. 安装 Java 工具链**

```bash
# macOS
brew install openjdk@21 maven

# Windows：下载 Eclipse Temurin 21 JDK，Maven 官网下载解压后加 PATH
java -version    # 验证：显示 21.x
mvn -version     # 验证：显示 3.9.x
```

**2. 初始化项目结构**

```bash
mkdir knowledge-rag && cd knowledge-rag
mkdir frontend    # Next.js 放这里
# backend 稍后用 Spring Initializr 生成
```

**3. 创建 `docker-compose.yml`（根目录）**

```yaml
version: '3.8'
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: knowledge_rag
      POSTGRES_USER: dev
      POSTGRES_PASSWORD: devpassword
    ports:
      - "5433:5432"   # 宿主机用 5433，避开 macOS 上 Homebrew/Postgres.app 占用的本地 5432
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dev -d knowledge_rag"]
      interval: 5s
      retries: 5
  redis:
    image: redis:alpine
    ports:
      - "6379:6379"
volumes:
  pgdata:
```

```bash
docker-compose up -d
docker-compose ps    # postgres 和 redis 均为 Up (healthy)

# 验证 1：dev 角色已创建（POSTGRES_USER 只在卷为空时才生效，
# 如果你之前用同名卷跑过其它 Postgres 镜像，需先 down -v 再 up -d）
docker-compose exec postgres psql -U dev -d knowledge_rag -c '\conninfo'
# 期望：You are connected to database "knowledge_rag" as user "dev" ...

# 验证 2：从宿主机用 5433 端口能连进去（确认没被本地 Postgres 抢路由）
psql 'postgresql://dev:devpassword@localhost:5433/knowledge_rag' -c 'select 1;'
# 若报 "role \"dev\" does not exist"：你大概率连到了本地 Postgres，
# 检查 lsof -nP -iTCP:5432,5433 -sTCP:LISTEN 确认绑定情况。
```

**4. 创建 Spring Boot 项目**

访问 https://start.spring.io，配置：

| 项 | 值 |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | 3.3.6 |
| Group | com.example |
| Artifact | knowledge-rag |
| Java | 21 |
| Dependencies | Spring Web、Spring Data JPA、PostgreSQL Driver、Lombok |

下载解压到 `knowledge-rag/backend/`，用 IntelliJ IDEA 打开 `backend/` 目录。

**5. 添加 Spring AI 依赖（`pom.xml`）**

在 `<properties>` 加：
```xml
<spring-ai.version>1.0.0</spring-ai.version>
```

在 `<dependencyManagement>` 加 BOM：
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

在 `<dependencies>` 加：
```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-tika-document-reader</artifactId>
</dependency>
```

**6. 配置 `src/main/resources/application.yml`**

将 `application.properties` 重命名为 `application.yml`：

📄 `backend/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: knowledge-rag
  datasource:
    url: jdbc:postgresql://localhost:5433/knowledge_rag
    username: dev
    password: devpassword
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
  ai:
    ollama:
      base-url: http://localhost:11434
      init:
        pull-model-strategy: when_missing   # 启动时若本地无此模型则自动拉取
      chat:
        options:
          model: qwen2:7b
          temperature: 0.7
      embedding:
        options:
          model: nomic-embed-text
    vectorstore:
      pgvector:
        initialize-schema: true
        dimensions: 768    # nomic-embed-text 输出 768 维向量

server:
  port: 8080
```

**7. 创建 Next.js 前端**

```bash
cd packages/knowledge-rag/frontend   # 或你的 knowledge-rag/frontend 路径
npx create-next-app@latest . --typescript --tailwind --app --src-dir --no-git
pnpm add ai @ai-sdk/react          # AI SDK 6：React 钩子已迁至 @ai-sdk/react，不再使用 ai/react
```

如在仓库根目录（stack-garden）统一管理依赖，需把 `packages/knowledge-rag/frontend` 写入根目录 `pnpm-workspace.yaml`，再在仓库根执行 `pnpm install`。

**验收：**

```bash
# 确保 ollama serve 在运行（另开一个终端执行 ollama serve）

# 后端：在 backend/ 目录
mvn spring-boot:run
# 看到 "Started KnowledgeRagApplication" 表示成功

# 前端：pnpm run dev（在 frontend/）
# 浏览器访问 http://localhost:3000
```

---

### Day 2：后端实现 Chat API

**学习重点：** Spring Boot Controller、Spring AI ChatClient、CORS 配置

📄 `backend/src/main/java/com/example/knowledge_rag/dto/ChatRequest.java`

```java
package com.example.knowledge_rag.dto;

public record ChatRequest(String message, String conversationId) {}
```

📄 `backend/src/main/java/com/example/knowledge_rag/controller/ChatController.java`

```java
package com.example.knowledge_rag.controller;

import com.example.knowledge_rag.dto.ChatRequest;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:3000")
public class ChatController {

    private final ChatClient chatClient;

    /**
     * Spring AI 的 ChatClient 是访问所有 LLM 的统一入口。
     * 注入 ChatClient.Builder（Spring AI 自动装配），在构造函数里一次性完成配置。
     *
     * 为什么注入 Builder 而不是直接注入 ChatClient？
     * → ChatClient 是不可变对象，Builder 让你在创建时设置所有默认行为。
     * → 不同 Controller 需要不同 System Prompt 时，各自 build 自己的实例即可。
     */
    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder
            // System Prompt 定义 AI 的"角色"和行为规则，用户不可见、不可修改。
            // 每次对话都会携带这段内容，消耗 token，所以不要写得过长。
            .defaultSystem("你是一个有帮助的 AI 助手，用中文回复。")
            // Advisor 是 Spring AI 的拦截器机制，可在请求发出前后插入逻辑。
            // MessageChatMemoryAdvisor：每次调用时自动把历史消息注入 prompt，实现多轮对话。
            // MessageWindowChatMemory：内存中的滑动窗口历史，重启后丢失。生产可换 Redis/DB 实现。
            // Spring AI 1.0 要求 advisor 构建时必须指定非空的默认会话 ID。
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build())
                .conversationId(ChatMemory.DEFAULT_CONVERSATION_ID)
                .build())
            .build();
    }

    // 非流式接口，调试用。等 LLM 生成完整响应后一次性返回。
    @PostMapping
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        ChatResponse chatResponse = chatClient.prompt()
            .user(request.message())
            .advisors(a -> a.param(
                ChatMemory.CONVERSATION_ID,
                request.conversationId() != null ? request.conversationId() : ChatMemory.DEFAULT_CONVERSATION_ID
            ))
            .call()
            .chatResponse();

        // Token 用量监控：本地 Ollama 不计费，但养成习惯，上云后直接有数据。
        Usage usage = chatResponse.getMetadata().getUsage();
        if (usage != null) {
            log.info("Tokens used: prompt={}, completion={}, total={}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }

        String content = chatResponse.getResult().getOutput().getText();
        return Map.of("content", content);
    }

    /**
     * 流式 SSE 接口，面向用户的实际接口。
     *
     * produces = MediaType.TEXT_EVENT_STREAM_VALUE：
     * → 声明这个接口返回 Server-Sent Events，不是普通 JSON。
     * → 客户端保持连接，持续接收 "data: <内容>\n\n" 格式的事件。
     *
     * 返回值 Flux<String>：
     * → Flux 是 Reactor 的"异步数据流"类型，代表 0..N 个异步元素的序列。
     * → Spring MVC（无需切换到 WebFlux）已支持返回 Flux。
     * → Spring 自动把每个 String 元素写成一条 SSE 事件，无需手动拼 "data: xxx\n\n"。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        return chatClient.prompt()
            .user(request.message())
            .advisors(a -> a.param(
                // 告诉 MessageChatMemoryAdvisor 用哪个 ID 读写历史记录。
                // 相同 conversationId 的请求共享历史，不同 ID 互相独立。
                // ChatMemory.CONVERSATION_ID：Spring AI 约定的 advisor 参数名，勿手写魔法字符串。
                ChatMemory.CONVERSATION_ID,
                request.conversationId() != null ? request.conversationId() : ChatMemory.DEFAULT_CONVERSATION_ID
            ))
            // .stream() vs .call()：
            // .stream()：LLM 每生成一个 token 立即发送，用户看到"打字机"效果。
            // .call()：等全部生成完再返回，用户看到"加载中"然后一次性出现。
            // 对于用户交互，永远用 .stream()；它不仅体验更好，感知延迟也更低。
            .stream()
            // .content()：只取文本内容，过滤掉 usage、model 等元数据。
            // 每次 emit 的是 1-4 个字符（一个 token），前端拼接后即为完整回复。
            .content();
    }
}
```

验证：
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你好"}'
# 应返回 {"content": "..."}
```

---

### Day 3：前端实现流式聊天 UI

**学习重点：** Next.js App Router API Route、`@ai-sdk/react` 的 `useChat`、`DefaultChatTransport`、服务端 `createUIMessageStream` 将 Spring SSE 转为 UI Message Stream；**解析上游 SSE 时须兼容 `data:正文`（冒号后无空格），勿误写成仅匹配 `data: `**

📄 `frontend/src/app/api/chat/route.ts`

```typescript
import { createUIMessageStream, createUIMessageStreamResponse } from 'ai';

/**
 * 这个 Next.js API Route 是"SSE → UI Message Stream" 适配层。
 *
 * Spring Boot（Spring AI）输出的是**纯文本 token** 的 SSE，常见两种写法：
 *   data:你\n\n      ← Spring MVC 对 Flux 常无空格
 *   data: 你\n\n     ← 部分示例带空格
 *
 * AI SDK 6 里 DefaultChatTransport 期望响应体是 **JSON 事件的 SSE**（每条 data: 一行 JSON），
 * 对应内部的 UIMessageChunk（text-start / text-delta / …）。
 *
 * 旧版示例里的 createDataStreamResponse / writeTextDelta 在 ai@6 中已移除；
 * 请使用 createUIMessageStream + createUIMessageStreamResponse，用 writer.write() 发出 chunk。
 *
 * Spring MVC 写出 Flux SSE 时，常见 **`data:token`**（`data:` 后无空格）。代理层若只判断 **`data: `**（带空格），会导致所有 **text-delta** 丢失，界面只见空气泡。
 * （若你只要裸文本流、不用 useChat 的 JSON 协议，可用 createTextStreamResponse + TextStreamChatTransport。）
 */
function ssePayloadFromLine(line: string): string | null {
  const trimmed = line.trimEnd();
  if (!trimmed.startsWith('data:')) return null;
  let value = trimmed.slice('data:'.length);
  if (value.startsWith(' ')) value = value.slice(1);
  value = value.trim();
  if (!value || value === '[DONE]') return null;
  return value;
}

function textFromUiMessage(message: {
  parts?: Array<{ type: string; text?: string }>;
  content?: string;
}) {
  if (message && typeof message.content === 'string') return message.content;
  if (!message?.parts?.length) return '';
  return message.parts
    .filter((p): p is { type: 'text'; text: string } => p.type === 'text')
    .map((p) => p.text)
    .join('');
}

export async function POST(req: Request) {
  const { messages } = await req.json();

  // useChat 把完整消息历史传来，我们只取最后一条发给 Spring Boot。
  // 对话历史由后端 MessageChatMemoryAdvisor 管理，前端无需传递全部历史。
  const lastMessage = messages[messages.length - 1];
  const userText = textFromUiMessage(lastMessage);

  const stream = createUIMessageStream({
    execute: async ({ writer }) => {
      const textId = 'assistant-text';

      writer.write({ type: 'start' });
      writer.write({ type: 'start-step' });
      writer.write({ type: 'text-start', id: textId });

      const response = await fetch('http://localhost:8080/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: userText }),
      });

      if (!response.ok || !response.body) {
        writer.write({
          type: 'error',
          errorText: `Spring upstream failed: ${response.status}`,
        });
        writer.write({ type: 'text-end', id: textId });
        writer.write({ type: 'finish-step' });
        writer.write({ type: 'finish', finishReason: 'error' });
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let sseBuffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        sseBuffer += decoder.decode(value, { stream: true });

        let newlineIndex: number;
        while ((newlineIndex = sseBuffer.indexOf('\n')) >= 0) {
          const line = sseBuffer.slice(0, newlineIndex).trimEnd();
          sseBuffer = sseBuffer.slice(newlineIndex + 1);

          const token = ssePayloadFromLine(line);
          if (token) {
            writer.write({ type: 'text-delta', id: textId, delta: token });
          }
        }
      }

      sseBuffer += decoder.decode();
      if (sseBuffer.trim()) {
        for (const line of sseBuffer.split('\n')) {
          const trimmed = line.trimEnd();
          const token = ssePayloadFromLine(trimmed);
          if (token) {
            writer.write({ type: 'text-delta', id: textId, delta: token });
          }
        }
      }

      writer.write({ type: 'text-end', id: textId });
      writer.write({ type: 'finish-step' });
      writer.write({ type: 'finish', finishReason: 'stop' });
    },
  });

  return createUIMessageStreamResponse({ stream });
}
```

📄 `frontend/src/app/page.tsx`（聊天 UI 在首页 `/`，API Route 路径 → `/api/chat`）

```typescript
'use client';

import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport, type UIMessage } from 'ai';
import { type FormEvent, useState } from 'react';

function textFromMessage(message: UIMessage) {
  return message.parts
    .filter((p): p is { type: 'text'; text: string } => p.type === 'text')
    .map((p) => p.text)
    .join('');
}

export default function ChatPage() {
  const [input, setInput] = useState('');
  const { messages, sendMessage, status } = useChat({
    transport: new DefaultChatTransport({ api: '/api/chat' }),
  });

  const busy = status === 'submitted' || status === 'streaming';

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    await sendMessage({ text });
  }

  return (
    <main className="flex flex-col max-w-2xl mx-auto h-screen p-4">
      <h1 className="text-2xl font-bold mb-4">知识库助手</h1>

      <div className="flex-1 overflow-y-auto space-y-3 mb-4">
        {messages.map((m) => (
          <div
            key={m.id}
            className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            <div
              className={`max-w-lg p-3 rounded-lg ${
                m.role === 'user' ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-800'
              }`}
            >
              {textFromMessage(m)}
            </div>
          </div>
        ))}
        {busy && (
          <div className="flex justify-start">
            <div className="bg-gray-100 p-3 rounded-lg text-gray-400">思考中...</div>
          </div>
        )}
      </div>

      <form onSubmit={onSubmit} className="flex gap-2">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="输入你的问题..."
          className="flex-1 border border-gray-300 rounded-lg p-3 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          disabled={busy || !input.trim()}
          className="bg-blue-500 text-white px-6 py-3 rounded-lg disabled:opacity-50"
        >
          发送
        </button>
      </form>
    </main>
  );
}
```

**验收：**
- [ ] 浏览器打开 `http://localhost:3000`（聊天 UI）；Network 中 **`/api/chat`** 的 SSE 在 `text-start` 与 `text-end` 之间应出现多条 **`text-delta`**
- [ ] 输入消息，AI 回复逐字流式出现
- [ ] 控制台无 CORS 或格式错误

---

### Day 4-5：深化理解——三个实验

**实验 1：System Prompt 的作用**

在 ChatController 里替换 system prompt，观察输出变化：
- `"每次回复不超过 30 字"`
- `"你是一个专业 Java 工程师，只用代码回答"`

**实验 2：Structured Output（结构化输出）**

```java
record ArticleSummary(String title, List<String> keyPoints, String sentiment) {}

@PostMapping("/summarize")
public ArticleSummary summarize(@RequestBody ChatRequest request) {
    return chatClient.prompt()
        .user("总结以下内容并提取关键点：" + request.message())
        .call()
        .entity(ArticleSummary.class);
}
```

**实验 3：Token 成本感知**

```java
ChatResponse response = chatClient.prompt()
    .user(request.message())
    .call()
    .chatResponse();

Usage usage = response.getMetadata().getUsage();
log.info("Tokens used: prompt={}, completion={}, total={}",
    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
```

---

### Day 6-7：代码整理

- 包结构：`controller/`、`service/`、`dto/`、`config/`
- 添加全局异常处理（`@RestControllerAdvice`）返回统一 JSON 错误格式
- 前端加错误状态展示
- 添加 `/api/health` 接口

---

## 第 2 周：全栈后端基础 + 文档上传

**目标：** 用户可上传 PDF/Word/TXT，系统保存文件、解析文本、展示处理状态。

**本周学习：** Spring Data JPA、Entity 设计、Repository 模式、@Async 异步处理、Apache Tika

---

### Day 1-2：数据库设计和 JPA 入门

**1. 创建 Document 实体**

📄 `backend/src/main/java/com/example/knowledge_rag/entity/Document.java`

```java
package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity           // 声明这是 JPA 管理的实体，对应数据库里的一张表
@Table(name = "documents") // 显式指定表名，不写则默认用类名小写。推荐显式写明。
@Data             // Lombok：自动生成 getter / setter / toString / equals / hashCode
@NoArgsConstructor // Lombok：JPA 强制要求实体必须有无参构造函数，Lombok 帮你生成
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    // 为什么用 UUID 而不是自增 Long？
    // → 分布式场景下不会冲突（多实例同时插入）
    // → 不暴露业务信息（用户无法通过 ID 猜出总数据量）
    // → 可以在应用层提前生成 ID，无需等待数据库自增
    private String id;

    @Column(nullable = false)
    private String filename;      // 服务器存储的实际文件名：UUID_原文件名，防止重名冲突

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;  // 用户上传时的原始文件名，只用于展示

    @Column(name = "file_type")
    private String fileType;

    @Column(nullable = false)
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    // 必须用 EnumType.STRING，不要用默认的 EnumType.ORDINAL（存数字 0、1、2...）
    // 原因：ORDINAL 依赖枚举的声明顺序，一旦中间插入新枚举值，
    //       历史数据的数字含义就错位了，导致静默数据污染，极难排查。
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @CreationTimestamp
    // Hibernate 注解：INSERT 时自动填入当前时间，不需要手动 setCreatedAt()
    @Column(name = "created_at", updatable = false)
    // updatable = false：生成 UPDATE SQL 时忽略这个字段，防止创建时间被意外改写
    private LocalDateTime createdAt;

    @UpdateTimestamp
    // Hibernate 注解：每次 UPDATE 时自动更新为当前时间
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

📄 `backend/src/main/java/com/example/knowledge_rag/entity/DocumentStatus.java`

```java
package com.example.knowledge_rag.entity;

public enum DocumentStatus { PENDING, PROCESSING, COMPLETED, FAILED }
```

**2. 创建 Repository**

📄 `backend/src/main/java/com/example/knowledge_rag/repository/DocumentRepository.java`

```java
package com.example.knowledge_rag.repository;

import com.example.knowledge_rag.entity.Document;
import com.example.knowledge_rag.entity.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findAllByOrderByCreatedAtDesc();
    List<Document> findByStatus(DocumentStatus status);
}
```

**3. 创建 DTO（不要直接返回 Entity）**

📄 `backend/src/main/java/com/example/knowledge_rag/dto/DocumentResponse.java`

```java
package com.example.knowledge_rag.dto;

import com.example.knowledge_rag.entity.Document;

public record DocumentResponse(
    String id,
    String originalFilename,
    String status,
    Long fileSize,
    Integer chunkCount,
    String createdAt
) {
    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(
            doc.getId(),
            doc.getOriginalFilename(),
            doc.getStatus().name(),
            doc.getFileSize(),
            doc.getChunkCount(),
            doc.getCreatedAt().toString()
        );
    }
}
```

---

### Day 3-4：文件上传 API

**`application.yml` 补充（追加到现有配置末尾）：**

📄 `backend/src/main/resources/application.yml`（追加）

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

app:
  upload:
    dir: ${user.home}/knowledge-rag-uploads
```

**FileStorageService：**

📄 `backend/src/main/java/com/example/knowledge_rag/service/FileStorageService.java`

```java
package com.example.knowledge_rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {

    private final Path uploadDir;

    public FileStorageService(@Value("${app.upload.dir}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir);
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create upload directory", e);
        }
    }

    public String store(MultipartFile file) {
        String filename = UUID.randomUUID() + "_" +
            StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        try {
            Files.copy(file.getInputStream(), uploadDir.resolve(filename));
            return filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    public Path load(String filename) {
        return uploadDir.resolve(filename);
    }
}
```

**DocumentService：**

📄 `backend/src/main/java/com/example/knowledge_rag/service/DocumentService.java`

```java
package com.example.knowledge_rag.service;

import com.example.knowledge_rag.dto.DocumentResponse;
import com.example.knowledge_rag.entity.Document;
import com.example.knowledge_rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final DocumentProcessingService processingService;

    private static final Set<String> ALLOWED_TYPES = Set.of(
        "application/pdf", "text/plain", "text/markdown",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    public Document upload(MultipartFile file) {
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("Unsupported file type: " + file.getContentType());
        }
        String filename = fileStorageService.store(file);
        var doc = new Document();
        doc.setFilename(filename);
        doc.setOriginalFilename(file.getOriginalFilename());
        doc.setFileType(file.getContentType());
        doc.setFileSize(file.getSize());
        var saved = documentRepository.save(doc);
        processingService.processAsync(saved.getId());
        return saved;
    }
}
```

---

### Day 5：文档解析（Apache Tika via Spring AI）

**学习重点：** @Async 异步执行、文档状态机、Apache Tika 解析原理

在主类加 `@EnableAsync`：

📄 `backend/src/main/java/com/example/knowledge_rag/KnowledgeRagApplication.java`

```java
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
```

📄 `backend/src/main/java/com/example/knowledge_rag/service/DocumentProcessingService.java`

```java
package com.example.knowledge_rag.service;

import com.example.knowledge_rag.entity.Document;
import com.example.knowledge_rag.entity.DocumentChunk;
import com.example.knowledge_rag.entity.DocumentStatus;
import com.example.knowledge_rag.repository.DocumentChunkRepository;
import com.example.knowledge_rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final FileStorageService fileStorageService;
    private final VectorStore vectorStore;

    /**
     * @Async 的作用：
     * Spring 把这个方法放到独立线程池执行，调用方（Controller）立即返回，
     * HTTP 请求不会阻塞等待文档处理完成。
     *
     * 前提：主类或 @Configuration 类上必须加 @EnableAsync，否则 @Async 无效（退化为同步调用）。
     *
     * 关键陷阱：@Async 必须跨 Bean 调用才生效。
     * ✅ Controller → DocumentProcessingService.processAsync()（有效）
     * ❌ DocumentProcessingService 内部方法 A 调 B @Async（无效，Spring AOP 代理不拦截自调用）
     */
    @Async
    public void processAsync(String documentId) {
        var document = documentRepository.findById(documentId).orElseThrow();

        // 状态机：每次状态变更都要持久化，前端轮询时才能看到最新状态。
        // 流转路径：PENDING → PROCESSING → COMPLETED（成功）
        //                               ↘ FAILED（异常）
        document.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(document);

        try {
            Path filePath = fileStorageService.load(document.getFilename());

            /**
             * TikaDocumentReader 是 Spring AI 集成的 Apache Tika 包装器。
             * Tika 能自动检测文件类型（不依赖后缀名）并提取纯文本：
             * - PDF → 提取所有页面文字
             * - DOCX → 提取 Word 正文
             * - TXT / MD → 直接读取
             *
             * 返回 List<Document>，每个 Document 包含：
             * - content：提取出的纯文本
             * - metadata：文件元数据（标题、作者、页数等）
             */
            var reader = new TikaDocumentReader(new FileSystemResource(filePath));
            List<org.springframework.ai.document.Document> parsed = reader.get();

            chunkAndEmbed(documentId, parsed);
            document.setStatus(DocumentStatus.COMPLETED);

        } catch (Exception e) {
            // @Async 方法里的异常不会传播给调用方，必须在这里自行处理。
            // 如果不捕获，线程会静默退出，文档会永远卡在 PROCESSING 状态。
            log.error("Failed to process document {}", documentId, e);
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(e.getMessage());
        }

        documentRepository.save(document);
    }
}
```

---

### Day 6-7：前端文档管理页

📄 `frontend/src/app/documents/page.tsx`，实现：
- 拖拽或点击上传区域
- 文档列表，含状态徽章（待处理 / 处理中 / 已完成 / 失败）
- 每 3 秒轮询更新状态（`setInterval` + fetch）
- 文件大小格式化显示

**验收：**
- [ ] 上传 PDF 成功，数据库有记录
- [ ] 状态从 PENDING 流转到 COMPLETED
- [ ] 前端文档列表实时更新状态

---

## 第 3 周：手写最小 RAG

**目标：** 完整实现：文档 → chunk → embedding → pgvector → 检索 → 含引用的流式回答。

**本周学习：** Text Chunking、Embedding 原理、pgvector 相似性搜索、RAG Prompt 设计

---

### Day 1：理解 Embedding（先做实验再编码）

以下调试端点加在 `ChatController` 或新建 `DebugController` 里均可：

📄 `backend/src/main/java/com/example/knowledge_rag/controller/DebugController.java`（新建）

```java
package com.example.knowledge_rag.controller;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@CrossOrigin(origins = "http://localhost:3000")
public class DebugController {

    private final EmbeddingModel embeddingModel;

    public DebugController(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @GetMapping("/embedding")
    public Map<String, Object> testEmbedding(@RequestParam String text) {
        float[] vector = embeddingModel.embed(text);
        return Map.of(
            "text", text,
            "dimensions", vector.length,
            "first5", Arrays.copyOf(vector, 5)
        );
    }
}
```

调用三次，观察向量差异：
```bash
curl "localhost:8080/api/debug/embedding?text=Java是一门编程语言"
curl "localhost:8080/api/debug/embedding?text=Kotlin也是JVM上的语言"
curl "localhost:8080/api/debug/embedding?text=今天北京天气很好"
```

**关键理解：** Embedding 把"语义"映射到高维空间，语义越相近的文本，向量越接近。nomic-embed-text 返回 768 维向量，调用三次观察 `dimensions` 字段均为 768。

---

### Day 2：实现 Chunking

📄 `backend/src/main/java/com/example/knowledge_rag/entity/DocumentChunk.java`

```java
package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_chunks")
@Data
@NoArgsConstructor
public class DocumentChunk {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "content_hash")
    private String contentHash;    // SHA-256，防止重复 embedding

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
```

📄 `backend/src/main/java/com/example/knowledge_rag/repository/DocumentChunkRepository.java`

```java
package com.example.knowledge_rag.repository;

import com.example.knowledge_rag.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, String> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(String documentId);
    boolean existsByContentHash(String contentHash);
    void deleteByDocumentId(String documentId);
}
```

在 `DocumentProcessingService` 里添加 `chunkAndEmbed` 方法：

```java
private void chunkAndEmbed(String documentId,
                             List<org.springframework.ai.document.Document> parsedDocs) {

    /**
     * TokenTextSplitter 按 Token 数切分文本。
     *
     * 参数含义：
     * ┌─────────────────┬────────────────────────────────────────────────────────┐
     * │ chunkSize (512) │ 每个 chunk 的目标 token 数                             │
     * │                 │ Token ≠ 字符：1 个中文字 ≈ 1-2 token                   │
     * │                 │ 512 token ≈ 350-400 个中文字                           │
     * ├─────────────────┼────────────────────────────────────────────────────────┤
     * │ overlap (100)   │ 相邻 chunk 之间重叠的 token 数                          │
     * │                 │ 为什么要 overlap？                                      │
     * │                 │ 防止一句话被切割在两个 chunk 边界，导致语义断裂。         │
     * ├─────────────────┼────────────────────────────────────────────────────────┤
     * │ minChunkLength  │ 少于 5 字符的 chunk 直接丢弃（页眉页脚等噪音）           │
     * └─────────────────┴────────────────────────────────────────────────────────┘
     *
     * chunk size 选择经验：
     * < 128 token  → chunk 太小，缺乏上下文，检索到了但回答质量差
     * 512 token    → 推荐起点，平衡语义完整性和检索精度
     * > 1024 token → chunk 太大，包含太多无关内容，稀释语义，召回率下降
     */
    var splitter = new TokenTextSplitter(512, 100, 5, 1000, true);

    // 给每个文档的 metadata 打上 document_id 标记。
    // metadata 会跟着 chunk 一起存入 pgvector（存为 JSONB 类型）。
    // 检索时可用 filterExpression("document_id == 'xxx'") 限定只搜某个文档。
    for (var doc : parsedDocs) {
        doc.getMetadata().put("document_id", documentId);
    }

    List<org.springframework.ai.document.Document> chunks = splitter.apply(parsedDocs);
    log.info("Document {} → {} chunks", documentId, chunks.size());

    /**
     * vectorStore.add() 这一行在内部自动完成 RAG 索引的全部三步：
     *
     * 步骤 1：Embedding（向量化）
     *   → 对每个 chunk 调用 EmbeddingModel.embed(chunk.content)
     *   → 调用本地 Ollama nomic-embed-text 模型（完全免费，无网络请求）
     *   → 把文本转换为 768 维的浮点数向量
     *
     * 步骤 2：持久化
     *   → 把向量、chunk 内容、metadata 一起存入 pgvector 的 vector_store 表
     *   → 表结构：id | content (text) | metadata (jsonb) | embedding (vector(768))
     *
     * 步骤 3：建索引（首次或按需）
     *   → pgvector 在 embedding 列上维护 IVFFlat/HNSW 索引
     *   → 使得后续的相似性搜索在百万级数据下仍能毫秒级返回
     *
     * 成本：本地模型，零 API 费用。速度取决于机器 CPU/GPU，首次调用较慢，后续会缓存。
     */
    vectorStore.add(chunks);

    // 同步存入 document_chunks 表，方便做 JOIN 查询和展示 chunk 列表。
    // 与 vector_store 是冗余的，但各有用途：
    // - vector_store：负责向量相似性搜索
    // - document_chunks：负责业务查询（按文档查 chunk、统计数量、删除等）
    for (int i = 0; i < chunks.size(); i++) {
        var chunk = new DocumentChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(i);
        chunk.setContent(chunks.get(i).getContent());
        chunkRepository.save(chunk);
    }
}
```

**验收：**
```bash
docker exec -it knowledge-rag-postgres-1 psql -U dev -d knowledge_rag \
  -c "SELECT id, metadata->>'document_id', LEFT(content, 50) FROM vector_store LIMIT 5;"
```

---

### Day 3：实现 RAG 检索

先创建 `RetrievedChunk` 数据传输对象，它贯穿检索、展示、评估全链路：

📄 `backend/src/main/java/com/example/knowledge_rag/dto/RetrievedChunk.java`

```java
package com.example.knowledge_rag.dto;

public record RetrievedChunk(
    String id,
    String content,
    String documentId,
    double score   // 余弦距离：越小越相似（0 = 完全相同，2 = 完全相反）
) {}
```

📄 `backend/src/main/java/com/example/knowledge_rag/service/RagService.java`

```java
package com.example.knowledge_rag.service;

import com.example.knowledge_rag.dto.RetrievedChunk;
import com.example.knowledge_rag.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DocumentChunkRepository chunkRepository;

    /**
     * RAG（Retrieval-Augmented Generation）的核心思路：
     *
     * 用户提问 → 问题向量化 → pgvector 找最相似的 N 个 chunk
     * → 把 chunk 内容拼入 prompt → LLM 基于这些内容回答
     *
     * 关键认知：LLM 不是在"搜索"，而是在"理解和总结"你检索到的内容。
     * 检索质量决定 RAG 效果的 70%，LLM 的生成只占 30%。
     * 调 RAG 系统 = 主要在调检索，而不是调 prompt。
     */
    public List<RetrievedChunk> search(String question, int topK, String documentId) {

        /**
         * SearchRequest 构建向量搜索请求。
         * .query(question)：Spring AI 自动把 question 传给 EmbeddingModel 做向量化，
         *                   再用生成的向量去 pgvector 做余弦相似性搜索。
         * .withTopK(topK)：返回向量空间中距离最近的 topK 个结果。
         *
         * topK 选择建议：
         * - 太小（1-2）：可能漏关键信息，回答不完整
         * - 太大（10+）：context 过长，LLM 容易忽略靠后的内容（"Lost in the Middle"问题）
         * - 推荐起点：5，再根据评估集调整
         */
        var builder = SearchRequest.query(question).withTopK(topK);

        if (documentId != null) {
            /**
             * filterExpression 在向量搜索的同时加 metadata 过滤。
             * Spring AI 把这个表达式转成 SQL WHERE 子句：
             * WHERE (metadata->>'document_id') = 'xxx'
             *
             * 安全警告：
             * 多用户场景下，必须加 owner_id 过滤，否则用户 A 可以检索到用户 B 的文档。
             * 这是 RAG 系统最常见的权限漏洞，不可忽视。
             */
            builder = builder.withFilterExpression("document_id == '" + documentId + "'");
        }

        return vectorStore.similaritySearch(builder).stream()
            .map(doc -> new RetrievedChunk(
                doc.getId(),
                doc.getContent(),
                (String) doc.getMetadata().getOrDefault("document_id", ""),
                /**
                 * "distance" 是向量间的余弦距离（值域 0 到 2），数值越小越相似：
                 *
                 * 0.00 ~ 0.20 → 极度相似（几乎相同的语义）
                 * 0.20 ~ 0.35 → 高度相关（好的检索结果）
                 * 0.35 ~ 0.50 → 中度相关（可能有用，也可能是噪音）
                 * 0.50+       → 相关性低（通常不应该放进 prompt）
                 *
                 * 注意：这是"距离"不是"相似度"，展示给用户时换算：similarity = 1 - distance
                 */
                (Double) doc.getMetadata().getOrDefault("distance", 0.0)
            ))
            .toList();
    }

    public String buildContext(List<RetrievedChunk> chunks) {
        return IntStream.range(0, chunks.size())
            .mapToObj(i -> "[%d] %s".formatted(i + 1, chunks.get(i).content()))
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * RAG System Prompt 的四个核心设计原则：
     *
     * 1. 划定信息边界："只根据以下资料回答"
     *    → 防止 LLM 混入训练数据知识产生幻觉（hallucination）
     *    → 没有这条约束，LLM 倾向于用"感觉合理"的内容填充，而不是承认不知道
     *
     * 2. 明确的拒绝指令："如果资料中没有，直接说无法回答"
     *    → 对知识库场景，"自信但错误"的回答比"我不知道"危害更大
     *
     * 3. 要求标注引用来源："用'根据第[N]段'标注"
     *    → 用户可以点击来源核实，建立信任感
     *    → 是 RAG 产品与普通 AI 聊天最重要的差异化体验
     *
     * 4. Context 放 System Prompt，问题放 User Message
     *    → 符合 OpenAI 最佳实践，LLM 能更好地区分"背景知识"和"用户提问"
     */
    public String buildRagSystemPrompt(String context) {
        return """
            你是一个知识库助手，只根据以下资料回答问题。
            如果资料中没有相关信息，直接说"根据现有文档，我无法回答这个问题"，不要编造内容。
            回答时用"根据第[N]段"标注引用来源。

            资料：
            %s
            """.formatted(context);
    }
}
```

---

### Day 4：引用来源展示

先创建请求 DTO：

📄 `backend/src/main/java/com/example/knowledge_rag/dto/RagRequest.java`

```java
package com.example.knowledge_rag.dto;

public record RagRequest(
    String question,
    String documentId,    // 可选，null 表示搜全库
    String conversationId // 可选，用于多轮 RAG 对话
) {}
```

**后端：先返回 sources（同步），再流式返回回答**

📄 `backend/src/main/java/com/example/knowledge_rag/controller/RagController.java`

```java
package com.example.knowledge_rag.controller;

import com.example.knowledge_rag.dto.RagRequest;
import com.example.knowledge_rag.dto.RetrievedChunk;
import com.example.knowledge_rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final ChatClient chatClient;

    @PostMapping("/rag/sources")
    public List<RetrievedChunk> getSources(@RequestBody RagRequest request) {
        return ragService.search(request.question(), 5, request.documentId());
    }

    /**
     * RAG 流式回答，加相似度阈值守卫。
     *
     * pgvector 返回的 distance 是余弦距离（0 到 2，越小越相似）：
     * < 0.35  → 足够相关，可以用来回答
     * > 0.35  → 相关性不足，直接拒绝，不让 LLM 发挥想象
     *
     * 阈值不靠感觉猜，通过评估集数据来确定。
     */
    @PostMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ragStream(@RequestBody RagRequest request) {
        var chunks = ragService.search(request.question(), 5, request.documentId());

        boolean hasRelevantChunks = chunks.stream().anyMatch(c -> c.score() < 0.35);
        if (!hasRelevantChunks) {
            return Flux.just("根据现有文档，我没有找到与该问题相关的内容。");
        }

        String context = ragService.buildContext(chunks);
        return chatClient.prompt()
            .system(ragService.buildRagSystemPrompt(context))
            .user(request.question())
            .stream()
            .content();
    }
}
```

📄 `frontend/src/app/rag/page.tsx` — 先展示 sources，再流式展示回答（调用 `/api/rag/sources` 后调 `/api/rag/stream`，参考 `page.tsx` 的 `sendMessage` 模式实现）。

---

### Day 5-7：端到端测试

上传一份你熟悉的技术文档，然后：
1. 查看 `vector_store` 表的 embeddings
2. 问文档中有答案的问题，验证引用
3. 问文档中没有的问题，验证拒绝回答
4. 调整 topK（3 / 5 / 10），对比回答质量差异

---

## 第 4 周：提升检索质量 + 调试面板

**目标：** 从"能跑"到"答案靠谱"，能系统性地调试 RAG 管道。

**本周学习：** Chunk Size Tuning、Hybrid Search、Query Rewrite、RRF 融合排名

---

### Day 1：Chunk Size 实验接口

加到 `DebugController`（`/api/debug/preview-chunking`）。`PreviewChunkRequest` 是内联 record：

📄 `backend/src/main/java/com/example/knowledge_rag/controller/DebugController.java`（追加方法）

```java
// 新增 import
import com.example.knowledge_rag.dto.RetrievalDebugResult;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;

record PreviewChunkRequest(String text) {}

@PostMapping("/debug/preview-chunking")
public Map<String, Object> previewChunking(@RequestBody PreviewChunkRequest req) {
    record Config(int size, int overlap) {}
    var configs = List.of(new Config(256, 50), new Config(512, 100), new Config(1024, 200));

    return configs.stream().collect(Collectors.toMap(
        c -> "chunks_" + c.size(),
        c -> {
            var splitter = new TokenTextSplitter(c.size(), c.overlap(), 5, 1000, true);
            var doc = new org.springframework.ai.document.Document(req.text());
            var chunks = splitter.apply(List.of(doc));
            return Map.of(
                "count", chunks.size(),
                "preview", chunks.subList(0, Math.min(2, chunks.size()))
                    .stream().map(d -> d.getContent().substring(0,
                        Math.min(100, d.getContent().length()))).toList()
            );
        }
    ));
}
```

规律：太小（< 128）丢失上下文，太大（> 1024）稀释语义，**推荐起点 512 / overlap 100**。

---

### Day 2-3：Hybrid Search（向量 + 关键词）

给 `DocumentChunkRepository` 加全文搜索：

📄 `backend/src/main/java/com/example/knowledge_rag/repository/DocumentChunkRepository.java`（追加方法）

```java
// 新增 import
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Query(value = """
    SELECT * FROM document_chunks
    WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', :query)
    ORDER BY ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', :query)) DESC
    LIMIT :limit
    """, nativeQuery = true)
List<DocumentChunk> fullTextSearch(@Param("query") String query, @Param("limit") int limit);
```

在 `RagService` 加 `hybridSearch`，用 RRF 融合两路排名：

```java
public List<RetrievedChunk> hybridSearch(String question, int topK) {

    /**
     * Hybrid Search = 向量搜索 + 关键词搜索，两路互补：
     *
     * ┌──────────────┬────────────────────────────────┬─────────────────────────────┐
     * │              │ 向量搜索                        │ 关键词搜索                   │
     * ├──────────────┼────────────────────────────────┼─────────────────────────────┤
     * │ 优势         │ 理解语义                        │ 精确匹配                     │
     * │              │ "汽车" 能匹配 "轿车"            │ "qwen2:7b" 一定能找到        │
     * ├──────────────┼────────────────────────────────┼─────────────────────────────┤
     * │ 弱点         │ 专有名词、型号、代号效果差       │ 同义词、近义词完全找不到      │
     * └──────────────┴────────────────────────────────┴─────────────────────────────┘
     */
    var vectorResults = search(question, topK, null);
    var keywordResults = chunkRepository.fullTextSearch(question, topK);

    /**
     * RRF（Reciprocal Rank Fusion，倒数排名融合）
     *
     * 核心思路：一个文档在多路排名中都靠前，说明它更可信。
     *
     * 公式：score(doc) = Σ [ 1 / (k + rank_i) ]
     * k = 60（论文推荐的平滑参数，防止第 1 名分数过度压倒其他名次）
     *
     * 举例（topK=3）：
     * chunk A：向量搜索第 1，关键词搜索第 2
     *   score = 1/(60+1) + 1/(60+2) = 0.01639 + 0.01613 = 0.03252
     *
     * chunk B：只在向量搜索第 2
     *   score = 1/(60+2) = 0.01613
     *
     * → chunk A 得分更高，因为两路都认为它相关
     */
    Map<String, Double> scores = new HashMap<>();
    int k = 60;

    for (int i = 0; i < vectorResults.size(); i++)
        // merge(key, value, mergeFn)：key 存在则用 mergeFn 合并，不存在则直接 put
        scores.merge(vectorResults.get(i).id(), 1.0 / (k + i + 1), Double::sum);

    for (int i = 0; i < keywordResults.size(); i++)
        scores.merge(keywordResults.get(i).getId(), 1.0 / (k + i + 1), Double::sum);

    return scores.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .limit(topK)
        .map(e -> findChunkById(e.getKey(), vectorResults, keywordResults))
        .toList();
}
```

---

### Day 4-5：检索调试面板

先创建两个 DTO：

📄 `backend/src/main/java/com/example/knowledge_rag/dto/RetrievalDebugResult.java`

```java
package com.example.knowledge_rag.dto;

import java.util.List;

public record RetrievalDebugResult(
    String originalQuestion,
    String rewrittenQuestion,
    List<RetrievedChunk> chunks,
    String promptPreview,
    long latencyMs
) {}
```

📄 `backend/src/main/java/com/example/knowledge_rag/dto/DebugRequest.java`

```java
package com.example.knowledge_rag.dto;

public record DebugRequest(String question, Integer topK) {}
```

后端调试接口加到 `DebugController`：

📄 `backend/src/main/java/com/example/knowledge_rag/controller/DebugController.java`（追加）

```java
@PostMapping("/debug/retrieval")
public RetrievalDebugResult debugRetrieval(@RequestBody DebugRequest req) {
    long start = System.currentTimeMillis();

    String rewritten = ragService.rewriteQuery(req.question());
    var chunks = ragService.hybridSearch(rewritten, req.topK() != null ? req.topK() : 5);
    String promptPreview = ragService.buildRagSystemPrompt(ragService.buildContext(chunks));

    return new RetrievalDebugResult(
        req.question(),
        rewritten,
        chunks,
        promptPreview,
        System.currentTimeMillis() - start
    );
}
```

📄 `frontend/src/app/debug/page.tsx` — 前端调试面板展示：

| 展示内容 | 说明 |
|---|---|
| 原始问题 vs 改写后问题 | 观察 rewrite 效果 |
| 检索到的 chunks（含分数）| 判断相关性 |
| 实际发给 LLM 的 prompt | 看上下文是否合理 |
| 检索耗时 | 性能感知 |
| topK 滑块 | 实时调整，对比效果 |

---

### Day 6-7：Query Rewrite（查询改写）

```java
public String rewriteQuery(String original) {
    /**
     * Query Rewrite 解决的问题：
     * 用户输入的口语化问题在向量空间里的位置，
     * 往往和文档里对应内容的向量位置差距较大。
     *
     * 典型失败案例：
     * 用户输入："那个处理事务的注解咋用？"
     * 文档内容："@Transactional 注解用于声明式事务管理..."
     *
     * 直接 embedding "那个处理事务的注解咋用" 效果不佳，
     * 改写为 "Spring @Transactional 注解 使用方法 事务管理" 后召回率大幅提升。
     *
     * 使用注意：
     * - 每次 rewrite 多一次 LLM 调用（额外时延 + 成本）
     * - 短而精确的技术查询（如 "@Transactional 原理"）不需要改写，可加判断跳过
     * - 改写策略效果要通过评估集验证，不能只靠主观感觉
     */
    return chatClient.prompt()
        .user("""
            请将以下问题改写成更适合向量检索的形式：
            - 展开缩写和指代词（"那个" → 具体名称）
            - 使用更精确的技术术语
            - 保持原意，不加解释，不扩展范围
            只输出改写后的查询，不要解释。

            原始问题：%s
            """.formatted(original))
        .call()
        .content()
        .trim();
}
```

---

## 第 5 周：完善成真正的产品

**目标：** 补齐用户体验闭环。

**本周学习：** 会话持久化、反馈系统、Spring Security 基础、多知识库

---

### Day 1-2：会话历史持久化

📄 `backend/src/main/java/com/example/knowledge_rag/entity/Conversation.java`

```java
package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversations")
@Data
@NoArgsConstructor
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String title;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

📄 `backend/src/main/java/com/example/knowledge_rag/entity/Message.java`

```java
package com.example.knowledge_rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "conversation_id")
    private String conversationId;

    @Enumerated(EnumType.STRING)
    private MessageRole role;    // USER / ASSISTANT

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "JSONB")
    private String sources;      // JSON 序列化的 chunks

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

📄 `backend/src/main/java/com/example/knowledge_rag/entity/MessageRole.java`

```java
package com.example.knowledge_rag.entity;

public enum MessageRole { USER, ASSISTANT }
```

每次 RAG 回答完成后保存消息，前端加左侧对话列表（类 ChatGPT 布局）。

---

### Day 3：反馈系统（点赞 / 点踩）

给 `Message` 加字段，同时新建 `FeedbackType` 枚举：

📄 `backend/src/main/java/com/example/knowledge_rag/entity/FeedbackType.java`

```java
package com.example.knowledge_rag.entity;

public enum FeedbackType { LIKED, DISLIKED }
```

`Message.java` 追加：

```java
@Enumerated(EnumType.STRING)
private FeedbackType feedback;  // LIKED / DISLIKED / null

@Column(columnDefinition = "TEXT")
private String feedbackComment;
```

📄 `backend/src/main/java/com/example/knowledge_rag/dto/FeedbackRequest.java`

```java
package com.example.knowledge_rag.dto;

import com.example.knowledge_rag.entity.FeedbackType;

public record FeedbackRequest(FeedbackType type, String comment) {}
```

接口加到 `RagController`（或单独 `MessageController`）：

```java
@PutMapping("/messages/{id}/feedback")
public void feedback(@PathVariable String id, @RequestBody FeedbackRequest req) {
    messageService.updateFeedback(id, req.type(), req.comment());
}
```

---

### Day 4：多知识库支持

添加 `KnowledgeBase` 实体，`documents` 表加 `knowledge_base_id` 列，检索时过滤：

```java
SearchRequest.query(question)
    .withTopK(5)
    .withFilterExpression("kb_id == '" + kbId + "'")
```

---

### Day 5-7：交互细节

- 文档删除：同步删除 `document_chunks`，并调 `vectorStore.delete(ids)` 删 embeddings
- 重新生成：传相同问题，清空上一次答案，触发新调用
- 三段 loading 状态：`检索中... → 生成中... → 完成`
- 空状态页：没有文档时引导用户上传

---

## 第 6 周：生产化能力

**目标：** 权限过滤、性能优化、评估集、延迟优化

---

### Day 1：权限过滤（Spring Security + JWT）

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
```

给文档加 `owner_id` 字段，检索时强制过滤：

```java
String userId = SecurityContextHolder.getContext().getAuthentication().getName();
// owner_id 过滤确保用户只能搜索自己的文档，这是防止数据泄露的关键
builder = builder.withFilterExpression("owner_id == '" + userId + "'");
```

---

### Day 2：Embedding 性能优化

```java
// content hash 缓存，跳过重复 embedding（Ollama 本地推理也有耗时，值得缓存）
String hash = DigestUtils.sha256Hex(content);
if (chunkRepository.existsByContentHash(hash)) {
    log.info("Skipping duplicate chunk: {}", hash);
    return;
}

// 批量 embedding 减少模型调用次数（N 次串行 → 1 次批量，对 Ollama 效果尤其明显）
List<float[]> vectors = embeddingModel.embed(List.of(text1, text2, text3));
```

---

### Day 3：缓存

```yaml
spring:
  cache:
    type: redis
  data:
    redis:
      host: localhost
      port: 6379
```

```java
@Cacheable(value = "rag-sources", key = "#question + '_' + #documentId")
public List<RetrievedChunk> search(String question, int topK, String documentId) { ... }

// 文档更新后主动 evict
@CacheEvict(value = "rag-sources", allEntries = true)
public void deleteDocument(String id) { ... }
```

---

### Day 4：pgvector 索引优化

```sql
-- 替换默认的 IVFFlat 索引，改用 HNSW（更快的近似最近邻算法）
--
-- HNSW 原理：把向量组织成多层图结构，搜索时从顶层开始"贪心游走"到最近邻。
-- 查询复杂度 O(log N)，远优于暴力搜索的 O(N)。
--
-- 参数说明：
-- m (16)：图中每个节点的最大边数。越大精度越高，但内存占用和建索引时间增加。推荐 16。
-- ef_construction (64)：建索引时每个节点的候选邻居数。越大质量越好，建索引越慢。推荐 64。
--
-- vector_cosine_ops：使用余弦距离。nomic-embed-text 输出归一化向量，余弦距离是语义搜索领域的惯例。
DROP INDEX IF EXISTS vector_store_embedding_idx;
CREATE INDEX ON vector_store
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
```

---

### Day 5-6：评估集

```java
@PostMapping("/eval/run")
public EvalReport runEval() {
    /**
     * 为什么必须有评估集？
     *
     * RAG 系统有大量超参数：chunk size、overlap、topK、相似度阈值、prompt 措辞...
     * 如果靠"感觉"调参，很容易陷入：改了 A 好了，改了 B 却把 A 搞坏了的循环。
     *
     * 核心指标：
     *
     * Hit Rate（命中率）= 检索到的 chunks 里是否包含来自正确文档的内容
     * → 衡量检索系统的"召回能力"
     * → Hit Rate 低 → chunk size 问题，或 topK 太小
     *
     * Keyword Match Rate = 最终回答是否包含期望关键词
     * → 粗粒度的回答质量代理指标，比 LLM 打分便宜
     * → Keyword Match 低 → prompt 设计问题，或检索内容不够精准
     *
     * 使用方法：每次调整参数后跑一遍，对比前后指标，确认方向正确再继续。
     */
    List<EvalCase> cases = loadCasesFromJson();

    return cases.stream()
        .map(c -> {
            var chunks = ragService.hybridSearch(c.question(), 5);

            boolean hit = chunks.stream()
                .anyMatch(chunk -> c.relevantDocId().equals(chunk.documentId()));

            String answer = generateAnswerSync(c.question(), chunks);

            // Keyword Match：期望关键词是否都出现在答案里（粗略但有效的初筛指标）
            boolean keywordMatch = c.expectedKeywords().stream()
                .allMatch(kw -> answer.contains(kw));

            return new EvalResult(c.question(), hit, keywordMatch);
        })
        .collect(collectingAndThen(toList(), EvalReport::new));
}
```

评估数据文件 `eval/cases.json` 格式：
```json
[
  {
    "question": "Spring Boot 如何配置多数据源？",
    "expectedKeywords": ["DataSource", "@Primary", "yml"],
    "relevantDocId": "doc-001"
  }
]
```

---

### Day 7：延迟优化清单

| 优化点 | 操作 |
|---|---|
| 流式响应 | 确保所有 LLM 调用都走 stream，降低感知延迟 |
| HNSW 索引 | 上面 Day 4 已操作 |
| topK 控制 | 默认 5，不超过 8（prompt 越长推理越慢）|
| 并行检索 | 用 `CompletableFuture.allOf` 并行执行向量搜索和关键词搜索 |
| 监控埋点 | Spring Boot Actuator + 结构化日志记录每步耗时 |

---

## 验收清单（全部完成即可面试 / 落地）

| 功能 | 验收标准 |
|---|---|
| 文档上传 | 支持 PDF、Word、TXT，状态流转正确 |
| Chunking | pgvector 里有对应 embeddings |
| RAG 检索 | 提问能召回相关 chunks |
| 引用来源 | 每条回答展示来源 chunk 和相似度分数 |
| 流式响应 | 前端逐字流式展示 |
| 拒绝回答 | 无相关内容时拒绝，不乱编 |
| 调试面板 | 可见 rewrite、chunks、分数、prompt、耗时 |
| Hybrid Search | 向量 + 关键词融合排名 |
| 会话历史 | 持久化，可翻看 |
| 反馈系统 | 点赞 / 踩入库 |
| 权限过滤 | 用户只能检索自己的文档 |
| 评估集 | 20+ 条，跑出 hitRate 报告 |
