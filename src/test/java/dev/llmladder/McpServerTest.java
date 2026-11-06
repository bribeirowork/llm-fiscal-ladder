package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.llmladder.core.Json;
import dev.llmladder.core.Principal;
import dev.llmladder.eval.Samples;
import dev.llmladder.mcp.McpStdioServer;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpServerTest {
    private final Platform platform = Platform.simulated();
    private final McpStdioServer server = new McpStdioServer(platform.sefaz, platform.knowledge, new Principal("mcp", "public", Set.of()));

    @Test
    void negotiatesProtocolVersion() {
        var response = Json.read(server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}"));

        assertThat(response.path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(response.path("result").path("capabilities").has("tools")).isTrue();
    }

    @Test
    void notificationsGetNoResponse() {
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    }

    @Test
    void exposesOnlyReadOnlyTools() {
        var response = Json.read(server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

        assertThat(response.path("result").path("tools").findValuesAsText("name"))
                .containsExactlyInAnyOrder("consultar_situacao", "buscar_manual");
        assertThat(response.toString()).doesNotContain("reenviar", "solicitar_cancelamento");
    }

    @Test
    void callsConsultTool() {
        String key = Samples.key(11).value();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"consultar_situacao\",\"arguments\":{\"chave\":\"" + key + "\"}}}";

        var response = Json.read(server.handle(request));

        assertThat(response.path("result").path("content").get(0).path("text").asText()).contains("NAO_ENCONTRADA");
        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void unknownToolAndMethodAreJsonRpcErrors() {
        var tool = Json.read(server.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"reenviar_nfe\",\"arguments\":{}}}"));
        var method = Json.read(server.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}"));

        assertThat(tool.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(method.path("error").path("code").asInt()).isEqualTo(-32601);
    }
}
