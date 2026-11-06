package dev.llmladder.eval;

/** Classificar o erro é o que decide a direção da escada. */
public enum FailureKind {
    CONHECIMENTO, // o modelo não sabe: fato que muda vai para o contexto
    ACAO,         // a tarefa exige fazer algo: ferramentas
    COMPORTAMENTO, // sabe, mas responde do jeito errado de forma estável: prompt e, só então, fine-tuning
    SEGURANCA     // não se resolve subindo degrau: corrige-se a engenharia ao redor
}
