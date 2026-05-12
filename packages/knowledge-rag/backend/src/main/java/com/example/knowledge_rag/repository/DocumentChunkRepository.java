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