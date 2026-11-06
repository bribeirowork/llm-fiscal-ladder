package dev.llmladder.core;

/** O tipo de demanda define o padrão dominante e a autonomia. */
public enum Demand {
    NEGOCIO("RAG com citação da fonte", "baixa: responde, não age"),
    PROCESSO("Workflow com saída em JSON validado", "média: etapas fixas"),
    AUTOMACAO("Agente com ferramentas e aprovação humana", "alta, proporcional ao risco");

    public final String pattern;
    public final String autonomy;

    Demand(String pattern, String autonomy) {
        this.pattern = pattern;
        this.autonomy = autonomy;
    }
}
