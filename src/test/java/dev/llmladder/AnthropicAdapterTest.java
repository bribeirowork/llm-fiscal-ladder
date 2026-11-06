package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.llmladder.core.Json;
import dev.llmladder.llm.AnthropicLlmClient;
import dev.llmladder.llm.LlmMessage;
import dev.llmladder.llm.LlmRequest;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.LlmUnavailableException;
import dev.llmladder.llm.TaskType;
import dev.llmladder.llm.ToolResult;
import dev.llmladder.llm.ToolSpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercita o SDK real contra um servidor HTTP local: valida o contrato sem gastar nem precisar de chave. */
class AnthropicAdapterTest {
    private static final ToolSpec TOOL = new ToolSpec("consultar_situacao", "Consulta",
            Map.of("type", "object", "properties", Map.of("chave", Map.of("type", "string")), "required", List.of("chave"), "additionalProperties", false));

    private HttpServer server;
    private AnthropicLlmClient client;
    private final List<JsonNode> bodies = new ArrayList<>();
    private final List<String> betas = new ArrayList<>();
    private String nextResponse;
    private int nextStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            bodies.add(Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String beta = exchange.getRequestHeaders().getFirst("anthropic-beta");
            betas.add(beta == null ? "" : beta);
            byte[] body = nextResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(nextStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new AnthropicLlmClient(AnthropicOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("chave-de-teste")
                .maxRetries(0)
                .build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String message(String content, String stop) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":%s,
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":3,"cache_creation_input_tokens":0}}
                """.formatted(content, stop);
    }

    private static LlmRequest request(String model, List<ToolSpec> tools, Map<String, Object> schema, List<LlmMessage> messages) {
        return new LlmRequest(TaskType.ANSWER, model, "sistema", messages, tools, schema, 1_024, LlmRequest.Effort.LOW);
    }

    @Test
    void sendsStructuredOutputCacheEffortAndFallback() {
        nextResponse = message("[{\"type\":\"text\",\"text\":\"{}\",\"citations\":null}]", "end_turn");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(), "required", List.of(), "additionalProperties", false);

        LlmResponse response = client.complete(request("claude-opus-5-5", List.of(), schema, List.of(new LlmMessage.User("oi"))));

        JsonNode body = bodies.get(0);
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("system").get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(body.path("fallbacks").asText()).isEqualTo("default");
        assertThat(betas.get(0)).contains("server-side-fallback-2026-07-01");
        assertThat(response.usage().cacheReadTokens()).isEqualTo(3);
        assertThat(response.stop()).isEqualTo(LlmResponse.Stop.END_TURN);
    }

    @Test
    void toolLoopRoundTripKeepsAssistantContentAndDoesNotUseFallback() {
        nextResponse = message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"consultar_situacao\",\"input\":{\"chave\":\"[CHAVE_1]\"}}]", "tool_use");
        List<LlmMessage> messages = new ArrayList<>(List.of(new LlmMessage.User("incidente")));

        LlmResponse first = client.complete(request("claude-opus-5-5", List.of(TOOL), null, messages));

        assertThat(first.stop()).isEqualTo(LlmResponse.Stop.TOOL_USE);
        assertThat(first.toolCalls().get(0).input().path("chave").asText()).isEqualTo("[CHAVE_1]");
        assertThat(bodies.get(0).path("tools").get(0).path("strict").asBoolean()).isTrue();
        assertThat(bodies.get(0).path("tools").get(0).path("input_schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(bodies.get(0).has("fallbacks")).isFalse();

        messages.add(first.asAssistantMessage());
        messages.add(new LlmMessage.ToolResults(List.of(new ToolResult("toolu_1", "{\"situacao\":\"AUTORIZADA\"}", false))));
        nextResponse = message("[{\"type\":\"text\",\"text\":\"feito\",\"citations\":null}]", "end_turn");

        client.complete(request("claude-opus-5-5", List.of(TOOL), null, messages));

        JsonNode sent = bodies.get(1).path("messages");
        assertThat(sent.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(sent.get(1).path("content").get(0).path("signature").asText()).isEqualTo("sig");
        assertThat(sent.get(2).path("content").get(0).path("tool_use_id").asText()).isEqualTo("toolu_1");
    }

    @Test
    void toolSchemaIsSerializedInStableOrderForPromptCaching() {
        nextResponse = message("[{\"type\":\"text\",\"text\":\"ok\",\"citations\":null}]", "end_turn");
        ToolSpec tool = new ToolSpec("t", "d", Map.of("type", "object",
                "properties", Map.of("zeta", Map.of("type", "string"), "alfa", Map.of("type", "string")),
                "required", List.of("zeta", "alfa"), "additionalProperties", false));

        client.complete(request("claude-opus-5-5", List.of(tool), null, List.of(new LlmMessage.User("oi"))));

        JsonNode schema = bodies.get(0).path("tools").get(0).path("input_schema");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("alfa", "zeta");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("alfa");
    }

    @Test
    void refusalIsSurfaced() {
        nextResponse = message("[]", "refusal");

        assertThat(client.complete(request("claude-opus-5-5", List.of(), null, List.of(new LlmMessage.User("oi")))).stop())
                .isEqualTo(LlmResponse.Stop.REFUSAL);
    }

    @Test
    void providerErrorBecomesUnavailable() {
        nextStatus = 500;
        nextResponse = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"falhou\"}}";

        assertThatThrownBy(() -> client.complete(request("claude-haiku-5-5", List.of(), null, List.of(new LlmMessage.User("oi")))))
                .isInstanceOf(LlmUnavailableException.class);
    }
}
