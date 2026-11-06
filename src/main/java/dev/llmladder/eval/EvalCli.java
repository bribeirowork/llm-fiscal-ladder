package dev.llmladder.eval;

import dev.llmladder.core.Json;
import dev.llmladder.llm.AnthropicLlmClient;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.SimulatedLlmClient;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Gate de qualidade. Sem flags usa o simulador (grátis, verifica o sistema ao redor do modelo).
 * {@code --live} chama a API real e gera custo; {@code --judge} adiciona o LLM-juiz e exige --live.
 */
public final class EvalCli {
    public static final Path BASELINE = Path.of("evals", "baseline.json");
    private static final Path REPORT = Path.of("target", "eval-report.md");

    private EvalCli() {}

    public static int run(String[] args) {
        Set<String> flags = Set.of(args);
        boolean live = flags.contains("--live");
        boolean judge = flags.contains("--judge");
        if (judge && !live) {
            System.err.println("--judge exige --live: o simulador não julga nada de verdade.");
            return 2;
        }
        LlmClient client = live ? AnthropicLlmClient.fromEnv() : new SimulatedLlmClient();
        Suites suites = new Suites(client);
        List<Grader> extra = judge ? List.of(new LlmJudgeGrader(suites.platform().gateway)) : List.of();

        List<LadderRunner.LadderReport> ladders = suites.climbAll(extra);
        String report = render(ladders, live);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        out.println(report);
        write(REPORT, report);

        if (flags.contains("--update-baseline")) {
            write(BASELINE, Json.write(scores(ladders)));
            out.println("Baseline atualizada: " + BASELINE);
            return 0;
        }
        List<String> regressions = live ? List.of() : regressions(ladders);
        regressions.forEach(r -> System.err.println("REGRESSÃO: " + r));
        boolean ok = ladders.stream().allMatch(LadderRunner.LadderReport::satisfied) && regressions.isEmpty();
        out.println(ok ? "GATE: aprovado" : "GATE: reprovado");
        return ok ? 0 : 1;
    }

    /** A pontuação do degrau final de cada suíte, usada para travar regressões. */
    public static Map<String, Map<String, Double>> scores(List<LadderRunner.LadderReport> ladders) {
        Map<String, Map<String, Double>> scores = new TreeMap<>();
        for (var ladder : ladders) {
            scores.put(ladder.suite(), new TreeMap<>(ladder.rungs().get(ladder.rungs().size() - 1).report().scoreMap()));
        }
        return scores;
    }

    /** Mudança que derruba o score não entra, mesmo acima do mínimo. */
    public static List<String> regressions(List<LadderRunner.LadderReport> ladders) {
        if (!Files.exists(BASELINE)) {
            return List.of();
        }
        List<String> regressions = new ArrayList<>();
        var baseline = read(BASELINE);
        scores(ladders).forEach((suite, current) -> current.forEach((grader, score) -> {
            double previous = baseline.path(suite).path(grader).asDouble(0);
            if (score < previous) {
                regressions.add(String.format(Locale.ROOT, "%s/%s: %.3f < %.3f", suite, grader, score, previous));
            }
        }));
        return regressions;
    }

    private static String render(List<LadderRunner.LadderReport> ladders, boolean live) {
        StringBuilder out = new StringBuilder("# Relatório de evals\n\n");
        out.append(live ? "Modo: API real.\n\n" : "Modo: simulador. Mede o sistema ao redor do modelo, não a qualidade do modelo.\n\n");
        for (var ladder : ladders) {
            out.append("## ").append(ladder.demand()).append(": ").append(ladder.demand().pattern).append("\n\n");
            for (var rung : ladder.rungs()) {
                out.append("### Degrau ").append(rung.rung().order).append(": ").append(rung.rung().label)
                        .append(rung.report().passed() ? " (aprovado)" : " (reprovado)").append("\n\n");
                out.append("| grader | acertos | score | mínimo | status |\n|---|---|---|---|---|\n");
                rung.report().scores().stream().map(SuiteReport.row()).forEach(row -> out.append(row).append('\n'));
                out.append(String.format(Locale.ROOT, "\nCusto médio estimado por tarefa: US$ %.6f\n\n", rung.report().averageCostUsd()));
                out.append("Decisão: ").append(rung.advice()).append("\n\n");
            }
        }
        return out.toString();
    }

    private static com.fasterxml.jackson.databind.JsonNode read(Path path) {
        try {
            return Json.read(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
