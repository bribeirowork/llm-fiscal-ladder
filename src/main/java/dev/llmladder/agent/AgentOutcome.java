package dev.llmladder.agent;

import java.util.List;

/**
 * O desfecho é calculado a partir do que o sistema observou, nunca do texto do modelo.
 * @param summary resumo do modelo, apenas informativo.
 */
public record AgentOutcome(Status status, String documentState, List<String> toolsCalled, List<String> denials,
                           String approvalId, String summary, String traceId) {
    public enum Status { RESOLVIDO, AGUARDANDO_APROVACAO, ESCALADO }
}
