package dev.llmladder.rag;

import dev.llmladder.core.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Índice BM25 em memória: determinístico e sem dependências; troque por um índice vetorial quando o eval
 * de retrieval pedir. A ACL é aplicada ANTES do ranking: o que o tenant não pode ver não vira candidato.
 */
public final class KnowledgeBase {
    public static final String PUBLIC = "public";
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final List<Chunk> chunks = new CopyOnWriteArrayList<>();

    public static KnowledgeBase fromClasspath() {
        KnowledgeBase base = new KnowledgeBase();
        for (String name : read("knowledge/index.txt").split("\\R")) {
            if (!name.isBlank()) {
                base.addAll(parse(read("knowledge/" + name.strip())));
            }
        }
        return base;
    }

    public void addAll(Collection<Chunk> newChunks) {
        chunks.addAll(newChunks);
    }

    public List<Chunk> search(String query, Principal principal, int limit) {
        List<Chunk> visible = chunks.stream()
                .filter(c -> c.tenant().equals(PUBLIC) || c.tenant().equals(principal.tenant()))
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<Chunk, List<String>> tokensByChunk = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (Chunk chunk : visible) {
            List<String> tokens = TextTokens.tokenize(chunk.section() + " " + chunk.text());
            tokensByChunk.put(chunk, tokens);
            totalLength += tokens.size();
            tokens.stream().distinct().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
        }
        double averageLength = totalLength / visible.size();
        List<String> queryTokens = TextTokens.tokenize(query).stream().distinct().toList();

        Map<Chunk, Double> scores = new HashMap<>();
        for (Chunk chunk : visible) {
            double score = score(tokensByChunk.get(chunk), queryTokens, documentFrequency, visible.size(), averageLength);
            if (score > 0) {
                scores.put(chunk, score);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Chunk, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().id()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double score(List<String> tokens, List<String> query, Map<String, Integer> df, int total, double avgLength) {
        double score = 0;
        for (String term : query) {
            long frequency = tokens.stream().filter(term::equals).count();
            if (frequency == 0) {
                continue;
            }
            int n = df.getOrDefault(term, 0);
            double idf = Math.log(1 + (total - n + 0.5) / (n + 0.5));
            score += idf * frequency * (K1 + 1) / (frequency + K1 * (1 - B + B * tokens.size() / avgLength));
        }
        return score;
    }

    /** Documento: cabeçalho {@code chave: valor} entre linhas "---", depois seções iniciadas por "## ". */
    static List<Chunk> parse(String document) {
        String[] parts = document.split("(?m)^---\\s*$", 3);
        Map<String, String> header = new HashMap<>();
        for (String line : parts[1].split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                header.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        List<Chunk> result = new ArrayList<>();
        int index = 1;
        for (String section : parts[2].split("(?m)^## ")) {
            if (section.isBlank()) {
                continue;
            }
            int lineEnd = section.indexOf('\n');
            String title = section.substring(0, lineEnd).strip();
            String body = section.substring(lineEnd + 1).strip();
            String id = header.get("id") + "#" + index++;
            result.add(new Chunk(id, header.get("id"), header.get("title"), title, header.get("tenant"), header.get("source"), body));
        }
        return result;
    }

    private static String read(String resource) {
        try (InputStream in = KnowledgeBase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("recurso ausente: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
