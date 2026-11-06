package dev.llmladder.eval;

import dev.llmladder.core.Demand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Sobe a escada degrau a degrau e para no primeiro que atende aos critérios. */
public final class LadderRunner {
    public record Step(Rung rung, Function<GoldenCase, Map<String, Object>> runner) {}

    public record RungResult(Rung rung, SuiteReport report, String advice) {}

    public record LadderReport(Demand demand, String suite, List<RungResult> rungs) {
        public Rung stoppedAt() {
            return rungs.get(rungs.size() - 1).rung();
        }

        public boolean satisfied() {
            return rungs.get(rungs.size() - 1).report().passed();
        }
    }

    private LadderRunner() {}

    public static LadderReport climb(Demand demand, String suite, List<GoldenCase> cases, List<Step> steps,
                                     List<Grader> graders, Map<String, Double> minimums) {
        List<RungResult> results = new ArrayList<>();
        for (Step step : steps) {
            SuiteReport report = EvalRunner.run(suite, cases, step.runner(), graders, minimums);
            results.add(new RungResult(step.rung(), report, EscalationAdvisor.advise(step.rung(), report.failingKinds())));
            if (report.passed()) {
                break;
            }
        }
        return new LadderReport(demand, suite, results);
    }
}
