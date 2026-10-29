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


## v3 — memoização dos consumidores de signals (2026-10-28)

Correção `ce4bd91b`: `Signals::changed()` e o fechamento de batch usam
`Runtime::scheduleRender()`. `Signal::set()` já invalida os consumidores;
remover a invalidação global preserva linhas memoizadas sem alterações.
Regressão cobre consumidor atualizado, componente alheio, ausência de
consumidores e batch. Documentação em `docs/architecture-v2.md`.

Rebase concluído sobre `e416f326`. Revalidação PHP 8.5: **8/8** verdes:
`arch_v2_signals`, `arch_v2_signal_fanout`, `arch_v2_composables`,
`arch_v2_signal_rendering`, `arch_v2_resource`, `arch_v2_resource_options`,
`arch_v2_resource_production` e a suíte completa `packages/native/tests/run.php`.
TMPDIR exclusivo: `/tmp/codex-inbox-resource-validation/framework-v3-tmp`.
Resultados: `/tmp/codex-inbox-resource-validation/framework-v3-results.json`.
Sem alterações de dependências, plugins ou código nativo nesta correção.
A integração desta correção não atesta QA nativo do aplicativo consumidor.


## Rodada x5 — Drawer e Toast, 28/10/2026

Extração do WIP concluída: Drawer e Toast passam a plugins opcionais públicos;
as 13 classes/enums históricos usam MovedToPlugin. IDs de protocolo permanecem
reservados; o núcleo encaminha as operações pelos registros genéricos.
Nenhum arquivo reservado de chat, listas, shared elements ou perfil foi alterado.

Publicados em push-in/pam-native-drawer e push-in/pam-native-toast: tags e releases
0.1.0; registro no Packagist confirmado com success pelo packagist-publish.
Sem tag do núcleo. Rebase sobre origin/integrate/port3-20261027 concluído; conflitos
apenas de relatório, preservando o texto anterior da integração.

Validações: suíte PHP SDK sem plugins 1/1; suíte PHP com os dois plugins e
compatibilidade 1/1; protocolo 1/1; manifests Composer dos plugins 2/2; compilação
Kotlin dos plugins 2/2; testes JVM Drawer 5/5 (Toast sem testes JVM).
Gradle com lock exclusivo, -Xmx3g, quatro workers, --stop e liberação ao terminar.
Compilação aprovada com fallback sem daemon Kotlin pela restrição de escrita.
Logs em /tmp/codex-x5-validation/. Builds próprios removidos após a etapa.

QA visual/instrumentado e medições no dispositivo não realizados nesta rodada.
Integração autorizada pelo pedido x5 com validadores verdes sem QA visual.
Somente Android; não houve execução de Swift, Mac, dispositivos físicos ou emulador.

Aplicativo consumidor validado: 6/6 checks PHP pertinentes com o SDK integrado
e 6/6 com o SDK estável do lock; dependência Toast 0.1.0 e lockfile definitivos.
Disco caiu abaixo de 20 GB por consumo concorrente; builds e vendor próprios
removidos. Sem QA visual, conforme autorizado nesta rodada.


## y2 — altura intrínseca do Input (2026-10-28)

O Input deixa de usar o controle fixo de 48: a altura automática vem da caixa de texto, com padding e bordas autorados. `height` continua definitivo; `minHeight` e `maxHeight` limitam o resultado. Single-line mede uma linha inclusive com texto longo ou quebras; multiline cresce por quebra explícita ou pela largura útil disponível. A medição nativa também recebe o limite de uma linha quando aplicável, com cache existente preservado.

Validação: `cargo test --workspace --offline`, quatro workers e lock `/tmp/pam-gradle.lock.d`: **279/279** (98 CLI, 167 engine, 14 protocolo), sem falhas. Quatro regressões novas cobrem padding/borda, altura explícita/mínima, multiline/maxHeight e callback nativo; dois testes existentes foram ajustados à nova altura intrínseca. Nenhuma dependência adicionada. Não foram alterados componentes de lista, balões, perfil ou shared elements.

QA visual e medição CPU/PSS/FPS em Android não executadas nesta rodada. Conforme a diretiva desta tarefa, entrega ao integrate após os validadores PHP verdes, sem aguardar emulador. Nenhuma tag do núcleo criada. Worktree próprio: `codex-input-height-fw` (não existia e foi criado a partir do integrate remoto).

Gate do consumidor antes da integração y2: cobertura final **497/497** validadores PHP, executados um por vez com limpeza por script. Rodada inicial 493/496; integridade passou após preparar o bundle, e duas expectativas antigas foram corrigidas e repetidas com sucesso. Os dois testes adicionais que usam a fixture ajustada também passaram. O rebase final trouxe um novo validador e mudanças concorrentes; 50/50 validadores relacionados passaram novamente, incluindo o novo teste, e a integridade do bundle final foi repetida com sucesso. A distribuição publicada não foi alterada; o coordenador empacotará o novo engine. Artefatos Cargo próprios removidos.


## y3 — Inicialização Android após instalação (28/10/2026)

A ativação do release PNB1 agora aguarda somente PHP/templates e metadados do runtime. Assets em `assets/` são entregues ao worker depois da ativação; os consumidores nativos continuam lendo-os diretamente do APK. A cópia começa após um frame PHP comprometido, usa um worker e buffer de 64 KB, e para quando a Activity deixa o foreground. No próximo foreground/start, arquivos já válidos são ignorados por SHA-256/tamanho; assets idênticos do release anterior são reaproveitados sem reabrir a entrada do APK. Cada arquivo é verificado antes de publicação atômica. Um marcador de conclusão evita refazer a instalação de assets em starts seguintes; se o processo morrer, a lista do pack permite retomar.

O splash configurado para esperar PHP não tem mais o timeout de quatro segundos que expunha uma janela vazia. Ele é liberado pelo frame comprometido ou pela apresentação de erro. O modo `firstFrame: window` permanece disponível para consumidores que o escolham explicitamente. As cores continuam sendo geradas pela configuração de aparência de cada consumidor.

Testes: **336/336 JVM verdes**, incluindo inicialização sem abrir assets adiados, metadados síncronos, integridade/tamanho, substituição atômica, reaproveitamento por digest, espera pelo primeiro frame e interrupção ao ocultar. A suíte do consumidor passou **497/497 validadores PHP**, um por vez e com limpeza entre execuções. Dois APKs benchmark x86_64 compilaram com o mesmo engine construído das fontes desta integração. Não houve alteração de dependências nem criação de tag.

Integração com gates verdes antes do QA visual, conforme autorização desta rodada. A comparação no emulador aguarda a liberação do lock compartilhado; ainda não há número medido antes/depois. Evidências: `~/.local/share/pam-codex/shots/y3/`. O acesso não foi obtido durante a tentativa de 600 segundos.

Rebase final preservou o ajuste concorrente de altura intrínseca do input e o relatório anterior. A suíte JVM foi revalidada sobre o HEAD integrado: BUILD SUCCESSFUL.

Resultado final do QA em dispositivo: até 21:58 de 28/10/2026 (America/Sao_Paulo), o lock do emulador não pôde ser adquirido durante 10 minutos; o owner observado inicialmente era `render-diff` e, na verificação final, `feed-scroll` (21:55:56). **Não foram medidos primeiro start antes/depois, pixels dos temas, memória/CPU no dispositivo ou paridade visual com o app de referência.** Nenhuma melhoria numérica é afirmada. A regra desta rodada autorizou integrar com os validadores verdes mesmo sem QA visual; a limitação permanece explícita.

Os APKs comparáveis estão em `~/.cache/codex-cold-start/{before,after}.apk`, com engine nativo idêntico. A inspeção de DEX confirmou que somente o corrigido contém `pam-deferred-assets`/`.assets-ready` e que o timeout antigo existe somente no baseline. O roteiro de medição e os hashes estão em `~/.local/share/pam-codex/shots/y3/`. Artefatos próprios de compilação, `.pam-native` e vendor foram removidos; APKs e evidências foram preservados. Nenhum lock ou artefato de outra sessão foi removido.

## z1 — Amostragem terminal do shared element Android (28/10/2026)

A falha `expected 300 but was 482.47` vinha do teste `mediaSharedElementMorphsCropRadiusAndFitBothWays`, não da geometria do shared element. O sampler de Choreographer só enxergava o voo antes do callback terminal: o ValueAnimator aplica progresso 1 e remove o voo no mesmo callback, antes da próxima amostragem. Na volta cúbica de 200 ms, a borda direita obedece a `1080 + (300 - 1080) * t³`; 482,47 corresponde a t ≈ 0,915 (183 ms), não ao destino.

O teste agora registra um listener de atualização no animator após o listener do host, usando reflexão somente no teste e um pre-draw registrado após navigate. Assim, amostra a geometria aplicada inclusive no progresso terminal, antes da limpeza. Os destinos passaram a tolerância de 0,01 px, sem alargar tolerâncias; também verifica alpha zero nas duas imagens durante as atualizações. Nenhum código de produção, PamSharedElements, Modal, lista, balões, fundo, perfil ou galeria foi alterado.

Build Android x86_64 e **336/336 testes JVM verdes**, repetidos após rebase no integrate com a alteração concorrente de PamRecyclerList. Engine reconstruído das fontes deste worktree: a primeira tentativa de reaproveitar a biblioteca pré-compilada encontrou símbolos JNI incompatíveis. Gradle/Cargo sempre sob `/tmp/pam-gradle.lock.d`, quatro workers, Gradle -Xmx3g e --stop ao terminar. **498/498 validadores PHP do consumidor verdes**, um por vez e com limpeza de .pam-native após cada script. A integridade foi repetida após preparar o bundle com `prepare . --abi x86_64`; a primeira invocação usou opção inválida. Vendor reaproveitado por hard links de um lockfile idêntico, sem instalar ou alterar dependências; removido ao fim.

Worktrees indicados inexistiam e foram criados como `codex-shared-test-fw` e `codex-shared-test` (consumidor só para validação). Builds, .cxx, engine temporário, vendor e .pam-native próprios já removidos. O disco começou perto de 21 GB e caiu abaixo de 20 GB por consumo concorrente, mesmo após limpar todos os builds próprios; nenhum artefato de outra sessão foi removido.

Integração com validadores verdes conforme a diretiva desta rodada, sem tag. A execução das sete classes PamNavigationHost/PamModal* (36 métodos) está aguardando o lock do emulador; a tarefa continua para concluir essa etapa. APKs e XMLs JVM preservados em `~/.local/share/pam-codex/shots/z1/`; logs e resultados PHP em `/tmp/codex-z1-validation/`. Ainda não se afirma sucesso instrumentado, paridade visual ou métricas de CPU/PSS/FPS.

## Rodada z2 — métricas de texto e elevação Android (28/10/2026)

Worktree `codex-composer-fw`, branch `codex/composer-fw`, criado a partir de
`origin/integrate/port3-20261027` porque o diretório indicado não existia.

`PamLineHeightSpan` agora distribui o leading a partir de ascent/descent,
preservando top/bottom nas linhas internas e ajustando apenas as bordas do
parágrafo. O algoritmo anterior podia manter avanço de 54 px quando o estilo
solicitava 56 px e deslocar a primeira baseline. Referência de semântica:
[CustomLineHeightSpan do RN 0.85](https://github.com/facebook/react-native/blob/v0.85.3/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/views/text/internal/span/CustomLineHeightSpan.kt).
Medição do engine e desenho continuam usando o mesmo pipeline.

O renderer evita que `setZ` aplicado por z-index reduza uma elevation já
convertida de dp para pixels. A correção cobre aplicação inicial, atualizações,
remoção, estilos de estado e restauração após pan do teclado. O empilhamento
legado por Z permanece; o Z efetivo passa a respeitar a elevation como piso.
Não foi introduzida API opcional nem dependência.

Verificação local: 336/336 testes JVM e compilação dos testes Android aprovados;
181/181 testes Rust (167 engine + 14 protocolo) aprovados. Gradle/cargo sob
`/tmp/pam-gradle.lock.d`, quatro workers e JVM com -Xmx3g; daemon encerrado.
O compilador Kotlin usou fallback em processo porque seu diretório de daemon
fora dos roots graváveis era somente leitura. APK/instrumentação e comparação
visual ainda serão registrados abaixo após a sessão Android.

Disco começou com cerca de 21 GB e caiu abaixo de 20 GB por uso concorrente.
A limpeza se restringe aos artefatos deste worktree. Nenhum diretório alheio,
Mac, dispositivo físico ou workflow foi utilizado; nenhuma tag foi criada.

Resultado da suíte do app que acompanha esta rodada: **498/498 aprovados**,
execução única sequencial contra o SDK 1.46.5 do lock. Todos os diretórios
.pam-native foram removidos após cada validador. APKs de instrumentação do
framework gerados com sucesso; 336 testes JVM e 181 Rust aprovados.
A integração inicial é feita com QA visual pendente, conforme autorização
expressa desta rodada; a evidência Android será acrescentada após o lock.



### Encerramento z2

App e framework integrados e enviados a `integrate/port3-20261027` após
498/498 validadores PHP verdes; no app, mais 53/53 gates afetados pelo rebase
foram aprovados. Commits funcionais do framework: b55f56b8 (leading), d013d628
(elevation) e 54e4cccd (regressão 54→56 px).

**Limitação de QA:** não houve sessão no emulador. O lock compartilhado
permaneceu ocupado e trocou de dono entre outras tarefas durante cerca de
14 minutos de espera. A espera foi encerrada sem tomar/remover lock alheio e
sem deixar aquisição pendente. Os 11 testes instrumentados de texto/Z foram
compilados, mas não executados. Não há comparação visual RN×PAM, screenshots
ou medições de CPU/PSS/FPS desta rodada. O deslocamento visual do botão e a
sombra ainda precisam de confirmação no dispositivo; a adoção da semântica
de texto do RN 0.85.3 foi conferida no código-fonte primário.

APKs de QA e seus hashes preservados em
`~/.local/share/pam-codex/shots/z2/`; logs em `/tmp/codex-z2-validation/`.
O APK final do app inclui a atualização concorrente de PamRecyclerList, sem
edição deste arquivo pela tarefa z2. Vendor, .pam-native, builds, .cxx e target
próprios foram removidos. O disco permaneceu abaixo de 20 GB por uso
concorrente; nenhum artefato alheio foi apagado. Não houve Mac, Swift, aparelho
físico, nova tag ou workflow disparado manualmente.

## z4 — foco depois de setup/onMounted (2026-10-28)

Reaplicado `accc118b` em `codex-focus-setup-fw` sobre
`origin/integrate/port3-20261027`, com resolução apenas do conflito de
CHANGELOG (preservadas as entradas atuais). O foco de navegação aguarda
`setup()` e `onMounted()`; sair antes do mount cancela a entrega. A mudança
não adiciona funcionalidade opcional ao núcleo nem altera renderizadores,
listas, shared elements ou código de plataforma.

Validação: `packages/native/tests/arch_v2_focus_order.php` e suíte completa
`packages/native/tests/run.php` verdes com o PHP embarcado 2.2.7. A suíte
completa usou `TMPDIR=/tmp/z4-fw-tests`, isolado desta tarefa: a primeira
execução encontrou classe duplicada em cache temporário compartilhado;
nenhum temporário alheio foi removido. Log: `/tmp/z4-framework-tests.log`.
O teste cobre abertura inicial, push, back, keep-alive, modal e tabs.

Sem build Android, Gradle, cargo ou emulador nesta tarefa: mudança apenas
PHP; QA visual não executado. O disco já iniciou abaixo de 20 GB livres,
sem artefatos próprios preexistentes que permitissem recuperar o mínimo.
Mantidas apenas verificações leves e limpeza dos artefatos desta tarefa.
Nenhuma tag foi criada. A integração depende dos validadores completos do
consumidor, executados contra uma cópia deste framework no vendor local.

Gate do consumidor concluído: **501/501** validadores próprios verdes,
executados sequencialmente contra o código PHP deste framework, mais
**32/32** cenários de perfil/sessão reexecutados após o ajuste final.
Sem revisão da versão publicada ou criação de tag. Integração por avanço
rápido em `integrate/port3-20261027`, com pull/rebase antes do push.
Artefatos próprios de validação removidos ao encerrar.
