package dev.llmladder.observability;

import dev.llmladder.core.Demand;
import dev.llmladder.llm.Usage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Registro de uma tarefa inteira: etapas, tokens, custo e latência. Usa a API do OpenTelemetry, que é
 * no-op sem SDK; basta anexar o agente Java do OTel para exportar os spans sem mudar código.
 */
public final class TaskTrace {
    public record Step(String name, long latencyMs, String model, long tokens, double costUsd, boolean cached, Map<String, String> attributes) {}

    public record View(String id, Demand demand, String tenant, Instant startedAt, double totalCostUsd, long totalTokens, int llmCalls, List<Step> steps) {}

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("llm-fiscal-ladder");

    private final String id = UUID.randomUUID().toString();
    private final Demand demand;
    private final String tenant;
    private final Instant startedAt = Instant.now();
    private final List<Step> steps = new ArrayList<>();
    private double costUsd;
    private long tokens;
    private int llmCalls;

    public TaskTrace(Demand demand, String tenant) {
        this.demand = demand;
        this.tenant = tenant;
    }

    public String id() {
        return id;
    }

    public <T> T step(String name, Supplier<T> body) {
        Span span = TRACER.spanBuilder(name).setAttribute("ladder.task_id", id).startSpan();
        long start = System.nanoTime();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
            add(new Step(name, (System.nanoTime() - start) / 1_000_000, null, 0, 0, false, Map.of()));
        }
    }

    public synchronized void recordLlm(String model, Usage usage, double cost, long latencyMs, boolean cached) {
        llmCalls += cached ? 0 : 1;
        costUsd += cost;
        tokens += usage.total();
        steps.add(new Step("llm", latencyMs, model, usage.total(), cost, cached, Map.of()));
        Span.current().setAttribute("gen_ai.request.model", model);
        Span.current().setAttribute("gen_ai.usage.input_tokens", usage.inputTokens());
        Span.current().setAttribute("gen_ai.usage.output_tokens", usage.outputTokens());
    }

    public void event(String name, Map<String, String> attributes) {
        add(new Step(name, 0, null, 0, 0, false, attributes));
    }

    private synchronized void add(Step step) {
        steps.add(step);
    }

    public synchronized long tokens() {
        return tokens;
    }

    public synchronized double costUsd() {
        return costUsd;
    }

    public synchronized View view() {
        return new View(id, demand, tenant, startedAt, costUsd, tokens, llmCalls, List.copyOf(steps));
    }
}
