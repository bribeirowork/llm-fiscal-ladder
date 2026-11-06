package dev.llmladder.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subconjunto ilustrativo de códigos cStat, usado para detectar código inventado.
 * A fonte oficial é o Manual de Orientação do Contribuinte (MOC); num sistema real a tabela vem de lá.
 */
public final class StatusCodeTable {
    private static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("100", "Autorizado o uso da NF-e"),
            Map.entry("101", "Cancelamento de NF-e homologado"),
            Map.entry("108", "Serviço paralisado momentaneamente"),
            Map.entry("110", "Uso denegado"),
            Map.entry("135", "Evento registrado e vinculado a NF-e"),
            Map.entry("204", "Duplicidade de NF-e"),
            Map.entry("217", "NF-e não consta na base de dados da SEFAZ"),
            Map.entry("225", "Falha no schema XML"),
            Map.entry("420", "Cancelamento para NF-e já cancelada"),
            Map.entry("502", "Erro na chave de acesso"),
            Map.entry("539", "Duplicidade de NF-e com diferença na chave de acesso"));

    private static final Pattern CODE_MENTION = Pattern.compile(
            "(?iu)(?:c[oó]digos?|cstat|rejei[cç][aã]o|status)\\D{0,12}(\\d{3})\\b");

    private StatusCodeTable() {}

    public static Optional<String> describe(String code) {
        return Optional.ofNullable(CODES.get(code));
    }

    /** Códigos citados no texto que não existem na tabela. */
    public static List<String> unknownCodesIn(String text) {
        List<String> unknown = new ArrayList<>();
        Matcher matcher = CODE_MENTION.matcher(text);
        while (matcher.find()) {
            if (!CODES.containsKey(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        return unknown;
    }
}
