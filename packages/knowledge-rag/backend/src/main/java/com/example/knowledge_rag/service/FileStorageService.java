package com.example.knowledge_rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class FileStorageService {

    private final Path uploadDir;

    public FileStorageService(@Value("${app.upload.dir}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir);
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create upload directory", e);
        }
    }

    public String store(MultipartFile file) {
        String filename = UUID.randomUUID() + "_" +
                StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        try {
            Files.copy(file.getInputStream(), uploadDir.resolve(filename));
            return filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    public Path load(String filename) {
        return uploadDir.resolve(filename);
    }
}