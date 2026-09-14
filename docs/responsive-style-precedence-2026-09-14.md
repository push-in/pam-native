# Responsive style precedence — 2026-09-14

PAM Native's responsive sheet merge validates incoming sheet maps and reuses
validatedStyleRules for class/tag declarations. It merges typed local maps rather
than unpacking unchecked nested metadata. Existing declarations not replaced by a
matching query remain intact; unmatched queries leave the sheet unchanged.

Responsive cascade order now starts after the maximum base rule order, not after
the number of base rules. For example, one base rule with order 9 followed by an
incoming rule with local order 2 now produces order 12, not order 3. Negative,
noninteger and overflowing rule orders fail explicitly.

Query environments passed to the query compiler are limited to named scalar/null
entries. The existing integer StyleQueryKind enum is used in regression fixtures.
No new string discriminator or native UI host implementation was introduced.

SDK tests passed. New internal merge regressions cover preserving declarations,
sparse order precedence, unmatched queries, malformed class/cascade containers,
noninteger orders and the maximum-integer overflow boundary. Existing public
Language 2 rendering tests also passed in the same suite.

PHPStan level 9 completed with 65 remaining TemplateRenderer diagnostics, down
from 74. It reported none in responsiveStyleSheet, styleMap or queryMatches.
Full diagnostic output: `/tmp/pam-template-responsive-static-20260914.json`.
This is not a clean-file or release approval. No Android rebuild, visual claim,
iOS execution or publication was performed for this internal metadata batch.
