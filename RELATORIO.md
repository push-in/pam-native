# u4 — renderização dos signals

Corrigido `Signals::changed()` para usar `Runtime::scheduleRender()` em
mudanças simples e em batch. `Signal::set()` já invalida os consumidores;
a invalidação global redundante fazia flags de resource renderizarem
novamente linhas sem alterações no real-world app.

Commit: `79b13239` — `fix: preserve memoized components on signal updates [skip ci]`.
Branch própria com push: `codex/inbox-resource-framework`.
Foi executado `git pull --rebase` antes do push. Sem merge na integração.

Validação PHP **8/8**: `arch_v2_signals`, `arch_v2_signal_fanout`,
`arch_v2_composables`, `arch_v2_signal_rendering`, `arch_v2_resource`,
`arch_v2_resource_options`, `arch_v2_resource_production` e a suíte completa
`packages/native/tests/run.php`, que inclui a regressão nova. A execução
completa usa TMPDIR exclusivo para não reutilizar fixtures de runs antigos.
Teste novo cobre consumidor atualizado, linha alheia memoizada, signal sem
consumidores e batch. Documentação em `docs/architecture-v2.md`.

O real-world app passou 34/34 validadores selecionados com esta alteração
aplicada localmente ao SDK instalado. Os resultados estão em
`/tmp/codex-inbox-resource-validation/`.

Pendências: disponibilizar a correção na dependência consumida pelo
real-world app; concluir o teste de memória do app; comparar os APKs e
medir PSS/CPU/frames no emulador. Locks de emulador e Gradle pertencem a
outras tarefas e o disco voltou a ficar abaixo de 20 GB livres. Nenhum
teste nativo/build/emulador/aparelho físico foi executado nesta tarefa.
Sem conflito de rebase com a branch de integração. Sem dependências novas.
