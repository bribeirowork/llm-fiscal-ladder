package dev.llmladder.rag;

/** @param tenant dono do trecho; {@link KnowledgeBase#PUBLIC} é visível para todos. */
public record Chunk(String id, String docId, String title, String section, String tenant, String source, String text) {}
