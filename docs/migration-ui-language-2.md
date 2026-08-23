# Migrating to PAM Native UI Language 2

Migration is per component and reversible until you commit the source change.
No application-wide flag is required.

1. Keep the PHP block valid PHP 8.5 and add `#[Prop]`, `#[State]` and
   `#[Action]` to the existing contracts.
2. Add typed `#[Event]` and `#[Slot]` class attributes where the component
   publishes those surfaces.
3. Add accessibility labels or mark decorative images explicitly.
4. Add `p-key` to every loop inside a virtual list or grid.
5. Change `<template>` to `<template language="2">`.
6. Run `pam format`, `pam native:optimize` and `pam test`.

Language 1 components and Language 2 components may call each other. Convert
leaf components first, then shared components and finally screens. Do not
rewrite working attributes into a custom `props {}` syntax: the attributes are
the stable public API.
