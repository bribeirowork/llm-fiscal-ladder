package dev.llmladder;

import dev.llmladder.agent.ActionLog;
import dev.llmladder.agent.ApprovalService;
import dev.llmladder.agent.AuditLog;
import dev.llmladder.agent.FiscalAgent;
import dev.llmladder.fiscal.SimulatedSefaz;
import dev.llmladder.gateway.AiGateway;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.ModelCatalog;
import dev.llmladder.llm.SimulatedLlmClient;
import dev.llmladder.observability.TraceStore;
import dev.llmladder.rag.KnowledgeBase;
import dev.llmladder.rag.PromptOnlyAnswerer;
import dev.llmladder.rag.RagAnswerer;
import dev.llmladder.workflow.DocumentWorkflow;
import java.time.Clock;

/** Composição sem framework: a mesma montagem serve à API, à CLI de evals e aos testes. */
public final class Platform {
    public final Clock clock;
    public final AiGateway gateway;
    public final KnowledgeBase knowledge;
    public final SimulatedSefaz sefaz;
    public final TraceStore traces = new TraceStore();
    public final AuditLog audit;
    public final ActionLog actions = new ActionLog();
    public final ApprovalService approvals;
    public final PromptOnlyAnswerer promptOnly;
    public final RagAnswerer rag;
    public final DocumentWorkflow workflow;
    public final FiscalAgent agent;

    public Platform(LlmClient client, ModelCatalog catalog, AiGateway.Limits limits, Clock clock) {
        this.clock = clock;
        this.gateway = new AiGateway(client, catalog, limits, clock);
        this.knowledge = KnowledgeBase.fromClasspath();
        this.sefaz = new SimulatedSefaz(clock);
        this.audit = new AuditLog(clock);
        this.approvals = new ApprovalService(sefaz, audit, actions, clock);
        this.promptOnly = new PromptOnlyAnswerer(gateway, traces);
        this.rag = new RagAnswerer(knowledge, gateway, traces);
        this.workflow = new DocumentWorkflow(sefaz, knowledge, gateway, traces);
        this.agent = FiscalAgent.create(gateway, sefaz, knowledge, actions, approvals, audit, traces, clock);
    }

    public static Platform with(LlmClient client) {
        return new Platform(client, ModelCatalog.anthropicDefaults(), AiGateway.Limits.defaults(), Clock.systemUTC());
    }

    public static Platform simulated() {
        return with(new SimulatedLlmClient());
    }
}
