package dev.llmladder.llm;

public record ToolResult(String toolCallId, String content, boolean error) {}
