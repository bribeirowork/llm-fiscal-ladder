package dev.llmladder.workflow;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Routing: cada categoria tem uma ação sugerida fixa e a busca da regra correspondente. */
public enum RejectionCategory {
    AUTORIZADA("Nenhuma ação necessária", "autorizado uso"),
    CANCELADA("Nenhuma ação: documento já cancelado", "cancelamento homologado evento registrado"),
    JA_CANCELADA("Não repetir o cancelamento", "cancelamento NF-e já cancelada 420"),
    DENEGADA("Encaminhar ao fiscal: uso denegado não pode ser reenviado", "uso denegado 110"),
    DUPLICIDADE("Consultar a chave antes de qualquer reenvio", "duplicidade 204 539"),
    NAO_ENCONTRADA("Confirmar a situação e reenviar com a mesma chave", "não consta base dados 217"),
    SCHEMA("Corrigir o XML e reenviar", "falha schema XML 225"),
    CHAVE_INVALIDA("Regerar a chave de acesso", "erro chave de acesso 502"),
    SERVICO_INDISPONIVEL("Aguardar e consultar antes de reenviar", "serviço paralisado 108"),
    DESCONHECIDA("Encaminhar a um analista", "rejeição");

    public final String suggestedAction;
    public final String ruleQuery;

    RejectionCategory(String suggestedAction, String ruleQuery) {
        this.suggestedAction = suggestedAction;
        this.ruleQuery = ruleQuery;
    }

    public static List<String> orderedNames() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static Set<String> names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
