package dev.llmladder.guard;

import dev.llmladder.rag.TextTokens;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Heurística de prompt injection (OWASP LLM01). Não é defesa suficiente sozinha: a defesa de verdade é
 * tratar conteúdo recuperado como dado e limitar o que o agente pode fazer. Isto só tira do contexto o óbvio.
 */
public final class InjectionScanner {
    private static final List<Pattern> SIGNALS = List.of(
            Pattern.compile("ignore (as |todas as |suas )?(instrucoes|regras)"),
            Pattern.compile("ignore (all |previous |the )+instructions"),
            Pattern.compile("desconsidere (as |todas as )?(instrucoes|regras)"),
            Pattern.compile("voce agora e"),
            Pattern.compile("system prompt"),
            Pattern.compile("revele (o|seu|as) "));

    private InjectionScanner() {}

    public static boolean isSuspicious(String text) {
        String normalized = TextTokens.normalize(text);
        return SIGNALS.stream().anyMatch(p -> p.matcher(normalized).find());
    }
}
