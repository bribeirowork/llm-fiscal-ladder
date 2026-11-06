package dev.llmladder.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Trilha de auditoria: só anexa. A chave entra mascarada; em produção o destino é um armazenamento imutável. */
public final class AuditLog {
    public record Entry(Instant at, String tenant, String actor, String taskId, String action, AutonomyLevel level, String key, String result) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Clock clock;

    public AuditLog(Clock clock) {
        this.clock = clock;
    }

    public void record(String tenant, String actor, String taskId, String action, AutonomyLevel level, String key, String result) {
        entries.add(new Entry(clock.instant(), tenant, actor, taskId, action, level, key, result));
    }

    public List<Entry> forTenant(String tenant) {
        return entries.stream().filter(e -> e.tenant().equals(tenant)).toList();
    }
}
