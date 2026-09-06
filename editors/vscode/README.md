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

## Build an installable extension

From the SDK checkout, run:

```sh
python3 editors/vscode/package.py --output /tmp/pam-vscode-dist
code --install-extension /tmp/pam-vscode-dist/pam-native-vscode-0.1.1.vsix
```

The filename follows the version in `package.json`. The build uses Python's standard
library and does not install npm dependencies. It includes only the extension
runtime, grammar, manifest, README and SDK license; development tests stay outside
the distributed extension. A SHA-256 sidecar accompanies the VSIX. Entries have
fixed ordering, timestamps and permissions, producing identical archives when
built with the same Python/zlib toolchain. Release automation may upload both
files; this command does not publish to GitHub or the VS Code Marketplace.

CI runs `python3 editors/vscode/test_package.py` to check deterministic output,
checksums, XML metadata, bundled runtime/grammar paths, license inclusion and
exclusion of development files. A VS Code installation smoke check remains a
separate release validation.
