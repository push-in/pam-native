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
- Validadores do real-world app: em execução no worktree próprio.

## Recursos e limites

Cargo e Gradle usam o lock compartilhado /tmp/pam-gradle.lock.d. Gradle: heap máximo 3 GB, quatro workers, Kotlin no processo, modo offline e --stop após cada rodada. Nenhum workflow foi disparado. Mac e S10 não utilizados; Swift e QA em dispositivo não foram executados.

## Pendências em acompanhamento

Concluir validadores do real-world app e gerar bundle para build-integrity. O media-editor privado 0.5.0 publicado não inclui as APIs ImageEditor extraídas: merge privado preparado e testes PHP aprovados, aguardando definição de entrega do plugin. Não publicar o núcleo.
