package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.core.Json;
import dev.llmladder.eval.Samples;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ladder.feedback-file=target/test-candidates.jsonl")
class ApiTest {
    private static final String ANALYST = "demo-analista-acme";
    private static final String APPROVER = "demo-aprovador-acme";
    private static final String OTHER_TENANT = "demo-analista-globex";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String apiKey, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (apiKey != null) {
            request.header("X-Api-Key", apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requiresApiKey() throws Exception {
        assertThat(send("POST", "/v1/answers", null, Map.of("question", "oi")).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/v1/answers", "errada", Map.of("question", "oi")).statusCode()).isEqualTo(401);
    }

    @Test
    void healthIsPublic() throws Exception {
        assertThat(send("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void answersWithCitation() throws Exception {
        var response = send("POST", "/v1/answers", ANALYST, Map.of("question", "O que significa a rejeição 204?"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.read(response.body()).path("citations").get(0).path("docId").asText()).isEqualTo("manual-rejeicoes");
    }

    @Test
    void analyzesDocumentAndRejectsInvalidXml() throws Exception {
        var ok = send("POST", "/v1/documents/analyze", ANALYST, Map.of("xml", Samples.xml(Samples.key(301), "225", "Falha no Schema XML")));
        var bad = send("POST", "/v1/documents/analyze", ANALYST, Map.of("xml", "<quebrado"));

        assertThat(Json.read(ok.body()).path("category").asText()).isEqualTo("SCHEMA");
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    @Test
    void fullFlowFromTimeoutToApprovedCancellation() throws Exception {
        String key = Samples.key(302).value();

        JsonNode resolved = Json.read(send("POST", "/v1/incidents", ANALYST,
                Map.of("type", "TIMEOUT", "accessKey", key, "description", "Timeout na autorização")).body());
        assertThat(resolved.path("status").asText()).isEqualTo("RESOLVIDO");

        JsonNode pending = Json.read(send("POST", "/v1/incidents", ANALYST,
                Map.of("type", "CANCELAMENTO", "accessKey", key, "description", "Erro no valor")).body());
        assertThat(pending.path("status").asText()).isEqualTo("AGUARDANDO_APROVACAO");
        String approval = pending.path("approvalId").asText();

        assertThat(send("POST", "/v1/approvals/" + approval + "/approve", ANALYST, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/approvals/" + approval + "/approve", OTHER_TENANT, null).statusCode()).isEqualTo(404);

        var approved = send("POST", "/v1/approvals/" + approval + "/approve", APPROVER, null);
        assertThat(Json.read(approved.body()).path("status").asText()).isEqualTo("EXECUTADA");

        String traceId = pending.path("traceId").asText();
        assertThat(send("GET", "/v1/traces/" + traceId, ANALYST, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/v1/traces/" + traceId, OTHER_TENANT, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/audit", OTHER_TENANT, null).body()).isEqualTo("[]");
        assertThat(send("GET", "/v1/audit", ANALYST, null).body()).doesNotContain(key);
    }

    @Test
    void feedbackIsAccepted() throws Exception {
        var response = send("POST", "/v1/feedback", ANALYST, Map.of("traceId", "t1", "question", "CPF 123.456.789-09", "problem", "citou fonte errada"));

        assertThat(response.statusCode()).isEqualTo(202);
    }
}
