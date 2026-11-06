package dev.llmladder.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.llmladder.core.Json;
import dev.llmladder.core.Principal;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.fiscal.SefazPort;
import dev.llmladder.rag.KnowledgeBase;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servidor MCP mínimo (JSON-RPC 2.0 por stdio). Expõe somente ferramentas de leitura: quem liga um cliente
 * MCP aqui não ganha acesso a reenvio nem a cancelamento, que dependem das políticas do agente.
 */
public final class McpStdioServer {
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String LATEST_VERSION = "2025-06-18";

    private final SefazPort sefaz;
    private final KnowledgeBase knowledge;
    private final Principal principal;

    public McpStdioServer(SefazPort sefaz, KnowledgeBase knowledge, Principal principal) {
        this.sefaz = sefaz;
        this.knowledge = knowledge;
        this.principal = principal;
    }

    public void serve(BufferedReader in, PrintStream out) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String response = handle(line);
            if (response != null) {
                out.println(response);
                out.flush();
            }
        }
    }

    /** @return a resposta serializada, ou null para notificações. */
    public String handle(String line) {
        JsonNode request;
        try {
            request = Json.read(line);
        } catch (IllegalArgumentException e) {
            return error(null, -32700, "JSON inválido");
        }
        JsonNode id = request.get("id");
        if (id == null) {
            return null;
        }
        try {
            return switch (request.path("method").asText()) {
                case "initialize" -> result(id, initialize(request.path("params")));
                case "ping" -> result(id, Json.MAPPER.createObjectNode());
                case "tools/list" -> result(id, Json.MAPPER.valueToTree(Map.of("tools", tools())));
                case "tools/call" -> result(id, call(request.path("params")));
                default -> error(id, -32601, "método não suportado");
            };
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private static JsonNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText();
        String version = SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION;
        return Json.MAPPER.valueToTree(Map.of(
                "protocolVersion", version,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "llm-fiscal-ladder", "version", "0.1.0")));
    }

    private static List<Map<String, Object>> tools() {
        return List.of(
                Map.of("name", "consultar_situacao", "description", "Consulta a situação de uma NF-e pela chave de acesso. Somente leitura.",
                        "annotations", Map.of("readOnlyHint", true),
                        "inputSchema", Map.of("type", "object", "properties", Map.of("chave", Map.of("type", "string")), "required", List.of("chave"))),
                Map.of("name", "buscar_manual", "description", "Busca trechos dos manuais fiscais visíveis para o tenant.",
                        "annotations", Map.of("readOnlyHint", true),
                        "inputSchema", Map.of("type", "object", "properties", Map.of("consulta", Map.of("type", "string")), "required", List.of("consulta"))));
    }

    private JsonNode call(JsonNode params) {
        JsonNode arguments = params.path("arguments");
        String text = switch (params.path("name").asText()) {
            case "consultar_situacao" -> {
                String key = arguments.path("chave").asText();
                if (!AccessKey.isValid(key)) {
                    yield null;
                }
                SefazPort.Status status = sefaz.consult(new AccessKey(key));
                yield "{\"situacao\":\"" + status.state() + "\"}";
            }
            case "buscar_manual" -> knowledge.search(arguments.path("consulta").asText(), principal, 3).stream()
                    .map(c -> c.id() + ": " + c.text())
                    .collect(Collectors.joining("\n"));
            default -> throw new IllegalArgumentException("ferramenta desconhecida");
        };
        ObjectNode result = Json.MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text == null ? "chave de acesso inválida" : text);
        result.put("isError", text == null);
        return result;
    }

    private static String result(JsonNode id, JsonNode result) {
        ObjectNode response = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return Json.write(response);
    }

    private static String error(JsonNode id, int code, String message) {
        ObjectNode response = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return Json.write(response);
    }
}
