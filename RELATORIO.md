# Relatório — core slim, ondas 1 e 2

Branch remota: `feat/port4-core-slim-20261027`. Base inicial: `origin/integrate/port3-20261027` (`3f163f46`). Worktree próprio: `codex-slim-framework`.

## O que foi feito

- Reaplicada a onda 1 sobre a base de integração e recuperado o bump local de Cargo.toml, Cargo.lock, Protocol::SDK_VERSION e run.php para **1.46.0**.
- Preservada a ancestralidade publicada durante o rebase para permitir pushes normais, sem force-push. Conflito no CHANGELOG resolvido conservando os registros da integração e da extração.
- Notifications saiu para `pam-native-notifications`; MediaLibrary/MediaCapture/ViewCapture para `pam-native-media`; IncomingShares para `pam-native-share-extension`; Biometrics para `pam-native-auth`; WebView para o novo `pam-native-webview`.
- O núcleo conserva `MovedToPlugin` por uma versão e sugestões Composer em ambos os manifests. Ausência do plugin produz a instrução exata de instalação.
- Ganchos genéricos: intents/foreground/reload, permissões, lookup de nativeRef, reset/foco de rota em PHP e delegação de propriedades/eventos de nós reservados.
- O NodeKind da WebView permanece reservado; sua implementação vem da factory do plugin. O Swift de web foi separado de mídia. Helpers de URI de fotos continuam na base porque Image/MediaPlayer/Files os utilizam.
- O núcleo iOS mantém apenas os ganchos de provider/delegate de push; apresentação, ações e categorias pertencem ao plugin.
- A API antiga de biometria é adaptada para auth.biometrics, preservando o módulo biometrics durante a migração. AppCompat alinhado em 1.7.1 no plugin auth para resolver o classpath de testes do host.
- Testes PHP, JVM e instrumentados foram transferidos para seus plugins. O núcleo conserva testes de falha dos shims sem plugin.
- O plugin privado de edição teve a onda 1 reaplicada sobre sua main atual, preservando CoreText e os avanços de PhotoEditor, na branch privada de trabalho; não recebeu tag/release.

## Redução medida na onda 2

Contagem de linhas de produção, comparada ao commit `427adcfd` (onda 1 já aplicada e SDK 1.46 preparado):

| Camada | Antes | Depois | Variação |
| --- | ---: | ---: | ---: |
| Android app/src/main, Kotlin | 45.234 | 41.759 | −3.475 |
| iOS Sources, Swift | 36.365 | 33.317 | −3.048 |
| PHP SDK src | 76.162 | 73.803 | −2.359 |

Total líquido nessas três áreas: **8.882 linhas a menos**. Não é uma medição de APK/IPA; plugin-api e testes não entram nessa soma.

## Plugins públicos

| Repositório | Tag final |
| --- | --- |
| push-in/pam-native-media | v0.7.0 |
| push-in/pam-native-auth | v0.4.1 |
| push-in/pam-native-share-extension | v0.4.1 |
| push-in/pam-native-notifications | v0.1.2 |
| push-in/pam-native-webview | v0.1.1 |

Todos com release GitHub e atualização do Packagist aceita. Os plugins existentes ficaram em branches próprias; os dois repositórios públicos novos têm main. O núcleo **não recebeu tag nem release 1.46.0**. Nenhum merge/push foi feito na integrate.

## Gates

| Gate | Resultado |
| --- | --- |
| PHP SDK run.php, sem plugins | aprovado |
| PHP SDK run.php, com contratos dos cinco plugins | aprovado |
| scripts/protocol-parity.py | aprovado |
| cargo test -j4 | 274/274: CLI 98, engine 162, protocol 14 |
| Compile Kotlin, núcleo + cinco plugins | aprovado |
| Testes JVM, núcleo + cinco plugins | 360/360, zero falhas |
| PHP dos plugins | media 20/20; auth 4/4; share 8/8; notifications 35/35 asserções; webview 4/4 asserções |
| Compile Kotlin dos testes instrumentados, núcleo + plugins | aprovado (compilação; não executados em dispositivo) |
| Swift | pendente, sem Mac |

Gradle/cargo foram executados com o lock compartilhado; Gradle com Xmx3g, quatro workers, compilador Kotlin no processo e --stop no fim. O teste JVM do media usa org.json:json:20250517 no harness, após consulta ao POM. Os manifests/lockfiles e metadados Composer foram verificados antes da instalação.

## real-world app

O worktree `codex-slim-app` recupera os imports da onda 1, adota os namespaces da onda 2 e depende das tags públicas acima. O SDK é consumido por `dev-feat/port4-core-slim-20261027 as 1.46.0`; o editor privado, por sua branch candidata como 0.5.1. O relatório próprio registra os validadores e os impedimentos de ambiente.

## Pendências

- Compile/testes Swift, conforme proibição de usar o Mac.
- QA em dispositivo e medição de CPU/PSS: emulador compartilhado ocupado (`parity-chat`); S10 não utilizado.
- O gate de bundle gerado do real-world app depende dos artefatos descritos no relatório próprio. O validador visual recebeu da integração uma implementação compatível com PHP do PAM.
- Integração e publicação do núcleo 1.46.0 cabem ao coordenador; não foram executadas.

## Commits

```text
1feb9697 test(core): move instrumented and Swift capability tests with their plugins [skip ci]
b5b12a14 fix(plugins): let native factories release their views exactly once [skip ci]
31e55d37 refactor(core): extract wave two optional capabilities and compatibility tests [skip ci]
bb903bd6 feat(plugins): add generic host, runtime and reserved-node hooks [skip ci]
427adcfd chore: prepare SDK 1.46.0 for extracted plugins [skip ci]
8e5dea82 chore: preserve published ancestry after replaying wave one on integration [skip ci]
806bc8f6 ci: the Packagist ecosystem publisher retries until the release is visible and covers pam-native-audio [skip ci]
9d6d1e52 ci: the Packagist ecosystem publisher registers the plugins moved out of the core (location, contacts, sensors) on their first release [skip ci]
eb36ad42 refactor(core): move location, contacts/SMS, sensors, the audio recorder and the image editor out of the core into plugins (pam-native-location, pam-native-contacts, pam-native-sensors, pam-native-audio, the media editor plugin); deprecated alias shims for 1.46 that name the composer package when the plugin is missing; generic plugin hooks WatchChannel (plugin API, public on iOS) and Android ActivityResultHost; Play Services Location and the location/contacts permissions leave the core build [skip ci]
cf01e844 feat(resource): initial seed, cache store adapter, onSettled/onPage events and manual mode for useResource [skip ci]
015de4d6 fix(signals): notify a snapshot of the observers so every watcher of a signal or computed sees every change; each effect detaches and re-attaches while it re-runs, which moved it inside the live WeakMap and made the iteration skip the next observer, so with two or more watchers each one missed about half of the changes (useResource readers, computed chains, globals, composables) [skip ci]
d885779e ci: the Packagist ecosystem publisher retries until the release is visible and covers pam-native-audio [skip ci]
75d22573 ci: the Packagist ecosystem publisher registers the plugins moved out of the core (location, contacts, sensors) on their first release [skip ci]
818cb049 refactor(core): move location, contacts/SMS, sensors, the audio recorder and the image editor out of the core into plugins (pam-native-location, pam-native-contacts, pam-native-sensors, pam-native-audio, the media editor plugin); deprecated alias shims for 1.46 that name the composer package when the plugin is missing; generic plugin hooks WatchChannel (plugin API, public on iOS) and Android ActivityResultHost; Play Services Location and the location/contacts permissions leave the core build [skip ci]
```

O commit deste relatório é posterior aos hashes acima. Os logs de execução desta sessão estão em `/tmp/codex-slim-*`; são evidência local, não artefatos publicados.
