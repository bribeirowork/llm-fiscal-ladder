package dev.llmladder.observability;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Gera o relatório de consumo em PNG com Java2D, sem dependências e sem serviços externos. */
public final class UsageReportImage {
    /** Acima disso as barras diárias ficam ilegíveis; a retenção do consumo também é de 90 dias. */
    public static final int MAX_DAYS = 92;

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 760;
    private static final int SCALE = 2;
    private static final int MARGIN = 40;
    private static final int MAX_BAR_WIDTH = 24;
    private static final int MAX_MODEL_ROWS = 5;

    private static final Color SURFACE = Color.decode("#fcfcfb");
    private static final Color TILE = Color.decode("#f3f2ee");
    private static final Color TEXT_PRIMARY = Color.decode("#0b0b0b");
    private static final Color TEXT_SECONDARY = Color.decode("#52514e");
    private static final Color GRID = Color.decode("#e4e3de");
    private static final Color SERIES = Color.decode("#2a78d6");

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM");

    private UsageReportImage() {}

    /** @param simulated marca a imagem para que tokens estimados não sejam lidos como consumo real. */
    public static byte[] render(UsageLedger.Report report, boolean simulated) {
        long days = ChronoUnit.DAYS.between(report.from(), report.to()) + 1;
        if (days > MAX_DAYS) {
            throw new IllegalArgumentException("o período da imagem deve ter até " + MAX_DAYS + " dias");
        }
        BufferedImage image = new BufferedImage(WIDTH * SCALE, HEIGHT * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.scale(SCALE, SCALE);
            g.setColor(SURFACE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            drawHeader(g, report, simulated);
            drawTiles(g, report.total());
            drawDailyChart(g, report, (int) days);
            drawModelPanel(g, report);
            text(g, "Custo estimado pela tabela de preços do projeto; a cobrança real está no console do provedor. Dias em UTC.",
                    MARGIN, HEIGHT - 22, 12, false, TEXT_SECONDARY);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void drawHeader(Graphics2D g, UsageLedger.Report report, boolean simulated) {
        text(g, "Consumo de tokens", MARGIN, 58, 28, true, TEXT_PRIMARY);
        String period = "Tenant %s  ·  %s a %s".formatted(report.tenant(), report.from().format(FULL_DATE), report.to().format(FULL_DATE));
        text(g, period, MARGIN, 84, 15, false, TEXT_SECONDARY);
        if (simulated) {
            String label = "Dados simulados";
            g.setFont(font(13, true));
            int width = g.getFontMetrics().stringWidth(label) + 24;
            int x = WIDTH - MARGIN - width;
            g.setColor(TEXT_SECONDARY);
            g.setStroke(new BasicStroke(1));
            g.draw(new RoundRectangle2D.Float(x, 40, width, 28, 8, 8));
            text(g, label, x + 12, 59, 13, true, TEXT_SECONDARY);
        }
    }

    private static void drawTiles(Graphics2D g, UsageLedger.Totals total) {
        long answered = total.calls() + total.cacheHits();
        String hitRate = answered == 0 ? "sem respostas" : percent((double) total.cacheHits() / answered) + " das respostas";
        long cacheTokens = total.cacheReadTokens() + total.cacheWriteTokens();
        List<String[]> tiles = List.of(
                new String[] {"Tokens totais", integer(total.totalTokens()), "Soma de entrada, saída e cache"},
                new String[] {"Entrada / saída", integer(total.inputTokens()) + " / " + integer(total.outputTokens()), "Cache do provedor: " + integer(cacheTokens)},
                new String[] {"Custo estimado", usd(total.costUsd()), calls(total.calls()) + " ao modelo"},
                new String[] {"Cache do gateway", integer(total.cacheHits()), hitRate});
        int gap = 16;
        int width = (WIDTH - 2 * MARGIN - 3 * gap) / 4;
        for (int i = 0; i < tiles.size(); i++) {
            int x = MARGIN + i * (width + gap);
            g.setColor(TILE);
            g.fill(new RoundRectangle2D.Float(x, 108, width, 98, 12, 12));
            String[] tile = tiles.get(i);
            text(g, tile[0], x + 16, 134, 14, false, TEXT_SECONDARY);
            text(g, tile[1], x + 16, 168, fitSize(g, tile[1], width - 32), true, TEXT_PRIMARY);
            text(g, tile[2], x + 16, 192, 13, false, TEXT_SECONDARY);
        }
    }

    private static int fitSize(Graphics2D g, String value, int available) {
        for (int size = 28; size > 16; size -= 2) {
            g.setFont(font(size, true));
            if (g.getFontMetrics().stringWidth(value) <= available) {
                return size;
            }
        }
        return 16;
    }

    private static void drawDailyChart(Graphics2D g, UsageLedger.Report report, int days) {
        int left = MARGIN + 62;
        int right = 730;
        int top = 278;
        int bottom = 680;
        text(g, "Tokens por dia", MARGIN, 246, 18, true, TEXT_PRIMARY);

        long peak = report.byDay().values().stream().mapToLong(UsageLedger.Totals::totalTokens).max().orElse(0);
        if (peak == 0) {
            text(g, "Sem consumo no período", left + 180, (top + bottom) / 2, 16, false, TEXT_SECONDARY);
            return;
        }
        long step = niceStep(peak / 4.0);
        long axisMax = (long) Math.ceil((double) peak / step) * step;
        for (long tick = 0; tick <= axisMax; tick += step) {
            int y = bottom - (int) Math.round((double) tick / axisMax * (bottom - top));
            g.setColor(GRID);
            g.setStroke(new BasicStroke(1));
            g.drawLine(left, y, right, y);
            String label = integer(tick);
            g.setFont(font(12, false));
            text(g, label, left - 10 - g.getFontMetrics().stringWidth(label), y + 4, 12, false, TEXT_SECONDARY);
        }

        double band = (double) (right - left) / days;
        int barWidth = (int) Math.max(1, Math.min(MAX_BAR_WIDTH, band - 2));
        int labelEvery = (int) Math.ceil(days / 8.0);
        boolean peakLabeled = false;
        for (int i = 0; i < days; i++) {
            LocalDate day = report.from().plusDays(i);
            int center = left + (int) Math.round(band * i + band / 2);
            if (i % labelEvery == 0) {
                String label = day.format(SHORT_DATE);
                g.setFont(font(12, false));
                text(g, label, center - g.getFontMetrics().stringWidth(label) / 2, bottom + 20, 12, false, TEXT_SECONDARY);
            }
            UsageLedger.Totals totals = report.byDay().get(day);
            if (totals == null || totals.totalTokens() == 0) {
                continue;
            }
            int height = Math.max(2, (int) Math.round((double) totals.totalTokens() / axisMax * (bottom - top)));
            int x = center - barWidth / 2;
            g.setColor(SERIES);
            g.fill(new RoundRectangle2D.Float(x, bottom - height, barWidth, height, 8, 8));
            if (height > 4) {
                g.fillRect(x, bottom - height / 2, barWidth, height / 2);
            }
            if (!peakLabeled && totals.totalTokens() == peak) {
                peakLabeled = true;
                String label = integer(peak);
                g.setFont(font(13, true));
                int textX = center - g.getFontMetrics().stringWidth(label) / 2;
                text(g, label, Math.max(left, Math.min(textX, right - 60)), bottom - height - 8, 13, true, TEXT_PRIMARY);
            }
        }
    }

    private static void drawModelPanel(Graphics2D g, UsageLedger.Report report) {
        int left = 780;
        int right = WIDTH - MARGIN;
        text(g, "Por modelo", left, 246, 18, true, TEXT_PRIMARY);

        List<Map.Entry<String, UsageLedger.Totals>> rows = rows(report);
        if (rows.isEmpty()) {
            text(g, "Sem consumo no período", left, 300, 15, false, TEXT_SECONDARY);
            return;
        }
        long max = rows.stream().mapToLong(r -> r.getValue().totalTokens()).max().orElse(1);
        int y = 290;
        for (Map.Entry<String, UsageLedger.Totals> row : rows) {
            UsageLedger.Totals totals = row.getValue();
            text(g, row.getKey(), left, y, 15, true, TEXT_PRIMARY);
            String tokens = integer(totals.totalTokens()) + " tokens";
            g.setFont(font(14, false));
            text(g, tokens, right - g.getFontMetrics().stringWidth(tokens), y, 14, false, TEXT_PRIMARY);

            int barWidth = max == 0 ? 0 : Math.max(4, (int) Math.round((double) totals.totalTokens() / max * (right - left)));
            g.setColor(TILE);
            g.fill(new RoundRectangle2D.Float(left, y + 10, right - left, 8, 8, 8));
            g.setColor(SERIES);
            g.fill(new RoundRectangle2D.Float(left, y + 10, barWidth, 8, 8, 8));
            text(g, "%s  ·  %s".formatted(calls(totals.calls()), usd(totals.costUsd())), left, y + 38, 13, false, TEXT_SECONDARY);
            y += 78;
        }
    }

    /** Ordena por consumo e agrupa o excedente em "Outros" para a lista caber na imagem. */
    private static List<Map.Entry<String, UsageLedger.Totals>> rows(UsageLedger.Report report) {
        List<Map.Entry<String, UsageLedger.Totals>> sorted = new ArrayList<>(report.byModel().entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, UsageLedger.Totals> e) -> e.getValue().totalTokens()).reversed());
        if (sorted.size() <= MAX_MODEL_ROWS) {
            return sorted;
        }
        UsageLedger.Totals others = sorted.subList(MAX_MODEL_ROWS - 1, sorted.size()).stream()
                .map(Map.Entry::getValue)
                .reduce(UsageLedger.Totals.ZERO, UsageLedger.Totals::plus);
        List<Map.Entry<String, UsageLedger.Totals>> rows = new ArrayList<>(sorted.subList(0, MAX_MODEL_ROWS - 1));
        rows.add(Map.entry("Outros", others));
        return rows;
    }

    private static long niceStep(double rough) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(1, rough))));
        double normalized = rough / magnitude;
        double nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return Math.max(1, (long) (nice * magnitude));
    }

    private static void text(Graphics2D g, String value, int x, int y, int size, boolean bold, Color color) {
        g.setFont(font(size, bold));
        g.setColor(color);
        g.drawString(value, x, y);
    }

    private static Font font(int size, boolean bold) {
        return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static String integer(long value) {
        return NumberFormat.getIntegerInstance(PT_BR).format(value);
    }

    private static String calls(long value) {
        return integer(value) + (value == 1 ? " chamada" : " chamadas");
    }

    private static String percent(double value) {
        return NumberFormat.getPercentInstance(PT_BR).format(value);
    }

    private static String usd(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        int digits = value > 0 && value < 0.01 ? 4 : 2;
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return "US$ " + format.format(value);
    }
}
