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
        return ragService.stream(request.question(), request.documentId());
    }

}