package dev.llmladder.observability;

import dev.llmladder.llm.Usage;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumo acumulado por tenant, dia (UTC) e modelo. Fica no gateway para contar toda chamada em um único
 * ponto. O custo é uma estimativa; a referência de cobrança é o console do provedor.
 */
public final class UsageLedger {
    static final int RETENTION_DAYS = 90;

    public record Totals(long calls, long cacheHits, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheWriteTokens, long totalTokens, double costUsd) {
        public static final Totals ZERO = new Totals(0, 0, 0, 0, 0, 0, 0, 0);

        public Totals plus(Totals other) {
            return new Totals(calls + other.calls, cacheHits + other.cacheHits,
                    inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens,
                    totalTokens + other.totalTokens, costUsd + other.costUsd);
        }
    }

    public record Report(String tenant, LocalDate from, LocalDate to, Totals total,
                         Map<String, Totals> byModel, Map<LocalDate, Totals> byDay) {}

    private record Key(String tenant, LocalDate day, String model) {}

    private final Map<Key, Totals> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public UsageLedger(Clock clock) {
        this.clock = clock;
    }

    public void recordCall(String tenant, String model, Usage usage, double costUsd) {
        add(tenant, model, new Totals(1, 0, usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.total(), costUsd));
    }

    /** Resposta servida pelo cache do gateway: sem chamada ao modelo, sem tokens e sem custo. */
    public void recordCacheHit(String tenant, String model) {
        add(tenant, model, new Totals(0, 1, 0, 0, 0, 0, 0, 0));
    }

    private void add(String tenant, String model, Totals delta) {
        LocalDate today = today();
        entries.merge(new Key(tenant, today, model), delta, Totals::plus);
        entries.keySet().removeIf(key -> key.day().isBefore(today.minusDays(RETENTION_DAYS)));
    }

    /** Sem datas, cobre do primeiro dia do mês atual até hoje. */
    public Report report(String tenant, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from não pode ser depois de to");
        }
        Totals total = Totals.ZERO;
        Map<String, Totals> byModel = new TreeMap<>();
        Map<LocalDate, Totals> byDay = new TreeMap<>();
        for (Map.Entry<Key, Totals> entry : entries.entrySet()) {
            Key key = entry.getKey();
            if (key.tenant().equals(tenant) && !key.day().isBefore(start) && !key.day().isAfter(end)) {
                total = total.plus(entry.getValue());
                byModel.merge(key.model(), entry.getValue(), Totals::plus);
                byDay.merge(key.day(), entry.getValue(), Totals::plus);
            }
        }
        return new Report(tenant, start, end, total, byModel, byDay);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
