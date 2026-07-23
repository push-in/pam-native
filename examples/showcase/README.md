# Pam Native Android showcase

This application exercises the MVP's critical path:

- persistent PHP state and native events;
- incremental Rust rendering;
- input updates and stable keyed identities;
- stack navigation and Android back handling;
- asynchronous HTTPS and persistent native storage;
- a remote image and a virtualized list with 10,000 rows;
- development hot reload.

From the repository root:

```bash
pam composer install --working-dir pam-native/examples/showcase
pam mobile dev pam-native/examples/showcase
```

