# PAM Native editor support

PAM ships one editor-neutral language server and formatter through Composer:

```bash
vendor/bin/pam-native-language-server
vendor/bin/pam-native-format --stdin --filename Component.pam
```

Both `.pam` and legacy `.pam.php` files are supported. The language server uses
standard JSON-RPC/LSP over stdio and currently provides full-document sync,
diagnostics, formatting, workspace component completion, directive hover help,
and go-to-definition between `.pam` components.

## VS Code

The official extension source is in [`vscode`](vscode). It has no npm runtime
dependencies and starts the language server installed in the current project's
`vendor/bin` directory. It also supplies PAM syntax highlighting and enables
the official formatter on save.

```bash
pam editor:install vscode
```

Use `--force` when intentionally replacing an already installed PAM extension.

## Neovim

Neovim 0.11 or newer starts the project-local language server for both PAM
extensions. PAM installs a dedicated `plugin/pam-native.lua` file and never
rewrites the user's main `init.lua`.

```bash
pam editor:install neovim
```

## Helix

PAM merges a clearly delimited managed block into `languages.toml`, preserving
the rest of the user's configuration. Re-running with `--force` refreshes only
that managed block.

```bash
pam editor:install helix
```

## Zed

Add this language-server command to a PAM language extension or workspace
configuration:

```json
{
  "command": "vendor/bin/pam-native-language-server",
  "args": []
}
```

## Emacs

Associate `*.pam` and `*.pam.php` with `web-mode`, then register
`vendor/bin/pam-native-language-server` in Eglot. Formatting remains available
through `pam format` when an editor has no LSP formatting integration.

## Any LSP editor

Start `vendor/bin/pam-native-language-server` with stdio transport. The server
does not open a socket, execute template expressions, or require a global PHP
installation when invoked through `pam exec` or a generated PAM project.
