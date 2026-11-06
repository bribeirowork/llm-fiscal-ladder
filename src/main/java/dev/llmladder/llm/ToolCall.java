package dev.llmladder.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode input) {}
