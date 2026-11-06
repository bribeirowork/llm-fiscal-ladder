package dev.llmladder.rag;

import java.util.List;

public record Answer(String text, List<Citation> citations, double confidence, String model, boolean needsHuman, String reason, String traceId) {
    public record Citation(String chunkId, String docId, String document, String section, String source) {}
}
