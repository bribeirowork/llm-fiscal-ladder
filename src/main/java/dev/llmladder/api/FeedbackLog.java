package dev.llmladder.api;

import dev.llmladder.core.Json;
import dev.llmladder.gateway.PiiVault;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/** Erro real vira candidato ao golden set depois de revisão. PII sai antes de gravar (minimização). */
public final class FeedbackLog {
    private final Path file;

    public FeedbackLog(Path file) {
        this.file = file;
    }

    public synchronized void append(String tenant, String traceId, String question, String problem) {
        PiiVault vault = new PiiVault();
        String line = Json.write(Map.of("at", Instant.now().toString(), "tenant", tenant, "trace_id", traceId,
                "question", vault.mask(question), "problem", vault.mask(problem)));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
