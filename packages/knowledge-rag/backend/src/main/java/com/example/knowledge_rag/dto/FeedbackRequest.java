package com.example.knowledge_rag.dto;

import com.example.knowledge_rag.entity.FeedbackType;

public record FeedbackRequest(FeedbackType type, String comment) {}