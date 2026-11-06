package dev.llmladder.rag;

import dev.llmladder.core.Demand;
import dev.llmladder.core.Principal;
import dev.llmladder.gateway.AiGateway;
import dev.llmladder.gateway.PiiVault;
import dev.llmladder.guard.OutputGuard;
import dev.llmladder.llm.LlmMessage;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.TaskType;
import dev.llmladder.observability.TaskTrace;
import dev.llmladder.observability.TraceStore;
import java.util.List;
import java.util.Set;

/** Degrau 1: só prompt. Existe para o eval medir se o degrau 2 se paga. */
public final class PromptOnlyAnswerer implements Answerer {
    private final AiGateway gateway;
    private final TraceStore traces;

    public PromptOnlyAnswerer(AiGateway gateway, TraceStore traces) {
        this.gateway = gateway;
        this.traces = traces;
    }

    @Override
    public Answer answer(String question, Principal principal) {
        TaskTrace trace = new TaskTrace(Demand.NEGOCIO, principal.tenant());
        traces.save(trace);
        PiiVault vault = new PiiVault();
        String model = gateway.catalog().answerCascade().get(0);
        LlmRequest request = new LlmRequest(TaskType.ANSWER, model, Prompts.ANSWER_SYSTEM,
                List.of(new LlmMessage.User("<pergunta>" + question + "</pergunta>")),
                List.of(), Prompts.ANSWER_SCHEMA, 1_024, LlmRequest.Effort.LOW);
        LlmResponse response = gateway.call(request, principal, vault, trace);
        var verdict = OutputGuard.checkAnswer(response.text(), Set.of());
        var parsed = verdict.value().orElse(new OutputGuard.ParsedAnswer("", List.of(), 0));
        return new Answer(vault.unmask(parsed.answer()), List.of(), parsed.confidence(), response.model(),
                !verdict.ok(), String.join("; ", verdict.violations()), trace.id());
    }
}
