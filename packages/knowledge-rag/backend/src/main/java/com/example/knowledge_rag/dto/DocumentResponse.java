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