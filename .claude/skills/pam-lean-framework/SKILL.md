---
name: pam-lean-framework
description: Regra obrigatória de framework enxuto do PAM / PAM Native, para quem mexe no framework (push-in/pam-native, pam, plugins pam-native-*) ou nos apps que o usam (Zé Chat PAM, PushinPay PAM Native). O core é só a base; toda feature opcional vai para um plugin pam-native-*; nada de app, dependência nova ou trabalho em background sem necessidade; toda correção vira release oficial confirmada no Packagist. Use antes de criar, mover ou alterar qualquer módulo, componente, primitivo, prop, dependência, permissão, plugin ou release do framework, e antes de adicionar código nativo, dependência ou pin de versão num app PAM.
---

# Framework enxuto (PAM / PAM Native)

**Regra:** o `pam-native` é **só a base**. Toda feature que nem todo app usa fica num plugin
`pam-native-*`, que pode ser novo ou existente. No core fica apenas o **gancho genérico** de que o plugin
precisa. Referência completa: `PAM_CORE_SLIMMING.md`, na pasta Pushin.

## 1. O que pode ficar no core

- Runtime, bridge, protocolo e wire, renderer, layout engine (Rust), templates e compilador de CSS.
- Primitivos: View, Text, Image, Input, listas (Flat/Section/Virtualized/VirtualGrid), Pressable,
  Modal, BottomSheet, ActivityIndicator, Switch, StatusBar, SafeArea, KeyboardAvoiding,
  RefreshControl, MediaPlayer básico, Portal.
- Navegação e rotas, estado (signals, Store, Composables, Scheduling).
- HTTP básico, Storage, SecureStorage, SQLite, Cache, Files, Crypto, Update (OTA).
- Sistema básico: gestos, teclado, acessibilidade, Haptics, Clipboard, Linking, Permissions,
  Appearance, Window, Timers, DeviceInfo, Alert/Share básicos, toast básico.
- Ganchos genéricos para plugins, como `WatchChannel`, `ActivityResultHost`, `ViewRef`,
  `BackgroundPush` e o registro de token de push.
- Ferramentas de build e CLI (formatter, lints, auditoria de runtime).

## 2. O que vai para plugin

| Feature | Destino |
|---|---|
| Location | `pam-native-location` |
| Contacts e SMS | `pam-native-contacts` |
| Sensors | `pam-native-sensors` |
| Gravação de áudio | `pam-native-audio` |
| Notificações (conversa, ações, reply, credenciais, categorias) | `pam-native-notifications` (+ `pam-native-firebase`) |
| Galeria, câmera e captura de view | `pam-native-media` |
| Compartilhamento recebido | `pam-native-share-extension` |
| Biometria | `pam-native-auth` |
| WebView | `pam-native-webview` |
| Canvas | `pam-native-canvas` |
| Drawer Android, Swipeable, estilo do toast | `pam-native-ui` |
| Catálogo de tradução e `t()` | `pam-native-i18n` (o locale fica no core) |
| Sync e LocalFirst | `pam-native-sync` |
| Edição avançada de mídia (crop, filtros, desenho, texto, camadas, DrawingCanvas) | plugin **privado** `pam-native-media-editor` |

Se uma feature nova não está na lista do §1, ela vai para plugin. Na dúvida, também vai para plugin.

## 3. Proibido no core

- **Nome ou regra de app.** Nada de "Zé Chat", "PushinPay", `zechat://` ou `dev.zechat`, nem em comentários,
  fixtures e testes. Use nomes genéricos. Nada específico da PushinPay entra no core.
- **Nome de lib externa vazando** em classe ou API (ex.: `PamVuetifySpinner` deve virar `PamSpinner`).
- **Dependência nova** (Gradle, CocoaPods/SPM, Composer, crate) ou **permissão nova** sem
  necessidade comprovada. Se a feature precisa delas, elas vão no `pam-native.plugin.json` do plugin.
  A CLI mescla essas declarações no app.
- **Trabalho em background sem necessidade**: timer, listener, polling, service, job ou animação contínua
  que roda sem ninguém ver. Ver a skill `lean-resources`.

## 4. Como mover sem quebrar apps

1. **Shim por um minor.** No core fica um arquivo de ~15 linhas que chama
   `Pam\Native\Plugin\MovedToPlugin::alias(antigo, novo, pacote)`.
   - Com o plugin instalado, o nome antigo vira alias.
   - Sem o plugin, o shim lança um erro dizendo qual `composer require` rodar.
   - O shim sai no minor seguinte.
2. Liste o plugin em `suggest` no `composer.json` do core.
3. **Não mude** o nome do módulo nativo nem o payload do wire.
4. Ganchos novos vão no `plugin-api` (Android) e no equivalente iOS, sempre genéricos.
5. O plugin declara `pamNative.minimum` com a versão do core que tem o gancho. Publique o core antes.

## 5. Bundle do aparelho enxuto

- O bundle de release **não** leva fontes nativas de plugins, testes, docs, licenças, lockfiles
  nem o compilador web/TV.
- Os componentes são pré-compilados em modo estrito. Uma falha estrita quebra o build, nunca o boot.
- Antes de publicar, meça o tamanho do APK/IPA e do vendor. Crescimento sem motivo é blocker.

## 6. Publicação (obrigatório)

- Toda correção ou melhoria no `pam`, no `pam-native` ou num plugin vira **release oficial**, seguindo o
  processo do repositório. Depois confirme a versão no **Packagist**, com o commit certo.
  - Se o Packagist não atualizar sozinho, por falta de webhook, acione o update pela API do Packagist com o token
    da conta mantenedora. Nunca deixe o app dependendo do GitHub.
- No app, commit de branch, `dev-*`, `vcs` repository ou pin por hash **não substitui** a release.
  Atualize o app para a versão oficial (`composer update pushinbr/<pacote>`), rode os validadores
  e só então gere o build de loja.
- Toda release de framework ou plugin que mude código nativo exige build e teste nas **duas**
  plataformas. Inclui o Swift do plugin no host iOS, mesmo quando no iOS a mudança é um no-op.

## 7. Nos apps (Zé Chat PAM, PushinPay PAM Native)

- O app **consome** plugins e não copia código nativo de plugin para dentro dele.
- Regra de negócio do app fica no PHP do app. Feature nativa que só um app usa vai para plugin,
  privado se for o caso, nunca para o core.
- Não adicione dependência Composer ou nativa ao app sem necessidade. Prefira o que o core ou
  um plugin já oferecem.

## Checklist antes do PR

- [ ] A mudança no core é base ou gancho genérico? Se não, vai para plugin.
- [ ] Nenhum nome de app ou de lib externa no core (inclusive testes e fixtures).
- [ ] Nenhuma dependência ou permissão nova no core.
- [ ] Nada roda em background quando não está visível nem é necessário.
- [ ] Shim e `suggest` quando algo saiu do core. Wire e nomes de módulo inalterados.
- [ ] Release oficial publicada e confirmada no Packagist. App atualizado para a versão oficial.
- [ ] Android e iOS buildados e testados com as versões oficiais.
