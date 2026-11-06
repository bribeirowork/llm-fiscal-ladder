package dev.llmladder.eval;

import dev.llmladder.fiscal.AccessKey;

/** Dados sintéticos: UF 35, modelo 55, CNPJ fictício. */
public final class Samples {
    private Samples() {}

    public static AccessKey key(int number) {
        String base = "3526111122233300018155001%09d1%08d".formatted(number, number);
        return new AccessKey(base + AccessKey.checkDigit(base));
    }

    public static String xml(AccessKey key, String cStat, String motivo) {
        return """
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe><infNFe Id="NFe%s"><ide><nNF>123</nNF></ide>
                <emit><CNPJ>11222333000181</CNPJ><xNome>Empresa Exemplo LTDA</xNome></emit></infNFe></NFe>
                <protNFe><infProt><chNFe>%s</chNFe><cStat>%s</cStat><xMotivo>%s</xMotivo></infProt></protNFe></nfeProc>
                """.formatted(key.value(), key.value(), cStat, motivo);
    }
}
