package dev.llmladder.eval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.llmladder.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record GoldenCase(String id, String tenant, JsonNode input, JsonNode expect) {

    /** JSONL: uma linha por caso; erros de produção viram novas linhas aqui. */
    public static List<GoldenCase> load(String suite) {
        String resource = "evals/" + suite + ".jsonl";
        try (InputStream in = GoldenCase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("golden set ausente: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank())
                    .map(Json::read)
                    .map(n -> new GoldenCase(n.path("id").asText(), n.path("tenant").asText("public"), n.path("input"), n.path("expect")))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
