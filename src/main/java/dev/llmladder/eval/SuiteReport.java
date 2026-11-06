package dev.llmladder.eval;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public record SuiteReport(String suite, List<Score> scores, List<Failure> failures, double averageCostUsd) {
    public record Score(String grader, FailureKind kind, int passed, int total, double minimum) {
        public double value() {
            return total == 0 ? 1 : (double) passed / total;
        }

        public boolean ok() {
            return value() >= minimum;
        }
    }

    public record Failure(String caseId, String grader, FailureKind kind) {}

    public boolean passed() {
        return scores.stream().allMatch(Score::ok);
    }

    public Set<FailureKind> failingKinds() {
        return scores.stream().filter(s -> !s.ok()).map(Score::kind).collect(Collectors.toSet());
    }

    public Map<String, Double> scoreMap() {
        return scores.stream().collect(Collectors.toMap(Score::grader, Score::value));
    }

    public static Function<Score, String> row() {
        return s -> String.format(Locale.ROOT, "| %s | %d/%d | %.2f | %.2f | %s |", s.grader(), s.passed(), s.total(), s.value(), s.minimum(), s.ok() ? "ok" : "FALHOU");
    }
}
