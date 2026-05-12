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
}