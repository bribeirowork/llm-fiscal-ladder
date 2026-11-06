package dev.llmladder.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Adaptador real. O cliente usa ANTHROPIC_API_KEY por padrão; a URL base pode ser trocada em testes. */
public final class AnthropicLlmClient implements LlmClient {
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;

    public AnthropicLlmClient(AnthropicClient client) {
        this.client = client;
    }

    public static AnthropicLlmClient fromEnv() {
        return new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv());
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        try {
            return toResponse(client.messages().create(toParams(request)));
        } catch (RateLimitException e) {
            throw new LlmUnavailableException("limite de requisições do provedor atingido", e);
        } catch (AnthropicServiceException e) {
            throw new LlmUnavailableException("erro do provedor: HTTP " + e.statusCode(), e);
        } catch (AnthropicIoException e) {
            throw new LlmUnavailableException("falha de conexão com o provedor", e);
        } catch (AnthropicException e) {
            throw new LlmUnavailableException("falha ao chamar o provedor", e);
        }
    }

    MessageCreateParams toParams(LlmRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));

        for (LlmMessage message : request.messages()) {
            switch (message) {
                case LlmMessage.User user -> params.addUserMessage(user.text());
                case LlmMessage.Assistant assistant -> params.addMessage(((Message) assistant.raw()).toParam());
                case LlmMessage.ToolResults results -> params.addMessage(toToolResultMessage(results));
            }
        }
        request.tools().stream().map(AnthropicLlmClient::toTool).forEach(params::addTool);

        OutputConfig.Builder output = OutputConfig.builder().effort(toEffort(request.effort()));
        if (request.jsonSchema() != null) {
            JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
            sorted(request.jsonSchema()).forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
            output.format(JsonOutputFormat.builder().schema(schema.build()).build());
        }
        params.outputConfig(output.build());

        // Num loop de agente, recusa escala para humano em vez de trocar de modelo no meio da sessão.
        boolean supportsFallback = request.model().equals("claude-opus-5-5") || request.model().equals("claude-sonnet-5-5");
        if (supportsFallback && request.tools().isEmpty()) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params.build();
    }

    /** Ordem estável: Map.of muda entre execuções da JVM, e um byte diferente invalida o prompt cache. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sorted(Map<String, Object> value) {
        Map<String, Object> result = new TreeMap<>();
        value.forEach((k, v) -> result.put(k, v instanceof Map<?, ?> nested ? sorted((Map<String, Object>) nested) : v));
        return result;
    }

    private static MessageParam toToolResultMessage(LlmMessage.ToolResults results) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ToolResult result : results.results()) {
            blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(result.toolCallId())
                    .content(result.content())
                    .isError(result.error())
                    .build()));
        }
        return MessageParam.ofUserOfContentBlockParams(blocks);
    }

    @SuppressWarnings("unchecked")
    private static Tool toTool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        sorted((Map<String, Object>) schema.get("properties"))
                .forEach((name, definition) -> properties.putAdditionalProperty(name, JsonValue.from(definition)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(((List<String>) schema.get("required")).stream().sorted().toList())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static OutputConfig.Effort toEffort(LlmRequest.Effort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
        };
    }

    private static LlmResponse toResponse(Message message) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(use -> toolCalls.add(new ToolCall(use.id(), use.name(), toJsonNode(use._input()))));
        }
        var usage = message.usage();
        Usage mapped = new Usage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
        return new LlmResponse(message.model().asString(), text.toString(), toolCalls, toStop(message), mapped, message);
    }

    private static JsonNode toJsonNode(JsonValue value) {
        return Json.MAPPER.valueToTree(value.convert(Object.class));
    }

    private static LlmResponse.Stop toStop(Message message) {
        StopReason reason = message.stopReason().orElse(null);
        if (StopReason.END_TURN.equals(reason)) return LlmResponse.Stop.END_TURN;
        if (StopReason.TOOL_USE.equals(reason)) return LlmResponse.Stop.TOOL_USE;
        if (StopReason.MAX_TOKENS.equals(reason)) return LlmResponse.Stop.MAX_TOKENS;
        if (StopReason.REFUSAL.equals(reason)) return LlmResponse.Stop.REFUSAL;
        return LlmResponse.Stop.OTHER;
    }
}
