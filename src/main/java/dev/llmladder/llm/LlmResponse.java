package dev.llmladder.llm;

import java.util.List;

public record LlmResponse(String model, String text, List<ToolCall> toolCalls, Stop stop, Usage usage, Object raw) {
    public enum Stop { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

    public LlmMessage.Assistant asAssistantMessage() {
        return new LlmMessage.Assistant(text, toolCalls, raw);
    }
}
