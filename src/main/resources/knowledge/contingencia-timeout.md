---
id: contingencia-timeout
title: Timeout na autorização (resumo ilustrativo)
tenant: public
source: Resumo didático baseado em boas práticas de integração
---
## Timeout na autorização
Timeout não é falha, é desconhecimento: a nota pode ter sido autorizada mesmo sem resposta. Consulte a situação pela chave de acesso antes de agir.

## Reenviar depois de timeout
Nunca reenvie às cegas depois de um timeout. Primeiro consulte a chave: se autorizada, guarde o protocolo; se não consta na base, reenvie com a mesma chave e com idempotência.
