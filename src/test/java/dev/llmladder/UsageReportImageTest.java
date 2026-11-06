package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmladder.llm.Usage;
import dev.llmladder.observability.UsageLedger;
import dev.llmladder.observability.UsageReportImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class UsageReportImageTest {
    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersReportWithDataAsPng() throws IOException {
        UsageLedger ledger = new UsageLedger(Clock.systemUTC());
        ledger.recordCall("acme", "claude-haiku-5-5", new Usage(1_000, 200, 50, 10), 0.002);
        ledger.recordCall("acme", "claude-opus-5-5", new Usage(5_000, 900, 0, 0), 0.04);
        ledger.recordCacheHit("acme", "claude-haiku-5-5");

        BufferedImage image = decode(UsageReportImage.render(ledger.report("acme", null, null), true));

        assertThat(image.getWidth()).isEqualTo(2400);
        assertThat(image.getHeight()).isEqualTo(1520);
    }

    @Test
    void emptyPeriodStillRenders() throws IOException {
        var report = new UsageLedger(Clock.systemUTC()).report("acme", null, null);

        assertThat(decode(UsageReportImage.render(report, false))).isNotNull();
    }

    @Test
    void rendersManyModelsAndTheLongestAllowedPeriod() throws IOException {
        UsageLedger ledger = new UsageLedger(Clock.systemUTC());
        for (int i = 1; i <= 8; i++) {
            ledger.recordCall("acme", "modelo-" + i, new Usage(i * 1_000L, 0, 0, 0), 0.01);
        }
        LocalDate to = LocalDate.now(Clock.systemUTC());
        var report = ledger.report("acme", to.minusDays(UsageReportImage.MAX_DAYS - 1), to);

        assertThat(decode(UsageReportImage.render(report, false))).isNotNull();
    }

    @Test
    void rejectsPeriodTooLongForDailyBars() {
        LocalDate to = LocalDate.parse("2026-11-05");
        var report = new UsageLedger(Clock.systemUTC()).report("acme", to.minusDays(UsageReportImage.MAX_DAYS), to);

        assertThatThrownBy(() -> UsageReportImage.render(report, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
