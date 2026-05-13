package com.example.knowledge_rag.service;

import com.example.knowledge_rag.entity.Document;
import com.example.knowledge_rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
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

    // 浏览器/OS 有时无法识别 MIME，会回退到 application/octet-stream；
    // 此时改从文件名后缀推断真实类型。
    private static final Map<String, String> EXT_TO_MIME = Map.of(
            "pdf",  "application/pdf",
            "txt",  "text/plain",
            "md",   "text/markdown",
            "doc",  "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    public Document upload(MultipartFile file) {
        String contentType = resolveContentType(file);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType);
        }
        String filename = fileStorageService.store(file);
        var doc = new Document();
        doc.setFilename(filename);
        doc.setOriginalFilename(file.getOriginalFilename());
        doc.setFileType(contentType);
        doc.setFileSize(file.getSize());
        var saved = documentRepository.save(doc);
        processingService.processAsync(saved.getId());
        return saved;
    }

    private String resolveContentType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && !declared.equals("application/octet-stream")) {
            return declared;
        }
        // 从原始文件名后缀兜底
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            String ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            return EXT_TO_MIME.getOrDefault(ext, declared);
        }
        return declared;
    }
}