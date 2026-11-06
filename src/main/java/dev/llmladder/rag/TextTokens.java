package dev.llmladder.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TextTokens {
    private static final Set<String> STOPWORDS = Set.of(
            "de", "da", "do", "das", "dos", "em", "na", "no", "nas", "nos", "um", "uma", "as", "os",
            "que", "por", "para", "com", "se", "ao", "qual", "quais", "como", "foi", "ser", "sobre", "minha", "meu");

    private TextTokens() {}

    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalize(text).split("[^a-z0-9]+")) {
            if (token.length() > 1 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
