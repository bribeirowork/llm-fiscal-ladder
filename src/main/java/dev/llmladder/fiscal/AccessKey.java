package dev.llmladder.fiscal;

/** Chave de acesso da NF-e: 44 dígitos com dígito verificador módulo 11. Validar em código evita gastar LLM. */
public record AccessKey(String value) {
    public AccessKey {
        if (!isValid(value)) {
            throw new IllegalArgumentException("chave de acesso inválida");
        }
    }

    public static boolean isValid(String key) {
        return key != null && key.matches("\\d{44}") && checkDigit(key.substring(0, 43)) == key.charAt(43) - '0';
    }

    public static int checkDigit(String first43) {
        int weight = 2;
        int sum = 0;
        for (int i = first43.length() - 1; i >= 0; i--) {
            sum += (first43.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    /** Forma segura para logs: a chave completa carrega o CNPJ do emitente. */
    public String masked() {
        return value.substring(0, 6) + "..." + value.substring(40);
    }

    @Override
    public String toString() {
        return masked();
    }
}
