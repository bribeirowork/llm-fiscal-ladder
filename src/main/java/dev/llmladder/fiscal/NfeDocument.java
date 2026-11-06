package dev.llmladder.fiscal;

/** Campos extraídos do XML. cStat e xMotivo vêm do protocolo de resposta, quando presente. */
public record NfeDocument(AccessKey key, String issuerCnpj, String issuerName, String number, String cStat, String xMotivo) {}
