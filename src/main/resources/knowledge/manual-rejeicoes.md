---
id: manual-rejeicoes
title: Guia de códigos de status da NF-e (resumo ilustrativo)
tenant: public
source: Resumo didático; a referência oficial é o Manual de Orientação do Contribuinte
---
## Código 100: autorizado o uso
O código 100 indica que a NF-e foi autorizada e o protocolo pode ser guardado junto do XML. Nenhuma ação é necessária.

## Código 110: uso denegado
O código 110 indica uso denegado por irregularidade fiscal. A nota denegada fica registrada na SEFAZ e não pode ser reenviada; encaminhe ao time fiscal.

## Rejeição 204: duplicidade de NF-e
A rejeição 204 indica duplicidade de NF-e: já existe nota autorizada com a mesma chave de acesso. Consulte a chave antes de qualquer reenvio e use o protocolo existente.

## Rejeição 217: NF-e não consta na base
A rejeição 217 indica que a NF-e não consta na base de dados da SEFAZ. Se o envio anterior expirou, a consulta 217 é o sinal de que o reenvio com a mesma chave é seguro.

## Rejeição 225: falha no schema XML
A rejeição 225 indica falha no schema XML: o arquivo não respeita o layout publicado. Corrija o XML e valide contra o XSD antes de reenviar.

## Rejeição 420: NF-e já cancelada
A rejeição 420 indica cancelamento para NF-e já cancelada. Não repita o pedido.

## Rejeição 502: erro na chave de acesso
A rejeição 502 indica erro na chave de acesso: o campo Id não corresponde à concatenação dos campos da nota. Regere a chave de acesso.

## Rejeição 539: duplicidade com diferença na chave
A rejeição 539 indica duplicidade de NF-e com diferença na chave de acesso: a mesma numeração foi autorizada com outra chave. Investigue antes de emitir de novo.

## Código 108: serviço paralisado
O código 108 indica serviço paralisado momentaneamente. Aguarde e consulte antes de reenviar.
