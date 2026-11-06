package dev.llmladder.gateway;

import dev.llmladder.core.Json;
import dev.llmladder.core.Principal;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.LlmMessage;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.ModelCatalog;
import dev.llmladder.llm.ToolResult;
import dev.llmladder.llm.Usage;
import dev.llmladder.observability.TaskTrace;
import dev.llmladder.observability.UsageLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ponto único de saída para o modelo, como um API gateway aplicado ao tráfego de LLM:
 * limite de tamanho e de taxa, orçamento por tarefa, mascaramento de PII, cache e contabilização.
 */
public final class AiGateway {
    public record Limits(int requestsPerMinute, long maxTokensPerTask, int maxInputChars, int cacheEntries, long cacheTtlMs) {
        public static Limits defaults() {
            return new Limits(600, 60_000, 60_000, 1_000, 3_600_000);
        }
    }

    private record CachedResponse(LlmResponse response, long storedAtMs) {}

    private final LlmClient client;
    private final ModelCatalog catalog;
    private final Limits limits;
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final Map<String, CachedResponse> cache;
    private final UsageLedger usage;

    public AiGateway(LlmClient client, ModelCatalog catalog, Limits limits, Clock clock) {
        this.client = client;
        this.catalog = catalog;
        this.limits = limits;
        this.clock = clock;
        this.rateLimiter = new RateLimiter(limits.requestsPerMinute(), clock);
        this.usage = new UsageLedger(clock);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
                return size() > limits.cacheEntries();
            }
        });
    }

    public ModelCatalog catalog() {
        return catalog;
    }

    public UsageLedger usage() {
        return usage;
    }

    public LlmResponse call(LlmRequest request, Principal principal, PiiVault vault, TaskTrace trace) {
        if (trace.tokens() > limits.maxTokensPerTask()) {
            throw new GatewayException(GatewayException.Reason.BUDGET_EXCEEDED, "orçamento de tokens da tarefa esgotado");
        }
        LlmRequest masked = request.withMessages(mask(request.messages(), vault));
        int inputChars = masked.messages().stream().mapToInt(AiGateway::size).sum();
        if (inputChars > limits.maxInputChars()) {
            throw new GatewayException(GatewayException.Reason.INPUT_TOO_LARGE, "entrada acima do limite");
        }

        // Só requisições sem ferramentas são cacheáveis; a chave inclui o tenant para não cruzar clientes.
        String cacheKey = masked.tools().isEmpty() ? cacheKey(masked, principal.tenant()) : null;
        if (cacheKey != null) {
            CachedResponse hit = cache.get(cacheKey);
            if (hit != null && clock.millis() - hit.storedAtMs() < limits.cacheTtlMs()) {
                trace.recordLlm(hit.response().model(), Usage.ZERO, 0, 0, true);
                usage.recordCacheHit(principal.tenant(), hit.response().model());
                return hit.response();
            }
        }

        if (!rateLimiter.tryAcquire(principal.tenant())) {
            throw new GatewayException(GatewayException.Reason.RATE_LIMITED, "limite de requisições do tenant atingido");
        }
        long start = System.nanoTime();
        LlmResponse response = client.complete(masked);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        double cost = catalog.estimateCostUsd(response.model(), response.usage());
        trace.recordLlm(response.model(), response.usage(), cost, latencyMs, false);
        usage.recordCall(principal.tenant(), response.model(), response.usage(), cost);

        if (cacheKey != null && response.stop() == LlmResponse.Stop.END_TURN) {
            cache.put(cacheKey, new CachedResponse(response, clock.millis()));
        }
        return response;
    }

    private static List<LlmMessage> mask(List<LlmMessage> messages, PiiVault vault) {
        List<LlmMessage> result = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            result.add(switch (message) {
                case LlmMessage.User user -> new LlmMessage.User(vault.mask(user.text()));
                case LlmMessage.Assistant assistant -> assistant;
                case LlmMessage.ToolResults results -> new LlmMessage.ToolResults(results.results().stream()
                        .map(r -> new ToolResult(r.toolCallId(), vault.mask(r.content()), r.error()))
                        .toList());
            });
        }
        return result;
    }

    private static int size(LlmMessage message) {
        return switch (message) {
            case LlmMessage.User user -> user.text().length();
            case LlmMessage.Assistant assistant -> assistant.text().length();
            case LlmMessage.ToolResults results -> results.results().stream().mapToInt(r -> r.content().length()).sum();
        };
    }

    private static String cacheKey(LlmRequest request, String tenant) {
        String material = tenant + "\n" + request.task() + "\n" + request.model() + "\n" + request.system()
                + "\n" + Json.write(request.jsonSchema()) + "\n" + request.messages();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
