# Migrating to Pam Native 1.27.0

## Keyed VirtualizedList sections

No change is required: lists without `listSection` behave as before.
Rebuild the Android and iOS hosts (new protocol identifiers 524 and 525).

To keep tab content alive over one list (chat details, profile tabs):

1. Keep rendering the rows of every visited tab, each with
   `listSection="<tab key>"` and a key unique across tabs.
2. Set `activeSection` on the `VirtualizedList` to the active tab key.
3. Leave the shared rows (header, the sticky rail, a footer) without a
   section.

The rows of one section must be contiguous to share the block origin; a
row between two sections without a section ends the block. Scroll
requests (`scrollRequest` with a target test id) only reach rows of the
active section. A list whose `activeSection` matches no row shows only the
shared rows.

Incremental layouts now keep the previous frames of clean subtrees under a
dirty node instead of re-sending them; no host change is needed.
