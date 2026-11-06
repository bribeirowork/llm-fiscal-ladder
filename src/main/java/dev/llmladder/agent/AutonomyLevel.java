package dev.llmladder.agent;

/** Quanto maior o dano de errar, menor a autonomia. */
public enum AutonomyLevel {
    SUGERE(0, "Humano decide"),
    LEITURA(1, "Somente leitura"),
    REVERSIVEL(2, "Idempotência, log e limite"),
    IRREVERSIVEL(3, "Aprovação humana obrigatória");

    public final int level;
    public final String control;

    AutonomyLevel(int level, String control) {
        this.level = level;
        this.control = control;
    }
}
