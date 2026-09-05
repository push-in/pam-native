# PAM Native for VS Code

PAM single-file components contain real PHP. Open `.pam` and `.pam.php` as
PHP so Intelephense can index classes, imports, inherited methods and types.
The extension pack includes Intelephense. PAM contributes template navigation,
completion and formatting alongside the PHP server. Existing workspace
associations to `pam` take precedence; change them to `php` to enable PHP tooling.

```json
{
  "files.associations": { "*.pam": "php", "*.pam.php": "php" },
  "intelephense.files.associations": ["*.php", "*.phtml", "*.pam"]
}
```

The PAM language server runs with `pam exec`, using the runtime's PHP version
instead of the host interpreter. Set `pam.languageServer.runtime` for a custom
PAM executable. An explicit `pam.languageServer.path` remains an executable
override. PHP keywords, braces, open/close delimiters, CSS and template expressions
use their embedded grammars.

## Verification

`node --check extension.js` checks extension syntax. `php-editor.test.js` is a
VS Code extension-host integration test for an SDK-equipped sample workspace with
`src/Components/PrimaryButton.pam` (extends Component and calls emit). Launch VS Code
with this directory as `--extensionDevelopmentPath`, the test file as
`--extensionTestsPath`, and the sample app as the workspace. Intelephense must be
installed. It checks PHP language identity, SDK class definition, inherited method
definition and PHP hover. The test does not claim universal PHP language coverage.
