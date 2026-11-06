package dev.llmladder.eval;

import java.util.Set;

/** A regra que sustenta a escada: a próxima decisão vem do tipo de falha medido, não de opinião. */
public final class EscalationAdvisor {
    private EscalationAdvisor() {}

    public static String advise(Rung current, Set<FailureKind> failures) {
        if (failures.isEmpty()) {
            return "Critérios atendidos: fique neste degrau.";
        }
        if (failures.contains(FailureKind.SEGURANCA)) {
            return "Falha de segurança: corrija a engenharia ao redor antes de considerar subir.";
        }
        if (failures.contains(FailureKind.CONHECIMENTO) && current.order < Rung.CONTEXTO.order) {
            return "Erro de conhecimento: suba para contexto/RAG, não para fine-tuning.";
        }
        if (failures.contains(FailureKind.ACAO)) {
            return "A tarefa exige agir: suba para ferramentas. Se o caminho for previsível, prefira workflow a agente.";
        }
        if (failures.contains(FailureKind.COMPORTAMENTO)) {
            return "Erro de comportamento: melhore prompt e exemplos; se seguir estável, avalie fine-tuning (LoRA/QLoRA).";
        }
        return "Erro de conhecimento persistente: avalie a qualidade do retrieval e do corpus.";
    }
}
