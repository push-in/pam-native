# Inactive input leading text

Android's shared input value application previously moved every changed value's
selection to its end, even while the field was inactive. The UI Search Bar's long
query specimen consequently showed its trailing text before interaction.

The renderer now defaults inactive value changes to selection zero, retaining
the trailing selection for focused editing. Explicit selection start/end take
precedence and are clamped to the new text length. No UI-specific cursor reset or
new native control was introduced.

The instrumented regression covers inactive replacement, focused replacement and
an explicit selection range followed by a value change. It does not itself prove
final post-layout scroll position, RTL behavior or iOS parity. A showcase build
and visual verification of the initial long query remain required.

The targeted API 36 instrumented test passed:
`PamRendererInstrumentedTest#inactiveInputStartsAtLeadingTextAndHonorsControlledSelection`.
Its build/test run completed in 10 seconds. No iOS execution or release approval
is implied by this Android result.
