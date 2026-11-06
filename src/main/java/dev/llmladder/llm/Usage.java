package dev.llmladder.llm;

public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public long total() {
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }
}
