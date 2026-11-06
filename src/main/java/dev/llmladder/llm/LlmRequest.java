package dev.llmladder.llm;

import java.util.List;
import java.util.Map;

public record LlmRequest(
        TaskType task,
        String model,
        String system,
        List<LlmMessage> messages,
        List<ToolSpec> tools,
        Map<String, Object> jsonSchema,
        long maxTokens,
        Effort effort) {

    public enum Effort { LOW, MEDIUM, HIGH }

    public LlmRequest withModel(String newModel) {
        return new LlmRequest(task, newModel, system, messages, tools, jsonSchema, maxTokens, effort);
    }

    public LlmRequest withMessages(List<LlmMessage> newMessages) {
        return new LlmRequest(task, model, system, newMessages, tools, jsonSchema, maxTokens, effort);
    }
}
