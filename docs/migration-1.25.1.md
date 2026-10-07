# Migrating to Pam Native 1.25.1

## Margins on virtualized cell roots

A margin on the root element of a `VirtualizedList`/`VirtualGrid` cell now
counts toward the cell, as in React Native: the row is `marginTop + content +
marginBottom` tall and the root is inset by its margins on every side
(`fullSpan` cells and grid rows included). Explicitly sized cells keep their
authored `height` (or `width` in a horizontal list); the margins are added
around it.

Apps that worked around the old behavior by turning a cell root's margin into
padding can return to the margin. Keeping the padding stays correct, except
that a padded root paints its background over what used to be the margin.

Sticky `ScrollView` children now pin with their top margin kept above them.

Rebuild the Android and iOS hosts after updating; the PHP SDK API and the
protocol are unchanged.
