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