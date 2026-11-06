package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmladder.eval.EvalCli;
import dev.llmladder.eval.LadderRunner;
import dev.llmladder.eval.Rung;
import dev.llmladder.eval.Suites;
import dev.llmladder.llm.SimulatedLlmClient;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** O gate roda no CI: mudança que derruba score não entra. */
class EvalGateTest {
    private static List<LadderRunner.LadderReport> ladders;

    @BeforeAll
    static void climb() {
        ladders = new Suites(new SimulatedLlmClient()).climbAll(List.of());
    }

    @Test
    void everyDemandMeetsItsCriteria() {
        assertThat(ladders).allSatisfy(l -> assertThat(l.satisfied()).as(l.suite()).isTrue());
    }

    @Test
    void eachDemandStopsAtTheCheapestRungThatWorks() {
        assertThat(ladders.get(0).stoppedAt()).isEqualTo(Rung.CONTEXTO);
        assertThat(ladders.get(1).stoppedAt()).isEqualTo(Rung.PROMPT);
        assertThat(ladders.get(2).stoppedAt()).isEqualTo(Rung.FERRAMENTAS);
    }

    @Test
    void knowledgeFailureRecommendsContextNotFineTuning() {
        assertThat(ladders.get(0).rungs().get(0).advice()).contains("contexto/RAG").contains("não para fine-tuning");
    }

    @Test
    void automationWithoutToolsRecommendsToolsNotSecurityFix() {
        assertThat(ladders.get(2).rungs().get(0).advice()).contains("suba para ferramentas");
    }

    @Test
    void scoresDoNotRegressFromBaseline() {
        assertThat(EvalCli.regressions(ladders)).isEmpty();
    }
}
