package dev.llmladder.eval;

import dev.llmladder.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class EvalRunner {
    private EvalRunner() {}

    public static Map<String, Map<String, Double>> loadCriteria() {
        try (InputStream in = EvalRunner.class.getClassLoader().getResourceAsStream("evals/criteria.json")) {
            Map<String, Map<String, Double>> criteria = new HashMap<>();
            Json.MAPPER.readTree(in).properties().forEach(suite -> {
                Map<String, Double> minimums = new HashMap<>();
                suite.getValue().properties().forEach(g -> minimums.put(g.getKey(), g.getValue().asDouble()));
                criteria.put(suite.getKey(), minimums);
            });
            return criteria;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static SuiteReport run(String suite, List<GoldenCase> cases, Function<GoldenCase, Map<String, Object>> runner,
                                  List<Grader> graders, Map<String, Double> minimums) {
        Map<String, int[]> tally = new LinkedHashMap<>();
        graders.forEach(g -> tally.put(g.name(), new int[2]));
        List<SuiteReport.Failure> failures = new ArrayList<>();
        double cost = 0;

        for (GoldenCase testCase : cases) {
            Map<String, Object> facts = runner.apply(testCase);
            cost += ((Number) facts.getOrDefault("cost_usd", 0.0)).doubleValue();
            for (Grader grader : graders) {
                Optional<Boolean> grade = grader.grade(testCase, facts);
                if (grade.isEmpty()) {
                    continue;
                }
                int[] counts = tally.get(grader.name());
                counts[1]++;
                if (grade.get()) {
                    counts[0]++;
                } else {
                    failures.add(new SuiteReport.Failure(testCase.id(), grader.name(), grader.kind()));
                }
            }
        }
        List<SuiteReport.Score> scores = new ArrayList<>();
        for (Grader grader : graders) {
            int[] counts = tally.get(grader.name());
            scores.add(new SuiteReport.Score(grader.name(), grader.kind(), counts[0], counts[1], minimums.getOrDefault(grader.name(), 1.0)));
        }
        return new SuiteReport(suite, scores, failures, cases.isEmpty() ? 0 : cost / cases.size());
    }
}
