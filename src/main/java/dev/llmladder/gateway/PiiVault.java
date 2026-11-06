package dev.llmladder.gateway;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimização (LGPD): troca dados pessoais por marcadores antes do modelo e devolve o valor real só
 * dentro do sistema. A chave de acesso também entra aqui porque carrega o CNPJ do emitente.
 * Um vault vive o tempo de uma tarefa.
 */
public final class PiiVault {
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("CHAVE", Pattern.compile("(?<!\\d)\\d{44}(?!\\d)"));
        PATTERNS.put("CNPJ", Pattern.compile("(?<![\\d./-])\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}(?!\\d)"));
        PATTERNS.put("CPF", Pattern.compile("(?<![\\d./-])\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?!\\d)"));
        PATTERNS.put("EMAIL", Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"));
        PATTERNS.put("TEL", Pattern.compile("\\(\\d{2}\\)\\s?9?\\d{4}-?\\d{4}"));
    }

    private final Map<String, String> tokenByValue = new HashMap<>();
    private final Map<String, String> valueByToken = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    public synchronized String mask(String text) {
        String result = text;
        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(result);
            StringBuilder masked = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(masked, Matcher.quoteReplacement(tokenFor(entry.getKey(), matcher.group())));
            }
            matcher.appendTail(masked);
            result = masked.toString();
        }
        return result;
    }

    private String tokenFor(String kind, String value) {
        return tokenByValue.computeIfAbsent(value, v -> {
            String token = "[" + kind + "_" + counters.merge(kind, 1, Integer::sum) + "]";
            valueByToken.put(token, v);
            return token;
        });
    }

    public synchronized String unmask(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : valueByToken.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
