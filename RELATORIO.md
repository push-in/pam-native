# Relatório — integração 1.46.0 (codex-integrate146)

## O que foi feito

- Merge do PR #180, `feat/port4-core-slim-20261027`, sobre `origin/integrate/port3-20261027` em worktree próprio.
- Ondas 1 e 2 do núcleo mínimo integradas. Funcionalidades opcionais continuam nos plugins; ganchos genéricos e aliases temporários permanecem na base.
- Versão 1.46.0 preparada em Cargo.toml, Cargo.lock, Protocol::SDK_VERSION e contrato PHP. CHANGELOG mantém 1.46.0 sob Unreleased, sem tag nem release do núcleo.
- Preservadas as correções de lazy rows (`5d52b607`), Signal (`a4b3950a`), useResource (`3f163f46`), predictive back (`e7b0d465`) e tipografia Android (`193b1660`).

## Conflitos

CHANGELOG: conteúdo das duas ondas consolidado sob Unreleased/1.46.0, conservando integralmente as notas 1.45.1. Cargo.toml, Cargo.lock, Protocol.php e run.php: adotado 1.46.0, mantendo as demais mudanças de ambos os lados. PamActivity/PamRenderer combinaram automaticamente os ganchos dos plugins com as correções recentes da integração.

## Commits

- `0168f10d`: merge das duas ondas e preparação da 1.46.0.

## Validadores

- PHP SDK run.php isolado, PHP 8.5 do PAM: 1/1.
- PHP SDK com contratos de media/auth/share-extension/notifications/webview publicados: 1/1.
- protocol-parity.py: 1/1.
- cargo test --locked -j4: 274/274 (CLI 98, engine 162, protocol 14).
- Kotlin compile + JVM, núcleo e plugins das duas ondas (audio, location, contacts, sensors, media, auth, share-extension, notifications, webview e editor privado candidato): 388/388, zero falhas, 95 suítes. O plugin-api não possui testes JVM próprios. Três falhas iniciais de caminhos de fixtures foram resolvidas configurando pam.plugin.dir no harness externo, sem alteração de produção.
- Todos os validadores do real-world app: 485/485 após repetições; registrados no Composer: 59/59. Build integrity da árvore final: 3195 arquivos verificados; alinhamento visual: 279 ícones, desvio máximo 0,5 px. O relatório do app detalha as rodadas e correções.

## Recursos e limites

Cargo e Gradle usam o lock compartilhado /tmp/pam-gradle.lock.d. Gradle: heap máximo 3 GB, quatro workers, Kotlin no processo, modo offline e --stop após cada rodada. Nenhum workflow foi disparado. Mac e S10 não utilizados; Swift e QA em dispositivo não foram executados.

## Entrega e pendências

PR #180 reconhecido como MERGED pelo GitHub; merge 0168f10d e relatório 5bd1c220 enviados à integrate. O media-editor privado 0.5.0 não continha as APIs extraídas; integração privada 4b6d3c9 e correção f878c22 entregues como tag/release privada v0.5.1, sem publicação em serviço público. O real-world app conserva ^0.5.0 e instala 0.5.1.

Nenhum gate solicitado pendente. Swift e QA/perfilamento em dispositivo continuam fora desta execução; não há números de CPU/PSS/FPS. Nenhuma tag/release do núcleo foi criada. O commit deste relatório registra os resultados finais.
