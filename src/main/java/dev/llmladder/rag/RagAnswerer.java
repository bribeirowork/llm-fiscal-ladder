package dev.llmladder.rag;

import dev.llmladder.context.ConversationStore;
import dev.llmladder.core.Demand;
import dev.llmladder.core.Principal;
import dev.llmladder.gateway.AiGateway;
import dev.llmladder.gateway.PiiVault;
import dev.llmladder.guard.InjectionScanner;
import dev.llmladder.guard.OutputGuard;
import dev.llmladder.llm.LlmMessage;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.TaskType;
import dev.llmladder.observability.TaskTrace;
import dev.llmladder.observability.TraceStore;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Degrau 2 / assistente v1: recupera com ACL, responde citando a fonte e usa cascata de modelos. */
public final class RagAnswerer implements Answerer {
    private static final int TOP_K = 4;
    private static final double MIN_CONFIDENCE = 0.7;

    private final KnowledgeBase knowledge;
    private final AiGateway gateway;
    private final TraceStore traces;
    private final ConversationStore conversations;

    public RagAnswerer(KnowledgeBase knowledge, AiGateway gateway, TraceStore traces, ConversationStore conversations) {
        this.knowledge = knowledge;
        this.gateway = gateway;
        this.traces = traces;
        this.conversations = conversations;
    }

    @Override
    public Answer answer(String question, Principal principal) {
        return answer(question, principal, null);
    }

    /** @param conversationId mantém o contexto entre perguntas, mesmo que cada uma seja atendida por outro modelo. */
    public Answer answer(String question, Principal principal, String conversationId) {
        ConversationStore.Conversation conversation = conversations.open(principal.tenant(), conversationId);
        TaskTrace trace = new TaskTrace(Demand.NEGOCIO, principal.tenant());
        traces.save(trace);
        PiiVault vault = conversation.vault();
        String retrievalQuery = (conversation.lastQuestion() + " " + question).strip();

        List<Chunk> context = trace.step("retrieval", () -> retrieve(retrievalQuery, principal, trace));
        if (context.isEmpty()) {
            return new Answer("Não encontrei fonte para responder a essa pergunta.", List.of(), 0,
                    null, true, "sem fonte recuperada", trace.id());
        }

        Map<String, Chunk> byId = context.stream().collect(Collectors.toMap(Chunk::id, Function.identity()));
        LlmRequest base = new LlmRequest(TaskType.ANSWER, "", Prompts.ANSWER_SYSTEM,
                List.of(new LlmMessage.User(conversation.renderHistory() + renderPrompt(question, context))),
                List.of(), Prompts.ANSWER_SCHEMA, 2_048, LlmRequest.Effort.LOW);

        String lastReason = "sem resposta válida";
        Answer best = null;
        for (String model : gateway.catalog().answerCascade()) {
            LlmResponse response = gateway.call(base.withModel(model), principal, vault, trace);
            if (response.stop() != LlmResponse.Stop.END_TURN) {
                lastReason = "modelo encerrou com " + response.stop();
                continue;
            }
            var verdict = OutputGuard.checkAnswer(response.text(), byId.keySet());
            if (!verdict.ok()) {
                lastReason = String.join("; ", verdict.violations());
                trace.event("guardrail.saida", Map.of("violacoes", lastReason));
                continue;
            }
            var parsed = verdict.value().orElseThrow();
            List<Answer.Citation> citations = parsed.citations().stream()
                    .map(byId::get)
                    .map(c -> new Answer.Citation(c.id(), c.docId(), c.title(), c.section(), c.source()))
                    .toList();
            boolean confident = parsed.confidence() >= MIN_CONFIDENCE && !citations.isEmpty();
            best = new Answer(vault.unmask(parsed.answer()), citations, parsed.confidence(), response.model(),
                    !confident, confident ? null : "confiança baixa ou sem citação", trace.id());
            if (confident) {
                conversation.record(question, best.text(), best.model());
                return best;
            }
            lastReason = best.reason();
            trace.event("cascata.escalou", Map.of("de", model));
        }
        return best != null ? best : new Answer("Não foi possível gerar uma resposta confiável.", List.of(), 0, null, true, lastReason, trace.id());
    }

    private List<Chunk> retrieve(String question, Principal principal, TaskTrace trace) {
        List<Chunk> found = knowledge.search(question, principal, TOP_K);
        List<Chunk> clean = found.stream().filter(c -> !InjectionScanner.isSuspicious(c.text())).toList();
        if (clean.size() < found.size()) {
            trace.event("guardrail.quarentena", Map.of("trechos", String.valueOf(found.size() - clean.size())));
        }
        return clean;
    }

    private static String renderPrompt(String question, List<Chunk> context) {
        String documents = context.stream()
                .map(c -> "<documento id=\"%s\" titulo=\"%s\">\n%s: %s\n</documento>".formatted(c.id(), c.title(), c.section(), c.text()))
                .collect(Collectors.joining("\n"));
        return documents + "\n<pergunta>" + question + "</pergunta>";
    }
}
