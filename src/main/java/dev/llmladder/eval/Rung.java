package dev.llmladder.eval;

/** A escada: cada degrau custa mais que o anterior. */
public enum Rung {
    PROMPT(1, "Prompt"),
    CONTEXTO(2, "Contexto / RAG"),
    FERRAMENTAS(3, "Agente + ferramentas (MCP)"),
    FINE_TUNING(4, "Fine-tuning (LoRA/QLoRA)"),
    PRE_TREINO(5, "Pré-treino");

    public final int order;
    public final String label;

    Rung(int order, String label) {
        this.order = order;
        this.label = label;
    }
}
