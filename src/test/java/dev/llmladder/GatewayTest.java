package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmladder.core.Demand;
import dev.llmladder.core.Principal;
import dev.llmladder.eval.Samples;
import dev.llmladder.gateway.AiGateway;
import dev.llmladder.gateway.GatewayException;
import dev.llmladder.gateway.PiiVault;
import dev.llmladder.llm.LlmClient;
import dev.llmladder.llm.LlmMessage;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.ModelCatalog;
import dev.llmladder.llm.TaskType;
import dev.llmladder.llm.Usage;
import dev.llmladder.observability.TaskTrace;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewayTest {
    private static final Principal ACME = new Principal("ana", "acme", Set.of());
    private static final Principal GLOBEX = new Principal("gil", "globex", Set.of());

    private static final class RecordingLlm implements LlmClient {
        final List<LlmRequest> requests = new ArrayList<>();

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            return new LlmResponse(request.model(), "ok", List.of(), LlmResponse.Stop.END_TURN, new Usage(1_000, 500, 0, 0), null);
        }
    }

    private static LlmRequest request(String text) {
        return new LlmRequest(TaskType.ANSWER, "claude-haiku-5-5", "sistema", List.of(new LlmMessage.User(text)),
                List.of(), Map.of("type", "object"), 100, LlmRequest.Effort.LOW);
    }

    private static AiGateway gateway(LlmClient client, AiGateway.Limits limits) {
        return new AiGateway(client, ModelCatalog.anthropicDefaults(), limits, Clock.systemUTC());
    }

    private static TaskTrace trace(String tenant) {
        return new TaskTrace(Demand.NEGOCIO, tenant);
    }

    @Test
    void masksPersonalDataBeforeReachingTheModel() {
        RecordingLlm llm = new RecordingLlm();
        String key = Samples.key(1).value();
        String input = "CPF 123.456.789-09, CNPJ 11.222.333/0001-81, email ana@acme.com.br, chave " + key;
        PiiVault vault = new PiiVault();

        gateway(llm, AiGateway.Limits.defaults()).call(request(input), ACME, vault, trace("acme"));

        String sent = ((LlmMessage.User) llm.requests.get(0).messages().get(0)).text();
        assertThat(sent).doesNotContain("123.456.789-09", "11.222.333/0001-81", "ana@acme.com.br", key);
        assertThat(sent).contains("[CPF_1]", "[CNPJ_1]", "[EMAIL_1]", "[CHAVE_1]");
        assertThat(vault.unmask("[CHAVE_1]")).isEqualTo(key);
    }

    @Test
    void cacheHitAvoidsTheCallAndCostsNothing() {
        RecordingLlm llm = new RecordingLlm();
        AiGateway gateway = gateway(llm, AiGateway.Limits.defaults());
        gateway.call(request("mesma pergunta"), ACME, new PiiVault(), trace("acme"));
        TaskTrace second = trace("acme");

        gateway.call(request("mesma pergunta"), ACME, new PiiVault(), second);

        assertThat(llm.requests).hasSize(1);
        assertThat(second.costUsd()).isZero();
    }

    @Test
    void cacheIsIsolatedByTenant() {
        RecordingLlm llm = new RecordingLlm();
        AiGateway gateway = gateway(llm, AiGateway.Limits.defaults());
        gateway.call(request("mesma pergunta"), ACME, new PiiVault(), trace("acme"));
        gateway.call(request("mesma pergunta"), GLOBEX, new PiiVault(), trace("globex"));

        assertThat(llm.requests).hasSize(2);
    }

    @Test
    void rateLimitIsPerTenant() {
        AiGateway gateway = gateway(new RecordingLlm(), new AiGateway.Limits(1, 60_000, 60_000, 10, 1_000));
        gateway.call(request("a"), ACME, new PiiVault(), trace("acme"));

        assertThatThrownBy(() -> gateway.call(request("b"), ACME, new PiiVault(), trace("acme")))
                .isInstanceOf(GatewayException.class);
        gateway.call(request("b"), GLOBEX, new PiiVault(), trace("globex"));
    }

    @Test
    void stopsWhenTaskBudgetIsExhausted() {
        AiGateway gateway = gateway(new RecordingLlm(), new AiGateway.Limits(100, 1_000, 60_000, 10, 1_000));
        TaskTrace task = trace("acme");
        gateway.call(request("a"), ACME, new PiiVault(), task);

        assertThatThrownBy(() -> gateway.call(request("b"), ACME, new PiiVault(), task))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("orçamento");
    }

    @Test
    void rejectsPathologicalInput() {
        AiGateway gateway = gateway(new RecordingLlm(), new AiGateway.Limits(100, 1_000, 10, 10, 1_000));

        assertThatThrownBy(() -> gateway.call(request("x".repeat(11)), ACME, new PiiVault(), trace("acme")))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void estimatesCostByModel() {
        ModelCatalog catalog = ModelCatalog.anthropicDefaults();

        assertThat(catalog.estimateCostUsd("claude-opus-5-5", new Usage(1_000_000, 1_000_000, 0, 0))).isEqualTo(24.0);
        assertThat(catalog.judgeFor("claude-sonnet-5-5")).isNotEqualTo("claude-sonnet-5-5");
    }
}
