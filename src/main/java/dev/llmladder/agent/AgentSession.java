package dev.llmladder.agent;

import dev.llmladder.core.Principal;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.fiscal.SefazPort;
import dev.llmladder.gateway.PiiVault;
import dev.llmladder.observability.TaskTrace;
import java.util.ArrayList;
import java.util.List;

/**
 * Estado que a política confia, porque foi observado pelo código e não declarado pelo modelo.
 * {@code knownState} volta a DESCONHECIDO depois de qualquer timeout: só uma consulta nova restabelece.
 */
final class AgentSession {
    final IncidentType type;
    final AccessKey key;
    final Principal principal;
    final PiiVault vault;
    final TaskTrace trace;
    final List<String> toolsCalled = new ArrayList<>();
    final List<String> denials = new ArrayList<>();
    SefazPort.State knownState = SefazPort.State.DESCONHECIDO;
    String pendingApprovalId;
    int calls;

    AgentSession(IncidentType type, AccessKey key, Principal principal, PiiVault vault, TaskTrace trace) {
        this.type = type;
        this.key = key;
        this.principal = principal;
        this.vault = vault;
        this.trace = trace;
    }
}
