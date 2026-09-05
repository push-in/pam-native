# Native bundle exclusions

Put `.pamignore` in the application root to exclude design references and other
files that do not belong on the device. Both Android and iOS staging use it.

```text
# Exact project-relative files or directories
BASE LAYOUT/
docs/
tests/
```

Directory rules exclude descendants. `config/local.php` excludes only that file,
not `config/local.php.bak`. Blank lines and full-line comments are ignored. Paths
may contain spaces, but must be relative; traversal, absolute paths, wildcards and
negation are rejected. Existing built-in exclusions remain in force.

This is an explicit path list, not gitignore syntax. Avoid shipping reference
assets, secrets, local environment settings and test fixtures. Bundle integrity
hashes cover the resulting staged files as before.
