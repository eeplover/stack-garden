package com.example.knowledge_rag.controller;

import com.example.knowledge_rag.dto.ChatRequest;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

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
            // MessageWindowChatMemory：内存中的滑动窗口历史，重启后丢失。生产可换持久化 ChatMemory。
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build())
                // Spring AI 要求 advisor 带有非空的默认会话 ID，否则 getConversationId 会直接 Assert 失败。
                .conversationId(ChatMemory.DEFAULT_CONVERSATION_ID)
                .build())
            .build();
    }

    // 非流式接口，调试用。等 LLM 生成完整响应后一次性返回。
    @PostMapping
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        String response = chatClient.prompt()
            .user(request.message())
            .advisors(a -> a.param(
                ChatMemory.CONVERSATION_ID,
                request.conversationId() != null ? request.conversationId() : ChatMemory.DEFAULT_CONVERSATION_ID
            ))
            .call()
            .content();
        return Map.of("content", response);
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