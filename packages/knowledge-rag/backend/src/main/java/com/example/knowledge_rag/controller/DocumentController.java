package com.example.knowledge_rag.controller;

import com.example.knowledge_rag.dto.DocumentResponse;
import com.example.knowledge_rag.repository.DocumentRepository;
import com.example.knowledge_rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;

    @PostMapping
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file) {
        var saved = documentService.upload(file);
        return ResponseEntity.ok(DocumentResponse.from(saved));
    }

    @GetMapping
    public List<DocumentResponse> list() {
        return documentRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }
}
