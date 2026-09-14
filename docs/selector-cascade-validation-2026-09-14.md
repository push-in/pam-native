# Selector and cascade validation — 2026-09-14

The native template cascade now compares specificity as separate integer fields
instead of a weighted packed score. An ID selector cannot lose to 1,001 class
selectors because of a numeric-score collision. Importance remains first and
source order remains the final tie breaker.

Selector matching normalizes sparse compound/ancestor lists and validates nested
class, pseudo and attribute structures before using them. Nonscalar attribute
values no longer reach string casts. Malformed structures fail matching instead
of causing invalid array operations. Cascade declarations and specificity have
explicit validation; scoped class maps reuse the existing declaration validator.

SDK tests passed. Added regressions cover ID-versus-many-classes precedence,
important overrides, sparse child-selector ancestry, malformed selector metadata
and nonscalar attribute comparisons. Existing public Language 2 tests cover
compound/child/descendant/attribute selectors and important cascade behavior.

PHPStan level 9 completed with 20 remaining TemplateRenderer diagnostics, down
from 65. None are in the selector matching, descriptor, class-map or cascade
functions changed here. Artifact: `/tmp/pam-template-selectors-static-20260914.json`.
Remaining work is concentrated in scoped state/recipe attributes, event attribute
types, keyframe metadata and error-message formatting. No diagnostic suppression
or passing release status was added.

The existing SDK performance script was inspected, but it measures tree encoding
and theme application, not this template selector path. It was not used to claim
selector performance. No Android rebuild, iOS verification or publication was
performed for this batch.
