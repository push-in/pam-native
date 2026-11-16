<?php

declare(strict_types=1);

use Pam\Native\AccessibilityCheckedState;
use Pam\Native\Internal\DevWarnings;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Text;

// AccessibilityCheckedState::None (0) is "no checked state", from PHP and templates.
$assert(
    Pressable::make(Text::make('Terms'))
        ->accessibilityChecked(AccessibilityCheckedState::None)
        ->properties()[PropKey::AccessibilityCheckedState->value] === 0,
    'accessibilityChecked(None) must send the renderers\' "no checked state" (0).',
);
$noneChecked = TemplateRenderer::render(TemplateCompiler::compile(
    '<Pressable accessibilityRole="checkbox" accessibilityCheckedState="none"><Text>Terms</Text></Pressable>',
), null, []);
$assert(
    $noneChecked->properties()[PropKey::AccessibilityCheckedState->value] === 0,
    'accessibilityCheckedState="none" must map to AccessibilityCheckedState::None.',
);

// PAM2302: accessibility attributes no renderer applies are reported at
// compile time: a development warning, a strict (release) build error.
$previousMode = getenv('PAM_NATIVE_MODE');
$previousStrict = getenv('PAM_NATIVE_STRICT');
$unknownA11yTemplate = '<Column>'
    ."\n".'<View accessibilityElementsHidden="true" :accessibilityState="$state"><Text>Decor</Text></View>'
    ."\n".'<Pressable aria-label="Close" @accessibilityTap="close"><Text>x</Text></Pressable>'
    ."\n".'</Column>';
$knownA11yTemplate = '<Column accessibilityRole="list" importantForAccessibility="yes">'
    .'<Pressable accessible="true" :accessibilityLabel="$label" accessibilityHint="Opens" accessibilityRole="adjustable"'
    .' accessibilityLiveRegion="polite" accessibilitySelected="true" accessibilityExpanded="false" accessibilityBusy="false"'
    .' accessibilityCheckedState="mixed" accessibilityValueMin="0" accessibilityValueMax="10" accessibilityValueNow="5"'
    .' accessibilityValueText="5 of 10" @accessibilityAction="act" data-accessibility-note="x"><Text>Row</Text></Pressable>'
    .'<Image source="a.png" accessibility-label="Photo" />'
    .'<ChatCard accessibilityFlavor="custom" />'
    .'</Column>';
try {
    putenv('PAM_NATIVE_MODE');
    putenv('PAM_NATIVE_STRICT');
    DevWarnings::reset();
    TemplateCompiler::compile($knownA11yTemplate, 'known-a11y.pam');
    $assert(
        DevWarnings::messages() === [],
        'Known accessibility attributes, data-* and component props must not warn.',
    );
    TemplateCompiler::compile($unknownA11yTemplate, 'unknown-a11y.pam');
    $warnings = implode("\n", DevWarnings::messages());
    $assert(
        count(DevWarnings::messages()) === 4
            && str_contains($warnings, 'PAM2302 View has no accessibility attribute accessibilityElementsHidden; use importantForAccessibility="no-hide-descendants" (unknown-a11y.pam:2:1)')
            && str_contains($warnings, 'PAM2302 View has no accessibility attribute :accessibilityState')
            && str_contains($warnings, 'PAM2302 Pressable has no accessibility attribute aria-label; use accessibilityLabel (unknown-a11y.pam:3:1)')
            && str_contains($warnings, 'PAM2302 Pressable has no accessibility attribute @accessibilityTap'),
        'Unknown accessibility attributes must warn in development with their location.',
    );
    DevWarnings::reset();

    putenv('PAM_NATIVE_STRICT=1');
    $strictError = null;
    try {
        TemplateCompiler::compile($unknownA11yTemplate, 'unknown-a11y.pam');
    } catch (RuntimeException $error) {
        $strictError = $error->getMessage();
    }
    $assert(
        $strictError === 'PAM2302 View has no accessibility attribute accessibilityElementsHidden;'
            .' use importantForAccessibility="no-hide-descendants" at unknown-a11y.pam:2:1.',
        'Unknown accessibility attributes must fail strict builds.',
    );
    TemplateCompiler::compile($knownA11yTemplate, 'known-a11y.pam');
} finally {
    putenv($previousMode === false ? 'PAM_NATIVE_MODE' : 'PAM_NATIVE_MODE='.$previousMode);
    putenv($previousStrict === false ? 'PAM_NATIVE_STRICT' : 'PAM_NATIVE_STRICT='.$previousStrict);
    DevWarnings::reset();
}

echo "ACCESSIBILITY_CONTRACT_OK checked-none pam2302-dev pam2302-strict\n";
