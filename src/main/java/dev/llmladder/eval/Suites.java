package dev.llmladder.eval;

import dev.llmladder.Platform;
import dev.llmladder.agent.AgentOutcome;
import dev.llmladder.agent.IncidentType;
import dev.llmladder.core.Demand;
import dev.llmladder.core.Principal;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.rag.Answer;
import dev.llmladder.rag.Answerer;
import dev.llmladder.workflow.WorkflowResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Liga cada suíte ao sistema e define qual degrau representa cada demanda. */
public final class Suites {
    public static final String BUSINESS = "business";
    public static final String PROCESS = "process";
    public static final String AUTOMATION = "automation";

    private final LlmClient client;
    private final Platform platform;
    private final Map<String, Map<String, Double>> criteria = EvalRunner.loadCriteria();

    public Suites(LlmClient client) {
        this.client = client;
        this.platform = Platform.with(client);
    }

    public Platform platform() {
        return platform;
    }

    private static Principal principal(GoldenCase c) {
        return new Principal("eval", c.tenant(), Set.of());
    }

    public List<LadderRunner.LadderReport> climbAll(List<Grader> extraBusinessGraders) {
        List<Grader> business = new java.util.ArrayList<>(Graders.business());
        business.addAll(extraBusinessGraders);

        var negocio = LadderRunner.climb(Demand.NEGOCIO, BUSINESS, GoldenCase.load(BUSINESS), List.of(
                new LadderRunner.Step(Rung.PROMPT, answering(platform.promptOnly)),
                new LadderRunner.Step(Rung.CONTEXTO, answering(platform.rag))),
                business, criteria.get(BUSINESS));

        var processo = LadderRunner.climb(Demand.PROCESSO, PROCESS, GoldenCase.load(PROCESS), List.of(
                new LadderRunner.Step(Rung.PROMPT, processing(false)),
                new LadderRunner.Step(Rung.CONTEXTO, processing(true))),
                Graders.process(), criteria.get(PROCESS));

        var automacao = LadderRunner.climb(Demand.AUTOMACAO, AUTOMATION, GoldenCase.load(AUTOMATION), List.of(
                new LadderRunner.Step(Rung.CONTEXTO, this::suggestOnly),
                new LadderRunner.Step(Rung.FERRAMENTAS, this::automate)),
                Graders.automation(), criteria.get(AUTOMATION));

        return List.of(negocio, processo, automacao);
    }

    private Function<GoldenCase, Map<String, Object>> answering(Answerer answerer) {
        return c -> {
            Answer answer = answerer.answer(c.input().path("question").asText(), principal(c));
            Map<String, Object> facts = new HashMap<>();
            facts.put("answer", answer.text());
            facts.put("needs_human", answer.needsHuman());
            facts.put("cited_docs", answer.citations().stream().map(Answer.Citation::docId).toList());
            facts.put("sources", answer.citations().stream().map(Answer.Citation::section).toList());
            if (answer.model() != null) {
                facts.put("model", answer.model());
            }
            platform.traces.find(answer.traceId(), c.tenant()).ifPresent(t -> facts.put("cost_usd", t.totalCostUsd()));
            return facts;
        };
    }

    private Function<GoldenCase, Map<String, Object>> processing(boolean withRule) {
        return c -> {
            AccessKey key = Samples.key(c.input().path("number").asInt(1));
            String xml = Samples.xml(key, c.input().path("cStat").asText(), c.input().path("motivo").asText());
            WorkflowResult result = platform.workflow.analyze(xml, principal(c), withRule);
            Map<String, Object> facts = new HashMap<>();
            facts.put("category", result.category().name());
            facts.put("action", result.suggestedAction());
            facts.put("needs_human", result.needsHuman());
            platform.traces.find(result.traceId(), c.tenant()).ifPresent(t -> facts.put("cost_usd", t.totalCostUsd()));
            return facts;
        };
    }

    /** Degrau de contexto aplicado à automação: consegue explicar, mas não consegue agir. */
    private Map<String, Object> suggestOnly(GoldenCase c) {
        Answer answer = platform.rag.answer(c.input().path("description").asText(), principal(c));
        Map<String, Object> facts = new HashMap<>(Map.of("status", "SUGERIDO", "answer", answer.text()));
        platform.traces.find(answer.traceId(), c.tenant()).ifPresent(t -> facts.put("cost_usd", t.totalCostUsd()));
        return facts;
    }

    /** Cada cenário usa uma plataforma nova para o estado da SEFAZ não vazar entre casos. */
    private Map<String, Object> automate(GoldenCase c) {
        Platform scenario = Platform.with(client);
        AccessKey key = Samples.key(c.input().path("number").asInt(1));
        switch (c.input().path("seed").asText("none")) {
            case "authorized_recent" -> scenario.sefaz.seedAuthorized(key, scenario.clock.instant().minus(Duration.ofHours(2)));
            case "authorized_old" -> scenario.sefaz.seedAuthorized(key, scenario.clock.instant().minus(Duration.ofHours(30)));
            default -> { }
        }
        switch (c.input().path("fault").asText("none")) {
            case "lost_response" -> scenario.sefaz.loseNextSubmitResponse();
            case "consult_timeout" -> scenario.sefaz.consultTimesOut(true);
            default -> { }
        }
        IncidentType type = IncidentType.valueOf(c.input().path("type").asText());
        AgentOutcome outcome = scenario.agent.resolve(type, key, c.input().path("description").asText(), principal(c));

        Map<String, Object> facts = new HashMap<>();
        facts.put("status", outcome.status().name());
        facts.put("tools", outcome.toolsCalled());
        facts.put("approval", outcome.approvalId());
        facts.put("submits", scenario.sefaz.submitCalls());
        facts.put("duplicates", scenario.sefaz.duplicates());
        facts.put("cancellations", scenario.sefaz.cancellations());
        scenario.traces.find(outcome.traceId(), c.tenant()).ifPresent(t -> facts.put("cost_usd", t.totalCostUsd()));
        return facts;
    }
}
