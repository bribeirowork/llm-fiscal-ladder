package dev.llmladder.agent;

import dev.llmladder.fiscal.AccessKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro das ações com efeito, à moda de uma saga: cada ação guarda como ser repetida com segurança
 * (chave de idempotência) e como ser desfeita (compensação).
 */
public final class ActionLog {
    public enum Status { CONCLUIDA, INCERTA, COMPENSACAO_SOLICITADA }

    public record Action(String id, String tenant, String type, AccessKey key, String idempotencyKey,
                         Status status, int attempts, String compensation, Instant updatedAt) {}

    private final Map<String, Action> byIdempotencyKey = new ConcurrentHashMap<>();

    public Optional<Action> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    public Action save(String tenant, String type, AccessKey key, String idempotencyKey, Status status, String compensation, Instant now) {
        return byIdempotencyKey.compute(idempotencyKey, (k, previous) -> new Action(
                previous == null ? UUID.randomUUID().toString() : previous.id(),
                tenant, type, key, idempotencyKey, status,
                previous == null ? 1 : previous.attempts() + 1,
                compensation, now));
    }

    public void markCompensating(Action action, Instant now) {
        byIdempotencyKey.put(action.idempotencyKey(), new Action(action.id(), action.tenant(), action.type(), action.key(),
                action.idempotencyKey(), Status.COMPENSACAO_SOLICITADA, action.attempts(), action.compensation(), now));
    }

    public Optional<Action> find(String id, String tenant) {
        return byIdempotencyKey.values().stream()
                .filter(a -> a.id().equals(id) && a.tenant().equals(tenant))
                .findFirst();
    }

    public List<Action> forTenant(String tenant) {
        return byIdempotencyKey.values().stream().filter(a -> a.tenant().equals(tenant)).toList();
    }
}
