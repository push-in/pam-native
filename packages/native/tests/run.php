<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\AppState;
use Pam\Native\AccessibilityRole;
use Pam\Native\AccessibilityCheckedState;
use Pam\Native\AccessibilityImportance;
use Pam\Native\AccessibilityLiveRegion;
use Pam\Native\ActivityIndicatorSize;
use Pam\Native\AnimationKind;
use Pam\Native\Component;
use Pam\Native\EventKind;
use Pam\Native\FontStyle;
use Pam\Native\ImageCachePolicy;
use Pam\Native\ImageErrorEvent;
use Pam\Native\ImageFit;
use Pam\Native\ImageLoadEvent;
use Pam\Native\ImageProgressEvent;
use Pam\Native\ImageResizeMethod;
use Pam\Native\InputAutoCapitalize;
use Pam\Native\InputAutofillImportance;
use Pam\Native\InputContentSizeEvent;
use Pam\Native\InputKeyEvent;
use Pam\Native\InputMode;
use Pam\Native\InputSelectionEvent;
use Pam\Native\InputSubmitBehavior;
use Pam\Native\InputTextAlignVertical;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Internal\Wire;
use Pam\Native\KeyboardAvoidingBehavior;
use Pam\Native\MemoryPressure;
use Pam\Native\ModalAnimationType;
use Pam\Native\ModalOrientation;
use Pam\Native\ModalPresentation;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NodeKind;
use Pam\Native\PointerEvents;
use Pam\Native\PressEvent;
use Pam\Native\ReturnKeyType;
use Pam\Native\RefreshIndicatorSize;
use Pam\Native\SafeAreaMode;
use Pam\Native\ScrollKeyboardDismissMode;
use Pam\Native\ScrollOverScrollMode;
use Pam\Native\PositionType;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Plugin\PluginException;
use Pam\Native\PropKey;
use Pam\Native\Restorable;
use Pam\Native\State;
use Pam\Native\StatusBarAppearance;
use Pam\Native\Style;
use Pam\Native\TemplateRegistry;
use Pam\Native\TextDecoration;
use Pam\Native\TextBreakStrategy;
use Pam\Native\TextDataDetectorType;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextHyphenationFrequency;
use Pam\Native\TextTransform;
use Pam\Native\Theme;
use Pam\Native\UserInterfaceAppearance;
use Pam\Native\View;
use Pam\Native\WindowMetrics;
use Pam\Native\UI\Button;
use Pam\Native\UI\ActivityIndicator;
use Pam\Native\UI\Column;
use Pam\Native\UI\CustomView;
use Pam\Native\UI\FlatList;
use Pam\Native\UI\Input;
use Pam\Native\UI\Image;
use Pam\Native\UI\ImageBackground;
use Pam\Native\UI\KeyboardAvoidingView;
use Pam\Native\UI\Modal;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\RefreshControl;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\SectionList;
use Pam\Native\UI\StatusBar;
use Pam\Native\UI\Text;
use Pam\Native\UI\Toggle;
use Pam\Native\Tests\Fixtures\ExamplePluginProvider;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';

    if (!str_starts_with($class, $prefix)) {
        return;
    }

    $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';

    if (is_file($path)) {
        require $path;
    }
});

require __DIR__.'/Fixtures/ExamplePluginProvider.php';

final class TestDiagnostics
{
    /** @var list<string> */
    public static array $messages = [];

    /** @var array{requestId: int, module: string, method: string, payload: string}|null */
    public static ?array $moduleCall = null;
}

if (!function_exists('pam_native_error')) {
    function pam_native_error(string $message): void
    {
        TestDiagnostics::$messages[] = $message;
    }
}

if (!function_exists('pam_native_call')) {
    function pam_native_call(
        int $requestId,
        string $module,
        string $method,
        string $payload,
    ): void {
        TestDiagnostics::$moduleCall = [
            'requestId' => $requestId,
            'module' => $module,
            'method' => $method,
            'payload' => $payload,
        ];
    }
}

$stateDirectory = sys_get_temp_dir().'/pam-native-state-tests-'.getmypid();
putenv('PAM_NATIVE_STATE_DIR='.$stateDirectory);
if (is_file($stateDirectory.'/state.json')) {
    unlink($stateDirectory.'/state.json');
}

$assert = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};

foreach (NodeKind::cases() as $index => $kind) {
    $assert($kind->value === $index + 1, 'Node kinds must remain sequential protocol integers.');
}
foreach (PropKey::cases() as $index => $key) {
    $assert($key->value === $index + 1, 'Property keys must remain sequential protocol integers.');
}
foreach (EventKind::cases() as $index => $kind) {
    $assert($kind->value === $index + 1, 'Event kinds must remain sequential protocol integers.');
}

$repositoryRoot = dirname(__DIR__, 3);
$kotlinProtocol = file_get_contents(
    $repositoryRoot.'/android/app/src/main/java/dev/pam/nativeapp/protocol/PamProtocol.kt',
);
$rustProtocol = file_get_contents(
    $repositoryRoot.'/crates/pam-native-protocol/src/lib.rs',
);
if ($kotlinProtocol === false || $rustProtocol === false) {
    throw new RuntimeException('Cross-language protocol sources must be readable.');
}
if (
    preg_match(
        '/enum class PropKey\\(val value: Int\\) \\{(?<body>.*?)\\n\\}/s',
        $kotlinProtocol,
        $kotlinBlock,
    ) !== 1
    || preg_match(
        '/pub enum PropKey \\{(?<body>.*?)\\n\\}/s',
        $rustProtocol,
        $rustBlock,
    ) !== 1
    || preg_match(
        '/enum class EventKind\\(val value: Int\\) \\{(?<body>.*?)\\n\\}/s',
        $kotlinProtocol,
        $kotlinEventBlock,
    ) !== 1
) {
    throw new RuntimeException('Cross-language protocol enums must be discoverable.');
}
preg_match_all(
    '/^\\s*([A-Z][A-Z0-9_]*)\\((\\d+)\\)[,;]/m',
    $kotlinBlock['body'],
    $kotlinValues,
);
preg_match_all(
    '/^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(\\d+),/m',
    $rustBlock['body'],
    $rustValues,
);
preg_match_all(
    '/^\\s*([A-Z][A-Z0-9_]*)\\((\\d+)\\)[,;]/m',
    $kotlinEventBlock['body'],
    $kotlinEventValues,
);
$phpPropertyValues = array_map(
    static fn (PropKey $key): int => $key->value,
    PropKey::cases(),
);
$kotlinPropertyValues = array_map('intval', $kotlinValues[2]);
$rustPropertyValues = array_map('intval', $rustValues[2]);
$normalizeProtocolName = static function (string $name): string {
    $normalized = preg_replace(
        ['/([a-z0-9])([A-Z])/', '/([A-Z])([A-Z][a-z])/'],
        ['$1_$2', '$1_$2'],
        $name,
    );
    if ($normalized === null) {
        throw new RuntimeException('Protocol enum name could not be normalized.');
    }

    return strtoupper($normalized);
};
$phpPropertyNames = array_map(
    static fn (PropKey $key): string => $normalizeProtocolName($key->name),
    PropKey::cases(),
);
$rustPropertyNames = array_map($normalizeProtocolName, $rustValues[1]);
$assert(
    $kotlinPropertyValues === $phpPropertyValues
        && $rustPropertyValues === $phpPropertyValues
        && $kotlinValues[1] === $phpPropertyNames
        && $rustPropertyNames === $phpPropertyNames,
    'PHP, Kotlin, and Rust property protocol enums must remain byte-for-byte aligned.',
);
$phpEventValues = array_map(
    static fn (EventKind $kind): int => $kind->value,
    EventKind::cases(),
);
$phpEventNames = array_map(
    static fn (EventKind $kind): string => $normalizeProtocolName($kind->name),
    EventKind::cases(),
);
$assert(
    array_map('intval', $kotlinEventValues[2]) === $phpEventValues
        && $kotlinEventValues[1] === $phpEventNames,
    'PHP and Kotlin event protocol enums must remain byte-for-byte aligned.',
);

foreach ([
    AnimationKind::cases(),
    AccessibilityRole::cases(),
    AccessibilityCheckedState::cases(),
    AccessibilityImportance::cases(),
    AccessibilityLiveRegion::cases(),
    FontStyle::cases(),
    ImageCachePolicy::cases(),
    ImageFit::cases(),
    ImageResizeMethod::cases(),
    InputAutoCapitalize::cases(),
    InputAutofillImportance::cases(),
    InputMode::cases(),
    InputSubmitBehavior::cases(),
    InputTextAlignVertical::cases(),
    PointerEvents::cases(),
    PositionType::cases(),
    ReturnKeyType::cases(),
    RefreshIndicatorSize::cases(),
    SafeAreaMode::cases(),
    TextBreakStrategy::cases(),
    TextDataDetectorType::cases(),
    TextDecoration::cases(),
    TextEllipsizeMode::cases(),
    TextHyphenationFrequency::cases(),
    TextTransform::cases(),
] as $cases) {
    foreach ($cases as $index => $case) {
        $assert(
            $case->value === $index + 1,
            $case::class.' values must remain sequential protocol integers.',
        );
    }
}

$tree = Screen::make(
    Column::make(
        Text::make('Pam Native')->key('title'),
        Button::make('Tap')->onPress(static function (): void {
        }),
        Input::make('value')->placeholder('Type here'),
    )->style(new Style(padding: 16.0, gap: 8.0)),
);
$inputSelection = null;
$inputContentSize = null;
$inputKey = null;
$inputEndValue = null;
$inputElement = Input::make('hello')
    ->multiline()
    ->editable(false)
    ->autoCorrect(false)
    ->autoCapitalize(InputAutoCapitalize::Words)
    ->caretHidden()
    ->contextMenuHidden()
    ->cursorColor(0xFF2563EB)
    ->disableFullscreenUi()
    ->autofillImportance(InputAutofillImportance::Yes)
    ->inputMode(InputMode::Email)
    ->minLines(3)
    ->selectTextOnFocus()
    ->selection(1, 4)
    ->showSoftInputOnFocus(false)
    ->submitBehavior(InputSubmitBehavior::Submit)
    ->textAlignVertical(InputTextAlignVertical::Top)
    ->returnKeyLabel('Publish')
    ->scrollEnabled(false)
    ->underlineColor(0x00000000)
    ->onEndEditing(
        static function (string $value) use (&$inputEndValue): void {
            $inputEndValue = $value;
        },
    )
    ->onSelectionChange(
        static function (InputSelectionEvent $event) use (&$inputSelection): void {
            $inputSelection = $event;
        },
    )
    ->onContentSizeChange(
        static function (InputContentSizeEvent $event) use (
            &$inputContentSize,
        ): void {
            $inputContentSize = $event;
        },
    )
    ->onKeyPress(
        static function (InputKeyEvent $event) use (&$inputKey): void {
            $inputKey = $event;
        },
    );
$inputElement->events()[EventKind::InputEndEditing->value]('finished');
$inputElement->events()[EventKind::InputSelectionChange->value](Wire::map([
    'start' => 1,
    'end' => 4,
]));
$inputElement->events()[EventKind::InputContentSizeChange->value](Wire::map([
    'width' => 240.0,
    'height' => 96.0,
]));
$inputElement->events()[EventKind::InputKeyPress->value](Wire::map([
    'key' => 'Enter',
]));
$assert(
    $inputElement->properties()[PropKey::InputEditable->value] === false
        && $inputElement->properties()[PropKey::InputAutoCorrect->value]
            === false
        && $inputElement->properties()[PropKey::InputAutoCapitalize->value]
            === InputAutoCapitalize::Words->value
        && $inputElement->properties()[PropKey::InputAutofillImportance->value]
            === InputAutofillImportance::Yes->value
        && $inputElement->properties()[PropKey::InputMode->value]
            === InputMode::Email->value
        && $inputElement->properties()[PropKey::InputMinLines->value] === 3
        && $inputElement->properties()[PropKey::InputSelectionStart->value]
            === 1
        && $inputElement->properties()[PropKey::InputSelectionEnd->value] === 4
        && $inputElement->properties()[PropKey::InputSubmitBehavior->value]
            === InputSubmitBehavior::Submit->value
        && $inputElement->properties()[PropKey::InputTextAlignVertical->value]
            === InputTextAlignVertical::Top->value
        && $inputElement->properties()[PropKey::InputReturnKeyLabel->value]
            === 'Publish'
        && $inputElement->properties()[PropKey::InputScrollEnabled->value]
            === false
        && $inputEndValue === 'finished'
        && $inputSelection instanceof InputSelectionEvent
        && $inputSelection->end === 4
        && $inputContentSize instanceof InputContentSizeEvent
        && $inputContentSize->height === 96.0
        && $inputKey instanceof InputKeyEvent
        && $inputKey->key === 'Enter',
    'Input helpers must preserve native editing, selection, keyboard, size and key behavior.',
);
$pressInEvent = null;
$pressOutEvent = null;
$pressMoveEvent = null;
$pressableElement = Pressable::make(Text::make('Open'))
    ->ripple(0x66010203, true, 24.0, true, 0.5)
    ->pressedOpacity(0.7)
    ->hitSlopEdges(4.0, 6.0, 8.0, 10.0)
    ->pressRetentionEdges(12.0, 14.0, 16.0, 18.0)
    ->delayLongPress(420)
    ->delayPressIn(25)
    ->delayPressOut(40)
    ->androidDisableSound()
    ->onPressIn(static function (PressEvent $event) use (&$pressInEvent): void {
        $pressInEvent = $event;
    })
    ->onPressOut(static function (PressEvent $event) use (&$pressOutEvent): void {
        $pressOutEvent = $event;
    })
    ->onPressMove(static function (PressEvent $event) use (&$pressMoveEvent): void {
        $pressMoveEvent = $event;
    });
$pressPayload = Wire::map([
    'x' => 11.5,
    'y' => 12.5,
    'pageX' => 21.5,
    'pageY' => 22.5,
    'timestamp' => 1234,
    'pointerId' => 7,
]);
$pressableElement->events()[EventKind::PressIn->value]($pressPayload);
$pressableElement->events()[EventKind::PressOut->value]($pressPayload);
$pressableElement->events()[EventKind::PressMove->value]($pressPayload);
$assert(
    $pressableElement->properties()[PropKey::HitSlopLeft->value] === 4.0
        && $pressableElement->properties()[PropKey::HitSlopBottom->value] === 10.0
        && $pressableElement->properties()[PropKey::PressRetentionRight->value] === 16.0
        && $pressableElement->properties()[PropKey::PressDelayLongMs->value] === 420
        && $pressableElement->properties()[PropKey::PressDelayInMs->value] === 25
        && $pressableElement->properties()[PropKey::PressDelayOutMs->value] === 40
        && $pressableElement->properties()[PropKey::PressAndroidDisableSound->value] === true
        && $pressableElement->properties()[PropKey::RippleBorderless->value] === true
        && $pressableElement->properties()[PropKey::RippleRadius->value] === 24.0
        && $pressableElement->properties()[PropKey::RippleForeground->value] === true
        && $pressableElement->properties()[PropKey::RippleAlpha->value] === 0.5
        && $pressInEvent instanceof PressEvent
        && $pressInEvent->x === 11.5
        && $pressOutEvent instanceof PressEvent
        && $pressMoveEvent instanceof PressEvent
        && $pressMoveEvent->pointerId === 7,
    'Pressable helpers must preserve complete native gesture geometry and lifecycle events.',
);
$accessibilityElement = Text::make('Upload')
    ->accessibilityRole(AccessibilityRole::ProgressBar)
    ->accessible()
    ->accessibilityLiveRegion(AccessibilityLiveRegion::Polite)
    ->accessibilityImportance(AccessibilityImportance::Yes)
    ->accessibilityExpanded(false)
    ->accessibilityBusy(true)
    ->accessibilityChecked(AccessibilityCheckedState::Mixed)
    ->accessibilityValue(0.0, 100.0, 40.0, '40 percent');
$assert(
    $accessibilityElement->properties()[PropKey::Accessible->value] === true
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityLiveRegion->value]
            === AccessibilityLiveRegion::Polite->value
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityImportance->value]
            === AccessibilityImportance::Yes->value
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityExpanded->value] === false
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityBusy->value] === true
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityCheckedState->value]
            === AccessibilityCheckedState::Mixed->value
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityValueNow->value] === 40.0
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityValueText->value]
            === '40 percent',
    'Accessibility state and range helpers must use fixed protocol properties.',
);

$safeAreaElement = SafeAreaView::make(Text::make('Inset content'))
    ->edges(top: true, right: false, bottom: true, left: false)
    ->mode(SafeAreaMode::Margin);
$assert(
    $safeAreaElement->properties()[PropKey::SafeAreaTop->value] === true
        && $safeAreaElement->properties()[PropKey::SafeAreaRight->value] === false
        && $safeAreaElement
            ->properties()[PropKey::SafeAreaBottomEdge->value] === true
        && $safeAreaElement->properties()[PropKey::SafeAreaLeft->value] === false
        && $safeAreaElement->properties()[PropKey::SafeAreaMode->value]
            === SafeAreaMode::Margin->value,
    'Safe area helpers must preserve per-edge and mode protocol properties.',
);

$keyboardAvoidingElement = KeyboardAvoidingView::make(
    Text::make('Keyboard content'),
    KeyboardAvoidingBehavior::Padding,
)->verticalOffset(24.0)->avoidingEnabled(false);
$assert(
    $keyboardAvoidingElement->properties()[PropKey::KeyboardBehavior->value]
        === KeyboardAvoidingBehavior::Padding->value
        && $keyboardAvoidingElement
            ->properties()[PropKey::KeyboardVerticalOffset->value] === 24.0
        && $keyboardAvoidingElement
            ->properties()[PropKey::KeyboardAvoidingEnabled->value] === false,
    'Keyboard avoidance helpers must preserve behavior, offset and enabled state.',
);

$refreshColors = [0xff112233, 0xff445566];
$refreshElement = RefreshControl::make(Text::make('Refresh content'), true)
    ->colors(...$refreshColors)
    ->progressBackgroundColor(0xfff8fafc)
    ->progressViewOffset(16.0)
    ->enabled(false)
    ->size(RefreshIndicatorSize::Large);
$assert(
    $refreshElement->properties()[PropKey::Refreshing->value] === true
        && $refreshElement->properties()[PropKey::RefreshColors->value]
            === implode(',', $refreshColors)
        && $refreshElement
            ->properties()[PropKey::RefreshProgressBackgroundColor->value]
            === 0xfff8fafc
        && $refreshElement
            ->properties()[PropKey::RefreshProgressViewOffset->value] === 16.0
        && $refreshElement->properties()[PropKey::Enabled->value] === false
        && $refreshElement->properties()[PropKey::RefreshIndicatorSize->value]
            === RefreshIndicatorSize::Large->value,
    'Refresh control helpers must preserve Android indicator and gesture properties.',
);

$textElement = Text::make('Selectable https://pam.dev')
    ->numberOfLines(2)
    ->selectable()
    ->selectionColor(0x66112233)
    ->ellipsize(TextEllipsizeMode::Middle)
    ->allowFontScaling()
    ->maxFontSizeMultiplier(1.5)
    ->adjustsFontSizeToFit(minimumScale: 0.5)
    ->breakStrategy(TextBreakStrategy::Balanced)
    ->hyphenation(TextHyphenationFrequency::Full)
    ->dataDetector(TextDataDetectorType::Link);
$assert(
    $textElement->properties()[PropKey::NumberOfLines->value] === 2
        && $textElement->properties()[PropKey::TextSelectable->value] === true
        && $textElement->properties()[PropKey::SelectionColor->value] === 0x66112233
        && $textElement->properties()[PropKey::TextEllipsizeMode->value]
            === TextEllipsizeMode::Middle->value
        && $textElement->properties()[PropKey::TextAllowFontScaling->value] === true
        && $textElement
            ->properties()[PropKey::TextMaxFontSizeMultiplier->value] === 1.5
        && $textElement
            ->properties()[PropKey::TextAdjustsFontSizeToFit->value] === true
        && $textElement->properties()[PropKey::TextMinimumFontScale->value] === 0.5
        && $textElement->properties()[PropKey::TextBreakStrategy->value]
            === TextBreakStrategy::Balanced->value
        && $textElement->properties()[PropKey::TextHyphenationFrequency->value]
            === TextHyphenationFrequency::Full->value
        && $textElement->properties()[PropKey::TextDataDetectorType->value]
            === TextDataDetectorType::Link->value,
    'Text helpers must preserve selection, fitting, breaking and detector properties.',
);

$statusBarElement = StatusBar::make(
    0x80112233,
    StatusBarAppearance::Light,
    true,
)->animated()->translucent();
$assert(
    $statusBarElement->properties()[PropKey::StatusBarColor->value] === 0x80112233
        && $statusBarElement->properties()[PropKey::StatusBarStyle->value]
            === StatusBarAppearance::Light->value
        && $statusBarElement->properties()[PropKey::StatusBarHidden->value] === true
        && $statusBarElement->properties()[PropKey::StatusBarAnimated->value] === true
        && $statusBarElement->properties()[PropKey::StatusBarTranslucent->value] === true,
    'Status bar helpers must preserve color, style, visibility and edge-to-edge properties.',
);
$modalShown = false;
$modalDismissed = false;
$modalRequestedClose = false;
$modalOrientation = null;
$modalElement = Modal::make(
    Text::make('Modal content'),
    true,
    ModalPresentation::Sheet,
)
    ->animationType(ModalAnimationType::Slide)
    ->backdropColor(0xFF102030)
    ->transparent()
    ->hardwareAccelerated()
    ->navigationBarTranslucent()
    ->statusBarTranslucent()
    ->allowSwipeDismissal()
    ->onRequestClose(static function () use (&$modalRequestedClose): void {
        $modalRequestedClose = true;
    })
    ->onShow(static function () use (&$modalShown): void {
        $modalShown = true;
    })
    ->onDismiss(static function () use (&$modalDismissed): void {
        $modalDismissed = true;
    })
    ->onOrientationChange(
        static function (ModalOrientation $orientation) use (
            &$modalOrientation,
        ): void {
            $modalOrientation = $orientation;
        },
    );
$modalElement->events()[EventKind::ModalRequestClose->value]('');
$modalElement->events()[EventKind::ModalShow->value]('');
$modalElement->events()[EventKind::ModalDismiss->value]('');
$modalElement->events()[EventKind::ModalOrientationChange->value](
    (string) ModalOrientation::Landscape->value,
);
$assert(
    $modalElement->properties()[PropKey::ModalPresentation->value]
        === ModalPresentation::Sheet->value
        && $modalElement->properties()[PropKey::ModalAnimationType->value]
            === ModalAnimationType::Slide->value
        && $modalElement->properties()[PropKey::ModalBackdropColor->value]
            === 0xFF102030
        && $modalElement->properties()[PropKey::ModalTransparent->value] === true
        && $modalElement
            ->properties()[PropKey::ModalHardwareAccelerated->value] === true
        && $modalElement
            ->properties()[PropKey::ModalNavigationBarTranslucent->value] === true
        && $modalElement
            ->properties()[PropKey::ModalStatusBarTranslucent->value] === true
        && $modalElement
            ->properties()[PropKey::ModalAllowSwipeDismissal->value] === true
        && $modalRequestedClose
        && $modalShown
        && $modalDismissed
        && $modalOrientation === ModalOrientation::Landscape,
    'Modal helpers must preserve window configuration and typed lifecycle events.',
);

$scrollElement = Scroll::make(Text::make('Scrollable'))
    ->horizontal()
    ->contentOffset(24.0, 8.0)
    ->fillViewport(false)
    ->overScrollMode(ScrollOverScrollMode::Never)
    ->nestedScrollEnabled()
    ->fadingEdgeLength(12.0)
    ->persistentScrollbar()
    ->pagingEnabled()
    ->snapToInterval(80.0)
    ->decelerationRate(0.9)
    ->keyboardDismissMode(ScrollKeyboardDismissMode::OnDrag)
    ->scrollEnabled(false)
    ->showsIndicator()
    ->onScroll(static function (): void {
    });
$assert(
    $scrollElement->properties()[PropKey::ScrollHorizontal->value] === true
        && $scrollElement->properties()[PropKey::ScrollContentOffsetX->value] === 24.0
        && $scrollElement->properties()[PropKey::ScrollContentOffsetY->value] === 8.0
        && $scrollElement->properties()[PropKey::ScrollFillViewport->value] === false
        && $scrollElement->properties()[PropKey::ScrollOverScrollMode->value]
            === ScrollOverScrollMode::Never->value
        && $scrollElement->properties()[PropKey::ScrollNestedEnabled->value] === true
        && $scrollElement->properties()[PropKey::ScrollFadingEdgeLength->value] === 12.0
        && $scrollElement
            ->properties()[PropKey::ScrollPersistentScrollbar->value] === true
        && $scrollElement->properties()[PropKey::ScrollPagingEnabled->value] === true
        && $scrollElement->properties()[PropKey::ScrollSnapInterval->value] === 80.0
        && $scrollElement->properties()[PropKey::ScrollDecelerationRate->value] === 0.9
        && $scrollElement->properties()[PropKey::ScrollKeyboardDismissMode->value]
            === ScrollKeyboardDismissMode::OnDrag->value
        && isset($scrollElement->events()[EventKind::Scroll->value]),
    'Scroll helpers must preserve Android-owned orientation, momentum and viewport behavior.',
);

$indicatorElement = ActivityIndicator::make(false)
    ->hidesWhenStopped(false)
    ->size(ActivityIndicatorSize::Large)
    ->color(0xFF2563EB);
$assert(
    $indicatorElement->properties()[PropKey::ActivityAnimating->value] === false
        && $indicatorElement
            ->properties()[PropKey::ActivityHidesWhenStopped->value] === false
        && $indicatorElement->properties()[PropKey::ActivitySize->value] === 36.0
        && $indicatorElement->properties()[PropKey::ProgressColor->value] === 0xFF2563EB,
    'Activity indicator helpers must preserve animation, stopped visibility, size and tint.',
);

$toggleElement = Toggle::make(true)
    ->trackColors(0xFF64748B, 0xFF2563EB)
    ->thumbColor(0xFFFFFFFF);
$assert(
    $toggleElement->properties()[PropKey::Checked->value] === true
        && $toggleElement
            ->properties()[PropKey::SwitchTrackColorFalse->value] === 0xFF64748B
        && $toggleElement
            ->properties()[PropKey::SwitchTrackColorTrue->value] === 0xFF2563EB
        && $toggleElement->properties()[PropKey::SwitchThumbColor->value] === 0xFFFFFFFF,
    'Switch helpers must preserve checked, track and thumb colors.',
);

$imageStarted = false;
$imageProgress = null;
$imageLoaded = null;
$imageError = null;
$imageEnded = false;
$imageElement = Image::make('https://example.test/photo.jpg')
    ->fit(ImageFit::Repeat)
    ->defaultSource('asset://placeholder.png')
    ->loadingIndicatorSource('asset://loading.png')
    ->fadeDuration(180)
    ->resizeMethod(ImageResizeMethod::Resize)
    ->resizeMultiplier(2.0)
    ->progressiveRendering()
    ->cache(ImageCachePolicy::ForceCache)
    ->overlayColor(0xFF0F172A)
    ->sourceSet(
        'https://example.test/photo.png 1x, '.
        'https://example.test/photo@2x.png 2x',
    )
    ->headers(['X-Image-Variant' => 'retina'])
    ->onLoadStart(static function () use (&$imageStarted): void {
        $imageStarted = true;
    })
    ->onProgress(
        static function (ImageProgressEvent $event) use (&$imageProgress): void {
            $imageProgress = $event;
        },
    )
    ->onLoad(
        static function (ImageLoadEvent $event) use (&$imageLoaded): void {
            $imageLoaded = $event;
        },
    )
    ->onError(
        static function (ImageErrorEvent $event) use (&$imageError): void {
            $imageError = $event;
        },
    )
    ->onLoadEnd(static function () use (&$imageEnded): void {
        $imageEnded = true;
    });
$imageElement->events()[EventKind::ImageLoadStart->value]('');
$imageElement->events()[EventKind::ImageProgress->value](Wire::map([
    'loaded' => 65_536,
    'total' => 131_072,
]));
$imageElement->events()[EventKind::ImageLoad->value](Wire::map([
    'uri' => 'https://example.test/photo@2x.png',
    'width' => 800.0,
    'height' => 600.0,
]));
$imageElement->events()[EventKind::ImageError->value](Wire::map([
    'error' => 'offline',
]));
$imageElement->events()[EventKind::ImageLoadEnd->value]('');
$imageBackground = ImageBackground::make(
    'asset://hero.jpg',
    Text::make('Overlay'),
)->tint(0xFFFFFFFF);
$assert(
    $imageElement->properties()[PropKey::ImageFit->value]
        === ImageFit::Repeat->value
        && $imageElement->properties()[PropKey::ImageDefaultSource->value]
            === 'asset://placeholder.png'
        && $imageElement
            ->properties()[PropKey::ImageLoadingIndicatorSource->value]
            === 'asset://loading.png'
        && $imageElement->properties()[PropKey::ImageFadeDurationMs->value]
            === 180
        && $imageElement->properties()[PropKey::ImageResizeMethod->value]
            === ImageResizeMethod::Resize->value
        && $imageElement->properties()[PropKey::ImageResizeMultiplier->value]
            === 2.0
        && $imageElement
            ->properties()[PropKey::ImageProgressiveRenderingEnabled->value]
            === true
        && $imageElement->properties()[PropKey::ImageCachePolicy->value]
            === ImageCachePolicy::ForceCache->value
        && $imageElement->properties()[PropKey::ImageOverlayColor->value]
            === 0xFF0F172A
        && $imageElement->properties()[PropKey::ImageRequestHeaders->value]
            === 'X-Image-Variant:retina'
        && $imageStarted
        && $imageProgress instanceof ImageProgressEvent
        && $imageProgress->loaded === 65_536
        && $imageLoaded instanceof ImageLoadEvent
        && $imageLoaded->width === 800.0
        && $imageError instanceof ImageErrorEvent
        && $imageError->message === 'offline'
        && $imageEnded
        && $imageBackground->children()[0]->kind() === NodeKind::Text
        && $imageBackground->properties()[PropKey::TintColor->value]
            === 0xFFFFFFFF,
    'Image helpers must preserve native loading, cache, resize, lifecycle and background behavior.',
);

$nativeControlTemplate = TemplateRenderer::render(
    TemplateCompiler::compile(<<<'PAM'
<Screen>
    <ScrollView
        horizontal="true"
        contentOffset="24"
        pagingEnabled="true"
        snapToInterval="80"
        overScrollMode="never"
        keyboardDismissMode="on-drag"
    >
        <Text>Scrollable</Text>
    </ScrollView>
    <ActivityIndicator
        animating="false"
        hidesWhenStopped="false"
        size="large"
        progressColor="#2563eb"
    />
    <Switch
        checked="true"
        trackColorFalse="#64748b"
        trackColorTrue="#2563eb"
        thumbColor="#ffffff"
    />
</Screen>
PAM),
    null,
    [],
);
$templateScroll = $nativeControlTemplate->children()[0];
$templateIndicator = $nativeControlTemplate->children()[1];
$templateSwitch = $nativeControlTemplate->children()[2];
$assert(
    $templateScroll->properties()[PropKey::ScrollHorizontal->value] === true
        && $templateScroll
            ->properties()[PropKey::ScrollContentOffsetX->value] === 24.0
        && $templateScroll->properties()[PropKey::ScrollPagingEnabled->value] === true
        && $templateScroll->properties()[PropKey::ScrollSnapInterval->value] === 80
        && $templateIndicator->properties()[PropKey::ActivityAnimating->value] === false
        && $templateIndicator
            ->properties()[PropKey::ActivityHidesWhenStopped->value] === false
        && $templateIndicator->properties()[PropKey::ActivitySize->value] === 36.0
        && $templateSwitch
            ->properties()[PropKey::SwitchTrackColorFalse->value] === 0xFF64748B
        && $templateSwitch
            ->properties()[PropKey::SwitchTrackColorTrue->value] === 0xFF2563EB
        && $templateSwitch->properties()[PropKey::SwitchThumbColor->value] === 0xFFFFFFFF,
    'Native control tags must map to the same typed scroll, indicator and switch protocol.',
);

$listElement = FlatList::make(['One', 'Two', 'Three'])
    ->rowHeight(56.0)
    ->prefetch(8)
    ->columns(2)
    ->inverted()
    ->initialScrollIndex(1)
    ->removeClippedSubviews(false)
    ->scrollEnabled(false)
    ->showsIndicator(false)
    ->onScroll(static function (): void {
    })
    ->onEndReached(static function (): void {
    }, 0.25);
$assert(
    $listElement->properties()[PropKey::ListRowHeight->value] === 56.0
        && $listElement->properties()[PropKey::ListPrefetch->value] === 8
        && $listElement->properties()[PropKey::ListNumColumns->value] === 2
        && $listElement->properties()[PropKey::ListInverted->value] === true
        && $listElement->properties()[PropKey::ListInitialScrollIndex->value] === 1
        && $listElement
            ->properties()[PropKey::ListRemoveClippedSubviews->value] === false
        && $listElement->properties()[PropKey::ScrollEnabled->value] === false
        && $listElement->properties()[PropKey::ShowsScrollIndicator->value] === false
        && $listElement->properties()[PropKey::EndReachedThreshold->value] === 0.25
        && isset($listElement->events()[EventKind::Scroll->value])
        && isset($listElement->events()[EventKind::EndReached->value]),
    'List helpers must preserve recycling, prefetch, layout and scroll event properties.',
);

$sectionListElement = SectionList::make([
    'Frameworks' => ['Laravel', 'PAM'],
])
    ->rowHeight(52.0)
    ->prefetch(6)
    ->horizontal()
    ->columns(2)
    ->inverted()
    ->initialScrollIndex(1)
    ->removeClippedSubviews(false);
$assert(
    $sectionListElement->properties()[PropKey::ListRowHeight->value] === 52.0
        && $sectionListElement->properties()[PropKey::ListPrefetch->value] === 6
        && $sectionListElement->properties()[PropKey::ListHorizontal->value] === true
        && $sectionListElement->properties()[PropKey::ListNumColumns->value] === 2
        && $sectionListElement->properties()[PropKey::ListInverted->value] === true
        && $sectionListElement
            ->properties()[PropKey::ListInitialScrollIndex->value] === 1
        && $sectionListElement
            ->properties()[PropKey::ListRemoveClippedSubviews->value] === false,
    'Section list helpers must preserve native recycler configuration.',
);

$first = (new TreeEncoder())->encode($tree);
$second = (new TreeEncoder())->encode($tree);
$firstFrame = $first['frame'];
$secondFrame = $second['frame'];

if ($firstFrame === null || $secondFrame === null) {
    throw new RuntimeException('Fresh encoders must produce complete tree frames.');
}

$assert($firstFrame === $secondFrame, 'Tree encoding must be deterministic.');
$assert(str_starts_with($firstFrame, 'PNT1'), 'Tree frame magic is missing.');
$assert(count($first['callbacks']) === 1, 'Event callback was not registered.');

$nativeContainer = CustomView::make(
    'community.container',
    ['axis' => 1],
    Text::make('Native child'),
);
$assert(
    count($nativeContainer->children()) === 1,
    'Custom native view containers must retain declarative children.',
);

$capturedParentVariants = null;
TemplateRegistry::reset();
TemplateRegistry::component(
    'VariantParent',
    static fn (array $props, array $children): \Pam\Native\Renderable =>
        Column::make(...$children),
);
TemplateRegistry::component(
    'VariantChild',
    static function (array $props) use (&$capturedParentVariants): \Pam\Native\Renderable {
        $capturedParentVariants = $props['__parentVariants'] ?? null;

        return Text::make('Child');
    },
);
$variantTemplate = TemplateCompiler::compile(
    '<VariantParent variant="outline" size="lg"><VariantChild /></VariantParent>',
);
TemplateRenderer::render($variantTemplate, null, []);
$assert(
    $capturedParentVariants === ['variant' => 'outline', 'size' => 'lg'],
    'Custom template components must inherit parent variant context.',
);
$gallery = [
    ['url' => 'file:///mountain.jpg', 'alt' => 'Mountain'],
    ['url' => 'file:///ocean.jpg', 'alt' => 'Ocean'],
];
$variantTemplate = TemplateCompiler::compile(
    '<VariantParent images="$gallery"><VariantChild /></VariantParent>',
);
TemplateRenderer::render($variantTemplate, null, ['gallery' => $gallery]);
$assert(
    $capturedParentVariants === ['images' => $gallery],
    'Bound declarative arrays must remain available to compound child components.',
);
$capturedClassName = null;
TemplateRegistry::component(
    'SemanticClasses',
    static function (
        array $props,
    ) use (&$capturedClassName): \Pam\Native\Renderable {
        $capturedClassName = $props['className'] ?? null;

        return Column::make();
    },
);
TemplateRegistry::styleResolver(
    static fn (string $class): ?array => $class === 'grid-cols-2'
        ? []
        : null,
);
$semanticClassTemplate = TemplateCompiler::compile(
    '<SemanticClasses class="grid-cols-2 gap-3" />',
);
$semanticClassElement = TemplateRenderer::render(
    $semanticClassTemplate,
    null,
    [],
);
$assert(
    $capturedClassName === 'grid-cols-2 gap-3'
        && ($semanticClassElement->properties()[PropKey::Gap->value] ?? null)
            === 12.0,
    'Registered template components must receive className before visual utilities are applied.',
);
$coreRoleElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Text accessibilityRole="header">Accessible heading</Text>',
    ),
    null,
    [],
);
$assert(
    $coreRoleElement->properties()[PropKey::AccessibilityRole->value]
        === AccessibilityRole::Header->value,
    'Core tag accessibility roles must compile to sequential protocol integers.',
);
$pressScope = new class {
    public ?PressEvent $move = null;

    public function moved(PressEvent $event): void
    {
        $this->move = $event;
    }
};
$corePressableElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Pressable hitSlopLeft="4" hitSlopBottom="10" '
        .'pressRetentionOffset="12" delayLongPress="420" '
        .'androidDisableSound="true" rippleAlpha="0.5" '
        .'on:pressMove="moved"><Text>Open</Text></Pressable>',
    ),
    $pressScope,
    [],
);
$corePressableElement->events()[EventKind::PressMove->value](Wire::map([
    'x' => 2.0,
    'y' => 3.0,
    'pageX' => 12.0,
    'pageY' => 13.0,
    'timestamp' => 99,
    'pointerId' => 1,
]));
$assert(
    $corePressableElement->properties()[PropKey::HitSlopLeft->value] === 4.0
        && $corePressableElement
            ->properties()[PropKey::HitSlopBottom->value] === 10.0
        && $corePressableElement
            ->properties()[PropKey::PressRetentionLeft->value] === 12.0
        && $corePressableElement
            ->properties()[PropKey::PressRetentionBottom->value] === 12.0
        && $corePressableElement
            ->properties()[PropKey::PressDelayLongMs->value] === 420
        && $corePressableElement
            ->properties()[PropKey::PressAndroidDisableSound->value] === true
        && $corePressableElement
            ->properties()[PropKey::RippleAlpha->value] === 0.5
        && $pressScope->move instanceof PressEvent
        && $pressScope->move->pageX === 12.0,
    'Core Pressable tags must retain gesture properties and typed move events.',
);
$modalScope = new class {
    public ?ModalOrientation $orientation = null;

    public function oriented(ModalOrientation $orientation): void
    {
        $this->orientation = $orientation;
    }
};
$coreModalElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Modal visible="true" presentation="fullScreen" '
        .'animationType="fade" transparent="true" '
        .'hardwareAccelerated="true" statusBarTranslucent="true" '
        .'on:orientationChange="oriented"><Text>Dialog</Text></Modal>',
    ),
    $modalScope,
    [],
);
$coreModalElement->events()[EventKind::ModalOrientationChange->value](
    (string) ModalOrientation::Landscape->value,
);
$assert(
    $coreModalElement->properties()[PropKey::ModalPresentation->value]
        === ModalPresentation::FullScreen->value
        && $coreModalElement->properties()[PropKey::ModalAnimationType->value]
            === ModalAnimationType::Fade->value
        && $coreModalElement->properties()[PropKey::ModalTransparent->value]
            === true
        && $coreModalElement
            ->properties()[PropKey::ModalHardwareAccelerated->value] === true
        && $coreModalElement
            ->properties()[PropKey::ModalStatusBarTranslucent->value] === true
        && $modalScope->orientation === ModalOrientation::Landscape,
    'Core Modal tags must preserve window properties and typed orientation events.',
);
$eventScope = new class {
    public string $submission = '';

    public function submit(string $payload): void
    {
        $this->submission = $payload;
    }
};
TemplateRegistry::component(
    'AdaptedEvent',
    static fn (array $_props, array $children): \Pam\Native\Renderable =>
        Column::make(...$children),
);
TemplateRegistry::eventAdapter(
    'AdaptedEvent',
    static fn (
        EventKind $_kind,
        Closure $handler,
        array $props,
    ): Closure => static function (string $payload) use (
        $handler,
        $props,
    ): void {
        $prefix = is_string($props['prefix'] ?? null)
            ? $props['prefix']
            : '';
        $handler($prefix.$payload);
    },
);
$adaptedEventTemplate = TemplateCompiler::compile(
    '<AdaptedEvent prefix="ui:" on:submit="submit" />',
);
$adaptedEventElement = TemplateRenderer::render(
    $adaptedEventTemplate,
    $eventScope,
    [],
);
$adaptedEventElement->events()[EventKind::Submit->value]('payload');
$assert(
    $eventScope->submission === 'ui:payload',
    'Registered components must be able to adapt declarative event contracts.',
);
$capturedEventContexts = null;
TemplateRegistry::component(
    'EventParent',
    static fn (array $_props, array $children): \Pam\Native\Renderable =>
        Column::make(...$children),
);
TemplateRegistry::component(
    'EventChild',
    static function (
        array $props,
    ) use (&$capturedEventContexts): \Pam\Native\Renderable {
        $capturedEventContexts = $props['__pamEventContexts'] ?? null;

        return Text::make('Child');
    },
);
$eventContextTemplate = TemplateCompiler::compile(
    '<EventParent value="framework" on:change="submit"><EventChild /></EventParent>',
);
TemplateRenderer::render($eventContextTemplate, $eventScope, []);
if (!is_array($capturedEventContexts)) {
    throw new RuntimeException('Declarative event contexts are missing.');
}
$eventContext = $capturedEventContexts['EventParent'] ?? null;
if (!is_array($eventContext)) {
    throw new RuntimeException('Declarative event context is missing.');
}
$eventContextEvents = $eventContext['events'] ?? null;
$eventContextProps = $eventContext['props'] ?? null;
$eventContextHandler = is_array($eventContextEvents)
    ? ($eventContextEvents[EventKind::Change->value] ?? null)
    : null;
if (!$eventContextHandler instanceof Closure) {
    throw new RuntimeException('Declarative event context handler is missing.');
}
if (!is_array($eventContextProps)) {
    throw new RuntimeException('Declarative event context props are missing.');
}
$eventContextHandler('laravel');
$assert(
    ($eventContextProps['value'] ?? null) === 'framework'
        && $eventScope->submission === 'laravel',
    'Registered child components must receive bounded ancestor event context.',
);
TemplateRegistry::reset();
TemplateRegistry::styleResolver(
    static fn (string $class): ?array => $class === 'w-1/2'
        ? [PropKey::WidthPercent->value => 50.0]
        : null,
);
$assert(
    TemplateRegistry::classProperties('w-1/2')
        === [PropKey::WidthPercent->value => 50.0],
    'Plugin utility resolvers must compile classes lazily.',
);
$assert(
    TemplateRegistry::classProperties('unknown-utility') === null,
    'Plugin utility resolvers must delegate unsupported classes.',
);
TemplateRegistry::reset();

$incremental = new TreeEncoder();
$initial = $incremental->encode(Text::make('A')->key('value'));
$unchanged = $incremental->encode(Text::make('A')->key('value'));
$patch = $incremental->encode(Text::make('B')->key('value'));
$initialFrame = $initial['frame'];
$patchFrame = $patch['frame'];

if ($initialFrame === null || $patchFrame === null) {
    throw new RuntimeException('Initial and changed trees must produce frames.');
}

$assert(str_starts_with($initialFrame, 'PNT1'), 'Initial tree frame is missing.');
$assert($unchanged['frame'] === null, 'An unchanged tree must not cross the native bridge.');
$assert(str_starts_with($patchFrame, 'PNP1'), 'Property update must use a patch frame.');
$assert(strlen($patchFrame) < strlen($initialFrame), 'Property patch must be smaller than the full tree.');

$structural = $incremental->encode(
    Column::make(
        Text::make('B')->key('value'),
        Text::make('New')->key('new'),
    )->key('content'),
);
$assert(
    $structural['frame'] !== null && str_starts_with($structural['frame'], 'PNP1'),
    'Structural changes after boot must use a patch frame.',
);

$wire = Wire::map([
    'name' => 'Pam',
    'count' => 42,
    'ratio' => 1.5,
    'enabled' => true,
]);
$assert(Wire::decodeMap($wire) === [
    'name' => 'Pam',
    'count' => 42,
    'ratio' => 1.5,
    'enabled' => true,
], 'Wire map round-trip failed.');

$counter = new class extends Component {
    public int $count = 0;

    public function render(): \Pam\Native\Element
    {
        return Screen::make(
            Button::make("Count: {$this->count}")
                ->key('counter')
                ->onPress(function (): void {
                    $this->count++;
                }),
        );
    }
};

App::run($counter);
$frame = Runtime::lastFrame();
$assert($frame !== null, 'App did not render its initial frame.');
$moduleResult = null;
$requestId = NativeModules::call(
    'community.echo',
    'echo',
    ['message' => 'fast'],
    static function (NativeModuleResult $result) use (&$moduleResult): void {
        $moduleResult = $result;
    },
);
$assert(
    TestDiagnostics::$moduleCall !== null
        && TestDiagnostics::$moduleCall['requestId'] === $requestId
        && TestDiagnostics::$moduleCall['module'] === 'community.echo'
        && Wire::decodeMap(TestDiagnostics::$moduleCall['payload']) === ['message' => 'fast'],
    'Public native module facade did not emit a typed bridge call.',
);
Runtime::dispatchModuleResult(
    $requestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map(['message' => 'fast']),
);
$assert(
    $moduleResult instanceof NativeModuleResult
        && $moduleResult->succeeded()
        && $moduleResult->values() === ['message' => 'fast'],
    'Public native module facade did not decode its result.',
);
$encoded = (new TreeEncoder())->encode($counter->render());
$eventKey = array_key_first($encoded['callbacks']);

if ($eventKey === null) {
    throw new RuntimeException('Counter callback is missing.');
}

[$nodeId, $eventKind] = array_map('intval', explode(':', $eventKey));
Runtime::dispatchEvent($nodeId, $eventKind, '');
$assert($counter->count === 1, 'Event did not update component state.');
$assert(Runtime::lastFrame() !== $frame, 'State update did not produce a new frame.');
Runtime::shutdown();

$lifecycleState = null;
$windowMetrics = null;
$memoryPressure = null;
App::onAppState(static function (AppState $state) use (&$lifecycleState): void {
    $lifecycleState = $state;
});
App::onDimensions(static function (WindowMetrics $metrics) use (&$windowMetrics): void {
    $windowMetrics = $metrics;
});
App::onMemoryPressure(static function (MemoryPressure $pressure) use (&$memoryPressure): void {
    $memoryPressure = $pressure;
});
App::run(Text::make('Lifecycle'));
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Background->value);
Runtime::dispatchEvent(0, EventKind::Dimensions->value, Wire::map([
    'width' => 412.0,
    'height' => 915.0,
    'density' => 3.0,
    'appearance' => UserInterfaceAppearance::Dark->value,
]));
Runtime::dispatchEvent(
    0,
    EventKind::MemoryPressure->value,
    (string) MemoryPressure::Critical->value,
);
$assert($lifecycleState === AppState::Background, 'App-state lifecycle event was not decoded.');
$assert(
    $windowMetrics instanceof WindowMetrics
        && $windowMetrics->width === 412.0
        && $windowMetrics->height === 915.0
        && $windowMetrics->density === 3.0
        && $windowMetrics->appearance === UserInterfaceAppearance::Dark,
    'Window metrics lifecycle event was not decoded.',
);
$assert(
    $memoryPressure === MemoryPressure::Critical,
    'Memory-pressure lifecycle event was not decoded.',
);
Runtime::shutdown();

State::set('test.value', ['count' => 7]);
State::resetCache();
$assert(State::get('test.value') === ['count' => 7], 'Atomic state persistence failed.');

$restorable = new class extends Component implements Restorable {
    public int $count = 0;

    public function stateKey(): string
    {
        return 'tests.counter';
    }

    public function restoreState(array $state): void
    {
        $this->count = is_int($state['count'] ?? null) ? $state['count'] : 0;
    }

    public function saveState(): array
    {
        return ['count' => $this->count];
    }

    public function render(): \Pam\Native\Element
    {
        return Button::make((string) $this->count)
            ->key('restorable')
            ->onPress(function (): void {
                $this->count++;
            });
    }
};
App::run($restorable);
$restorableFrame = (new TreeEncoder())->encode($restorable->render());
$restorableEvent = array_key_first($restorableFrame['callbacks']);
if ($restorableEvent === null) {
    throw new RuntimeException('Restorable component callback is missing.');
}
[$restorableNode, $restorableKind] = array_map('intval', explode(':', $restorableEvent));
Runtime::dispatchEvent($restorableNode, $restorableKind, '');
$assert($restorable->count === 1, 'Restorable component did not update.');
Runtime::shutdown();
State::resetCache();

$restored = new class extends Component implements Restorable {
    public int $count = 0;

    public function stateKey(): string
    {
        return 'tests.counter';
    }

    public function restoreState(array $state): void
    {
        $this->count = is_int($state['count'] ?? null) ? $state['count'] : 0;
    }

    public function saveState(): array
    {
        return ['count' => $this->count];
    }

    public function render(): \Pam\Native\Element
    {
        return Text::make((string) $this->count);
    }
};
App::run($restored);
$assert($restored->count === 1, 'Component state was not restored after runtime restart.');
Runtime::shutdown();

$templateDirectory = sys_get_temp_dir().'/pam-native-template-tests';

if (!is_dir($templateDirectory) && !mkdir($templateDirectory, 0o755, true) && !is_dir($templateDirectory)) {
    throw new RuntimeException('Cannot create the template test directory.');
}

file_put_contents(
    $templateDirectory.'/counter.pam',
    <<<'PAM'
<Screen>
    <Column class="p-4 gap-2 bg-white">
        <Text key="greeting">Hello, {{ $name }}</Text>
        <Input model="name" placeholder="Name" sync="debounced" />
        <Button key="increment" on:press="increment">Count: {{ $count }}</Button>
        <If condition="$details">
            <Text key="details">Template details</Text>
        </If>
    </Column>
</Screen>
PAM,
);
file_put_contents(
    $templateDirectory.'/panel.pam',
    <<<'PAM'
<Column class="card gap-2">
    <Text key="panel-title">{{ $props.title }}</Text>
    <Slot>
        <Text>Fallback</Text>
    </Slot>
</Column>
PAM,
);
file_put_contents(
    $templateDirectory.'/slots.pam',
    <<<'PAM'
<Screen>
    <Panel title="Metrics">
        <Text key="slot-content">Fast native content</Text>
    </Panel>
</Screen>
PAM,
);
file_put_contents(
    $templateDirectory.'/override.pam',
    <<<'PAM'
<Button>
    <Text>Plugin child</Text>
</Button>
PAM,
);
App::views($templateDirectory, $templateDirectory.'/cache');
App::theme(Theme::pamLab());
App::component('Panel', 'panel');
$assert(
    TemplateRegistry::classProperties('card') !== null,
    'Theme class tokens were not registered.',
);

$template = new class extends Component {
    private string $name = 'PHP';
    private int $count = 0;
    private bool $details = true;

    public function render(): View
    {
        return View::make('counter');
    }

    public function increment(): void
    {
        $this->count++;
    }

    public function count(): int
    {
        return $this->count;
    }

    /** @return array{name: string, details: bool} */
    public function templateState(): array
    {
        return ['name' => $this->name, 'details' => $this->details];
    }
};

App::run($template);
$templateRoot = $template->toElement();
$templateFrame = Runtime::lastFrame();
$templateEncoded = (new TreeEncoder())->encode($templateRoot);
$assert($templateFrame !== null, 'Tag template did not produce a native frame.');
$assert(count($templateEncoded['callbacks']) === 2, 'Template model and press callbacks are missing.');
$pressCallback = array_key_first(array_filter(
    $templateEncoded['callbacks'],
    static fn (string $key): bool => str_ends_with($key, ':1'),
    ARRAY_FILTER_USE_KEY,
));

if ($pressCallback === null) {
    throw new RuntimeException('Template press callback is missing.');
}

[$templateNode, $templateEvent] = array_map('intval', explode(':', $pressCallback));
Runtime::dispatchEvent($templateNode, $templateEvent, '');
$assert($template->count() === 1, 'Template event did not invoke its component method.');
Runtime::shutdown();

$slotView = View::make('slots')->toElement();
$assert(
    $slotView->children() !== [],
    'Component props and slots did not render native children.',
);

TemplateRegistry::component(
    'Button',
    static fn (array $_props, array $children, ?object $_scope): Pressable => Pressable::make(...$children),
);
$overriddenButton = View::make('override')->toElement();
$assert(
    $overriddenButton->kind() === NodeKind::Pressable
        && count($overriddenButton->children()) === 1,
    'Plugin tags must be able to override core tags and receive element children.',
);

$failing = new class extends Component {
    public function render(): \Pam\Native\Element
    {
        return Button::make('Fail')->onPress(static function (): void {
            throw new RuntimeException('Expected recoverable PHP error.');
        });
    }
};
App::run($failing);
$failingFrame = (new TreeEncoder())->encode($failing->render());
$failingEvent = array_key_first($failingFrame['callbacks']);
if ($failingEvent === null) {
    throw new RuntimeException('Failing callback is missing.');
}
[$failingNode, $failingKind] = array_map('intval', explode(':', $failingEvent));
Runtime::dispatchEvent($failingNode, $failingKind, '');
$assert(
    isset(TestDiagnostics::$messages[0])
        && str_starts_with(TestDiagnostics::$messages[0], "PAMERR1\n")
        && str_contains(TestDiagnostics::$messages[0], 'Expected recoverable PHP error.'),
    'Recoverable PHP errors must emit structured diagnostics.',
);
$assert(Runtime::lastFrame() !== null, 'A PHP callback error stopped the runtime.');
Runtime::shutdown();

$pluginProject = sys_get_temp_dir().'/pam-native-plugin-tests-'.getmypid();
$pluginPackage = $pluginProject.'/vendor/community/fixture';

if (
    !is_dir($pluginPackage)
    && !mkdir($pluginPackage, 0o755, true)
    && !is_dir($pluginPackage)
) {
    throw new RuntimeException('Cannot create the plugin fixture package.');
}
if (
    !is_dir($pluginProject.'/vendor/composer')
    && !mkdir($pluginProject.'/vendor/composer', 0o755, true)
    && !is_dir($pluginProject.'/vendor/composer')
) {
    throw new RuntimeException('Cannot create the Composer fixture directory.');
}

file_put_contents(
    $pluginProject.'/vendor/composer/installed.json',
    <<<'JSON'
{
    "packages": [
        {
            "name": "community/fixture",
            "version": "1.0.0",
            "install-path": "../community/fixture",
            "extra": {
                "pam-native": {
                    "plugin": "pam-native.plugin.json"
                }
            }
        }
    ]
}
JSON,
);
file_put_contents(
    $pluginPackage.'/pam-native.plugin.json',
    <<<'JSON'
{
    "version": 1,
    "protocol": 1,
    "pamNative": {
        "minimum": "0.1.0",
        "maximumExclusive": "0.2.0"
    },
    "php": {
        "provider": "Pam\\Native\\Tests\\Fixtures\\ExamplePluginProvider"
    }
}
JSON,
);

PluginManager::reset();
PluginManager::boot($pluginProject);
$assert(
    ExamplePluginProvider::$registered === 1
        && ExamplePluginProvider::$booted === 1
        && TemplateRegistry::factory('FixtureTag') !== null,
    'Composer plugin provider was not registered and booted exactly once.',
);
PluginManager::boot($pluginProject);
$assert(
    ExamplePluginProvider::$registered === 1 && ExamplePluginProvider::$booted === 1,
    'Plugin providers must be idempotent within a runtime.',
);
PluginManager::reset();
file_put_contents(
    $pluginProject.'/vendor/composer/installed.json',
    <<<'JSON'
{
    "packages": [
        {
            "name": "community/fixture",
            "version": "1.0.0",
            "install-path": "../community/fixture",
            "extra": {
                "pam-native": {
                    "plugin": "../outside.json"
                }
            }
        }
    ]
}
JSON,
);
$unsafeDescriptorRejected = false;

try {
    PluginManager::discover($pluginProject);
} catch (PluginException) {
    $unsafeDescriptorRejected = true;
}

$assert($unsafeDescriptorRejected, 'Plugin descriptor traversal must be rejected.');

$pamPhpDirectory = sys_get_temp_dir().'/pam-native-sfc-tests-'.getmypid();
$pamPhpCache = $pamPhpDirectory.'/.cache';

if (
    !is_dir($pamPhpDirectory)
    && !mkdir($pamPhpDirectory, 0o755, true)
    && !is_dir($pamPhpDirectory)
) {
    throw new RuntimeException('Cannot create the .pam.php fixture directory.');
}

file_put_contents(
    $pamPhpDirectory.'/CounterCard.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Attributes\State;
use Pam\Native\Component;

final class CounterCard extends Component
{
    /** @var list<string> */
    public static array $lifecycle = [];

    #[State]
    public int $count = 0;

    #[State]
    public bool $enabled = false;

    /** @var list<string> */
    public array $items = ['One', 'Two'];

    public int $repeatCount = 3;

    public function __construct(
        public string $title,
        public ?string $subtitle = null,
        public bool $elevated = false,
    ) {
    }

    public function boot(): void
    {
        self::$lifecycle[] = 'boot';
    }

    public function mount(): void
    {
        self::$lifecycle[] = 'mount';
    }

    public function attached(): void
    {
        self::$lifecycle[] = 'attached';
    }

    public function resumed(): void
    {
        self::$lifecycle[] = 'resumed';
    }

    public function updated(string $property): void
    {
        self::$lifecycle[] = 'updated:'.$property;
    }

    public function paused(): void
    {
        self::$lifecycle[] = 'paused';
    }

    public function unmount(): void
    {
        self::$lifecycle[] = 'unmount';
    }

    public function increment(): void
    {
        $this->count++;
        $this->emit('changed', $this->count);
    }

    /** @return array<array-key, string|bool> */
    public function cardClasses(): array
    {
        return [
            'p-4',
            'gap-2',
            'elevation-2' => $this->elevated,
        ];
    }
}
?>

<template>
    <Column :class="cardClasses()">
        <Text>{{ $title }}</Text>
        <Text v-if="$subtitle">{{ $subtitle }}</Text>
        <Text v-for="$item in $items">{{ $item }}</Text>
        <Text v-for="$number in $repeatCount">Repeat {{ $number }}</Text>
        <Text v-for="$_ in 0">Never rendered</Text>
        <Button @press="increment">
            {{ $count === 0 ? 'Ready' : $count }}
        </Button>
        <Switch bind:checked="$enabled" />
        <Slot name="action">
            <Text>Fallback action</Text>
        </Slot>
        <Slot />
    </Column>
</template>
PAM,
);
file_put_contents(
    $pamPhpDirectory.'/Dashboard.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Component;

final class Dashboard extends Component
{
    public string $title = 'PAM Native';
    public int $changes = 0;

    public function changed(int $count): void
    {
        $this->changes = $count;
        $this->title = 'Changed';
    }

}
?>

<template>
    <CounterCard
        :title="$title"
        subtitle="Compiled .pam.php"
        :elevated="true"
        @changed="changed"
    >
        <template #action>
            <Text>Named action</Text>
        </template>

        <Text>Default slot</Text>
    </CounterCard>
</template>
PAM,
);

TemplateRegistry::reset();
App::components($pamPhpDirectory, $pamPhpCache);
$dashboardClass = 'Pam\\Native\\Tests\\Sfc\\Dashboard';
$counterClass = 'Pam\\Native\\Tests\\Sfc\\CounterCard';
$dashboard = App::make($dashboardClass);
App::run($dashboard);
$sfcElement = $dashboard->toElement();
$sfcEncoded = (new TreeEncoder())->encode($sfcElement);
$pressKey = null;
$toggleKey = null;

foreach (array_keys($sfcEncoded['callbacks']) as $callbackKey) {
    [, $kind] = array_map('intval', explode(':', $callbackKey));
    if ($kind === EventKind::Press->value) {
        $pressKey = $callbackKey;
    } elseif ($kind === EventKind::Toggle->value) {
        $toggleKey = $callbackKey;
    }
}

$assert($pressKey !== null, '.pam.php @press event was not compiled.');
$assert($toggleKey !== null, '.pam.php bind:checked event was not compiled.');
$assert(
    count($sfcElement->children()) === 11,
    '.pam.php v-if, iterable/integer v-for and slots did not compose correctly.',
);
$assert(
    array_map(
        static fn (\Pam\Native\Element $element): mixed =>
            $element->properties()[PropKey::Text->value] ?? null,
        array_slice($sfcElement->children(), 4, 3),
    ) === ['Repeat 1', 'Repeat 2', 'Repeat 3'],
    '.pam.php integer v-for must expose a one-based loop value.',
);
$assert(
    $counterClass::$lifecycle === ['boot', 'mount', 'attached', 'resumed'],
    '.pam.php component lifecycle did not mount on the committed render.',
);

if ($pressKey === null) {
    throw new RuntimeException('.pam.php press callback is missing.');
}
[$pressNode, $pressKind] = array_map('intval', explode(':', $pressKey));
Runtime::dispatchEvent($pressNode, $pressKind, '');
$afterPress = $dashboard->toElement();
$assert(
    $afterPress->children()[0]->properties()[PropKey::Text->value] === 'Changed',
    'Component emit() did not reach the parent @event handler.',
);
$assert(
    in_array('updated:title', $counterClass::$lifecycle, true),
    'Constructor prop changes must update a stable nested component.',
);

$rerendered = $dashboard->toElement();
$rerenderedEncoded = (new TreeEncoder())->encode($rerendered);
$newToggleKey = null;
foreach (array_keys($rerenderedEncoded['callbacks']) as $callbackKey) {
    [, $kind] = array_map('intval', explode(':', $callbackKey));
    if ($kind === EventKind::Toggle->value) {
        $newToggleKey = $callbackKey;
        break;
    }
}
if ($newToggleKey === null) {
    throw new RuntimeException('Rerendered .pam.php toggle callback is missing.');
}
[$toggleNode, $toggleKind] = array_map('intval', explode(':', $newToggleKey));
Runtime::dispatchEvent($toggleNode, $toggleKind, '1');
$assert(
    $dashboard->toElement()->children()[8]
        ->properties()[PropKey::Checked->value] === true,
    'bind:checked must update component state and rerender the native toggle.',
);

Runtime::shutdown();
$assert(
    array_slice($counterClass::$lifecycle, -2) === ['paused', 'unmount'],
    '.pam.php component lifecycle did not clean up on runtime shutdown.',
);

$globalCallRejected = false;
try {
    TemplateRenderer::render(
        TemplateCompiler::compile('<Text>{{ system(\'id\') }}</Text>'),
        new class {
        },
        [],
    );
} catch (RuntimeException) {
    $globalCallRejected = true;
}
$assert(
    $globalCallRejected,
    '.pam.php expressions must never call global PHP functions.',
);

echo "Pam Native PHP SDK tests passed.\n";
