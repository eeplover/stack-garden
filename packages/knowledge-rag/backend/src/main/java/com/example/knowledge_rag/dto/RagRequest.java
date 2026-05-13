package com.example.knowledge_rag.dto;

public record RagRequest(
        String question,
        String documentId,    // 可选，null 表示搜全库
        String conversationId // 可选，用于多轮 RAG 对话
) {}