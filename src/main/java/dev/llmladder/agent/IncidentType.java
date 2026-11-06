package dev.llmladder.agent;

import java.util.Set;

/** Menor privilégio: cada tipo de incidente recebe só as ferramentas que a tarefa exige. */
public enum IncidentType {
    TIMEOUT(Set.of(FiscalTools.CONSULTAR, FiscalTools.BUSCAR_MANUAL, FiscalTools.REENVIAR)),
    CANCELAMENTO(Set.of(FiscalTools.CONSULTAR, FiscalTools.BUSCAR_MANUAL, FiscalTools.CANCELAR));

    public final Set<String> allowedTools;

    IncidentType(Set<String> allowedTools) {
        this.allowedTools = allowedTools;
    }
}
