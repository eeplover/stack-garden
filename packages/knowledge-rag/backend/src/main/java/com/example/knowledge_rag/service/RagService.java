package com.example.knowledge_rag.service;

import com.example.knowledge_rag.dto.RetrievedChunk;
import com.example.knowledge_rag.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DocumentChunkRepository chunkRepository;

    /**
     * RAG 不需要对话记忆（每次问答上下文通过 system prompt 注入），
     * 所以直接用 builder 构建一个无 advisor 的轻量 ChatClient。
     * Spring AI 只注册 ChatClient.Builder bean，不注册 ChatClient bean，
     * 必须在构造函数里手动 build。
     */
    public RagService(ChatClient.Builder builder,
                      VectorStore vectorStore,
                      DocumentChunkRepository chunkRepository) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.chunkRepository = chunkRepository;
    }

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
        var reqBuilder = SearchRequest.builder()
                .query(question)
                .topK(topK);

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
            reqBuilder = reqBuilder.filterExpression("document_id == '" + documentId + "'");
        }

        return vectorStore.similaritySearch(reqBuilder.build()).stream()
                .map(doc -> new RetrievedChunk(
                        doc.getId(),
                        doc.getText(),
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
                        // pgvector 写入的 distance 是 Float，不能直接强转 Double；
                        // 先转 Number（Float/Double 的公共父类）再取 doubleValue()。
                        ((Number) doc.getMetadata().getOrDefault("distance", 0.0)).doubleValue()
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
    /**
     * 检索 + 生成一体，供 RagController 直接调用。
     * 相似度阈值 0.35：distance < 0.35 才认为足够相关，否则拒绝回答，防止 LLM 凭空生成。
     */
    public Flux<String> stream(String question, String documentId) {
        var chunks = search(question, 5, documentId);
        boolean hasRelevantChunks = chunks.stream().anyMatch(c -> c.score() < 0.35);
        if (!hasRelevantChunks) {
            return Flux.just("根据现有文档，我没有找到与该问题相关的内容。");
        }
        String context = buildContext(chunks);
        return chatClient.prompt()
                .system(buildRagSystemPrompt(context))
                .user(question)
                .stream()
                .content();
    }

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