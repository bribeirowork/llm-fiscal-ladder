package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmladder.llm.Usage;
import dev.llmladder.observability.UsageLedger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UsageLedgerTest {
    private static final String HAIKU = "claude-haiku-5-5";
    private static final String OPUS = "claude-opus-5-5";

    private final MutableClock clock = new MutableClock();
    private final UsageLedger ledger = new UsageLedger(clock);

    @Test
    void sumsSeparatelyByModelAndKeepsTokenTypes() {
        ledger.recordCall("acme", HAIKU, new Usage(100, 50, 10, 5), 0.01);
        ledger.recordCall("acme", HAIKU, new Usage(200, 25, 0, 0), 0.02);
        ledger.recordCall("acme", OPUS, new Usage(1_000, 500, 0, 0), 0.50);

        var report = ledger.report("acme", null, null);

        assertThat(report.total().calls()).isEqualTo(3);
        assertThat(report.total().inputTokens()).isEqualTo(1_300);
        assertThat(report.total().outputTokens()).isEqualTo(575);
        assertThat(report.total().cacheReadTokens()).isEqualTo(10);
        assertThat(report.total().cacheWriteTokens()).isEqualTo(5);
        assertThat(report.total().totalTokens()).isEqualTo(1_890);
        assertThat(report.total().costUsd()).isCloseTo(0.53, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(report.byModel().get(HAIKU).calls()).isEqualTo(2);
        assertThat(report.byModel().get(OPUS).totalTokens()).isEqualTo(1_500);
    }

    @Test
    void cacheHitCountsAsHitWithoutCallOrTokens() {
        ledger.recordCacheHit("acme", HAIKU);

        var total = ledger.report("acme", null, null).total();

        assertThat(total.cacheHits()).isEqualTo(1);
        assertThat(total.calls()).isZero();
        assertThat(total.totalTokens()).isZero();
    }

    @Test
    void tenantsAreIsolated() {
        ledger.recordCall("acme", HAIKU, new Usage(100, 50, 0, 0), 0.01);

        assertThat(ledger.report("globex", null, null).total().calls()).isZero();
        assertThat(ledger.report("globex", null, null).byModel()).isEmpty();
    }

    @Test
    void filtersByPeriodAndGroupsByDay() {
        ledger.recordCall("acme", HAIKU, new Usage(10, 0, 0, 0), 0);
        clock.advance(Duration.ofDays(2));
        ledger.recordCall("acme", HAIKU, new Usage(20, 0, 0, 0), 0);
        LocalDate second = LocalDate.parse("2026-11-07");

        var onlySecond = ledger.report("acme", second, second);
        var both = ledger.report("acme", LocalDate.parse("2026-11-05"), second);

        assertThat(onlySecond.total().inputTokens()).isEqualTo(20);
        assertThat(both.total().inputTokens()).isEqualTo(30);
        assertThat(both.byDay()).hasSize(2);
    }

    @Test
    void defaultPeriodIsCurrentMonth() {
        var report = ledger.report("acme", null, null);

        assertThat(report.from()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(report.to()).isEqualTo(LocalDate.parse("2026-11-05"));
    }

    @Test
    void rejectsInvertedPeriod() {
        assertThatThrownBy(() -> ledger.report("acme", LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dropsEntriesOlderThanRetention() {
        LocalDate oldDay = LocalDate.parse("2026-11-05");
        ledger.recordCall("acme", HAIKU, new Usage(10, 0, 0, 0), 0);

        clock.advance(Duration.ofDays(91));
        ledger.recordCall("acme", HAIKU, new Usage(20, 0, 0, 0), 0);

        assertThat(ledger.report("acme", oldDay, oldDay).total().calls()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
