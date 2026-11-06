package dev.llmladder.llm;

import java.util.List;

public sealed interface LlmMessage {
    record User(String text) implements LlmMessage {}

    /** {@code raw} guarda a resposta original do provedor: blocos de thinking precisam voltar intactos. */
    record Assistant(String text, List<ToolCall> toolCalls, Object raw) implements LlmMessage {}

    record ToolResults(List<ToolResult> results) implements LlmMessage {}
}
