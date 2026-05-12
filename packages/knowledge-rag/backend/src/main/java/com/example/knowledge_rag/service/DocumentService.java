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