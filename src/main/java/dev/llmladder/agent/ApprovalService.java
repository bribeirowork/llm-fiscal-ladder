package dev.llmladder.agent;

import dev.llmladder.core.Principal;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.fiscal.SefazPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nível 3: ação irreversível só com um humano. As regras são revalidadas na hora de executar, porque entre o
 * pedido e a aprovação a nota pode ter sido cancelada ou a janela pode ter fechado.
 */
public final class ApprovalService {
    public static final Duration CANCEL_WINDOW = Duration.ofHours(24);

    public enum Status { PENDENTE, EXECUTADA, REJEITADA, FALHOU }

    public record Approval(String id, String tenant, AccessKey key, String justification, String requestedBy,
                           Status status, String decidedBy, String result, Instant createdAt) {
        Approval decided(Status newStatus, String approver, String newResult) {
            return new Approval(id, tenant, key, justification, requestedBy, newStatus, approver, newResult, createdAt);
        }
    }

    public static class ApprovalDeniedException extends RuntimeException {
        public ApprovalDeniedException(String message) {
            super(message);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final SefazPort sefaz;
    private final AuditLog audit;
    private final ActionLog actions;
    private final Clock clock;

    public ApprovalService(SefazPort sefaz, AuditLog audit, ActionLog actions, Clock clock) {
        this.sefaz = sefaz;
        this.audit = audit;
        this.actions = actions;
        this.clock = clock;
    }

    public boolean withinCancelWindow(SefazPort.Status status) {
        return status.authorizedAt() != null
                && Duration.between(status.authorizedAt(), clock.instant()).compareTo(CANCEL_WINDOW) <= 0;
    }

    /** Idempotente: um pedido pendente para a mesma chave é devolvido em vez de duplicado. */
    public synchronized Approval request(String tenant, AccessKey key, String justification, String requestedBy) {
        Optional<Approval> pending = approvals.values().stream()
                .filter(a -> a.tenant().equals(tenant) && a.key().equals(key) && a.status() == Status.PENDENTE)
                .findFirst();
        return pending.orElseGet(() -> {
            Approval created = new Approval(UUID.randomUUID().toString(), tenant, key, justification, requestedBy,
                    Status.PENDENTE, null, null, clock.instant());
            approvals.put(created.id(), created);
            return created;
        });
    }

    public List<Approval> pending(String tenant) {
        return approvals.values().stream()
                .filter(a -> a.tenant().equals(tenant) && a.status() == Status.PENDENTE)
                .toList();
    }

    public synchronized Approval approve(String id, Principal approver) {
        Approval approval = authorize(id, approver);
        SefazPort.Status status = sefaz.consult(approval.key());
        if (status.state() != SefazPort.State.AUTORIZADA || !withinCancelWindow(status)) {
            return finish(approval, Status.FALHOU, approver, "pré-condição falhou: " + status.state() + " ou fora da janela de 24h");
        }
        SefazPort.CancelResult result = sefaz.cancel(approval.key(), status.protocol(), approval.justification());
        return finish(approval, result.registered() ? Status.EXECUTADA : Status.FALHOU, approver, "cStat " + result.cStat());
    }

    public synchronized Approval reject(String id, Principal approver) {
        return finish(authorize(id, approver), Status.REJEITADA, approver, "rejeitada pelo aprovador");
    }

    /** Desfazer uma autorização é um cancelamento: continua sendo irreversível e continua pedindo aprovação. */
    public synchronized Approval compensate(String actionId, String justification, Principal principal) {
        ActionLog.Action action = actions.find(actionId, principal.tenant())
                .orElseThrow(() -> new NotFoundException("ação não encontrada"));
        if (action.status() == ActionLog.Status.COMPENSACAO_SOLICITADA) {
            throw new ApprovalDeniedException("compensação já solicitada");
        }
        Approval approval = request(principal.tenant(), action.key(), justification, principal.subject());
        actions.markCompensating(action, clock.instant());
        audit.record(principal.tenant(), principal.subject(), action.id(), "compensar", AutonomyLevel.IRREVERSIVEL,
                action.key().masked(), "aprovação " + approval.id());
        return approval;
    }

    private Approval authorize(String id, Principal approver) {
        Approval approval = Optional.ofNullable(approvals.get(id))
                .filter(a -> a.tenant().equals(approver.tenant()))
                .orElseThrow(() -> new NotFoundException("aprovação não encontrada"));
        if (!approver.hasRole(Principal.ROLE_APPROVER)) {
            throw new ApprovalDeniedException("papel de aprovador exigido");
        }
        if (approver.subject().equals(approval.requestedBy())) {
            throw new ApprovalDeniedException("quem solicitou não pode aprovar");
        }
        if (approval.status() != Status.PENDENTE) {
            throw new ApprovalDeniedException("aprovação já decidida");
        }
        return approval;
    }

    private Approval finish(Approval approval, Status status, Principal approver, String result) {
        Approval decided = approval.decided(status, approver.subject(), result);
        approvals.put(decided.id(), decided);
        audit.record(approval.tenant(), approver.subject(), approval.id(), "cancelar_nfe_110111",
                AutonomyLevel.IRREVERSIVEL, approval.key().masked(), status + ": " + result);
        return decided;
    }
}
