package dev.llmladder.llm;

/** Porta para o modelo. Implementações: Anthropic (real) e simulador determinístico (CI sem chave). */
public interface LlmClient {
    LlmResponse complete(LlmRequest request);
}
