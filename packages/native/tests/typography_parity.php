<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;
use Pam\Native\UI\Text;

/**
 * React Native typography/component parity: nested text spans,
 * includeFontPadding, hairline units and related layout contracts.
 *
 * @var Closure(bool, string): void $assert
 */
$typographyRender = static function (string $template, string $css, ?object $scope = null): \Pam\Native\Element {
    $compiled = TemplateCompiler::compile($template);
    $styled = new CompiledTemplateNode(
        kind: $compiled->kind,
        name: $compiled->name,
        attributes: [
            ...$compiled->attributes,
            '__pamStyles' => json_encode(ScopedStyleCompiler::compile($css, 'TypographyParity.pam.php'), JSON_THROW_ON_ERROR),
        ],
        source: $compiled->source,
        line: $compiled->line,
        column: $compiled->column,
        value: $compiled->value,
    );
    $styled->children = $compiled->children;

    return TemplateRenderer::render($styled, $scope, []);
};

// Nested spans: one paragraph, inherited styles, per-span press handlers.
$pressedLinks = [];
$richScope = new class ($pressedLinks) {
    /** @param list<string> $log */
    public function __construct(public array &$log)
    {
    }

    public function openMention(): void
    {
        $this->log[] = 'mention';
    }

    public function openLink(): void
    {
        $this->log[] = 'link';
    }
};
$rich = $typographyRender(
    '<Text class="body">Olá <Text class="strong">mundo <Span class="link" on:press="openLink">https://zé.chat</Span></Text>'
        .' e <Span class="mention" on:press="openMention">@ana</Span>!</Text>',
    '.body { font-size: 15px; line-height: 20px; color: #111111; }'
        .' .strong { font-weight: 700; }'
        .' .link { color: #1B7A4E; text-decoration: underline; }'
        .' .mention { color: #0055FF; background-color: #EEEEEE; font-size: 13px; }',
    $richScope,
);
$richProperties = $rich->properties();
$assert(
    ($richProperties[PropKey::Text->value] ?? null) === 'Olá mundo https://zé.chat e @ana!',
    'Nested Text/Span children must render as one paragraph string: '.var_export($richProperties[PropKey::Text->value] ?? null, true),
);
$richSpans = explode(';', (string) ($richProperties[PropKey::TextSpans->value] ?? ''));
$assert(count($richSpans) === 3, 'Each styled nested run must produce one span: '.implode(';', $richSpans));
$boldFields = explode(',', $richSpans[0]);
$assert(
    $boldFields[0] === '4' && $boldFields[1] === '25' && $boldFields[3] === '700',
    'The bold run must cover "mundo https://zé.chat" in code points: '.$richSpans[0],
);
$linkFields = explode(',', $richSpans[1]);
$assert(
    $linkFields[0] === '10' && $linkFields[1] === '25' && $linkFields[3] === '700'
        && $linkFields[5] === (string) 0xFF1B7A4E && $linkFields[7] === '2' && $linkFields[10] === '0',
    'Inner spans must inherit ancestor weight and carry color, decoration and their press slot: '.$richSpans[1],
);
$mentionFields = explode(',', $richSpans[2]);
$assert(
    $mentionFields[0] === '28' && $mentionFields[1] === '32' && $mentionFields[2] === '13'
        && $mentionFields[6] === (string) 0xFFEEEEEE && $mentionFields[10] === '1',
    'Mention spans must carry size, background and their own press slot: '.$richSpans[2],
);
$assert(
    (float) ($richProperties[PropKey::LineHeight->value] ?? 0) === 20.0
        && isset($richProperties[PropKey::OnSpanPress->value]),
    'Paragraph styles stay on the outer node and span presses are wired.',
);
$spanPress = $rich->events()[EventKind::SpanPress->value] ?? null;
$assert($spanPress instanceof Closure, 'Rich text must register a span press handler.');
$spanPress('1');
$spanPress('0');
$spanPress('9');
$assert($richScope->log === ['mention', 'link'], 'Span presses must dispatch to the pressed run only.');

$apiRich = Text::rich('a', Text::rich('b', Text::make('c')->property(PropKey::FontWeight, 600)), 'd');
$assert(
    $apiRich->properties()[PropKey::Text->value] === 'abcd'
        && $apiRich->properties()[PropKey::TextSpans->value] === '2,3,,600',
    'Text::rich must flatten nested runs with code point offsets.',
);
$plainNested = Text::rich('x', Text::make('y'));
$assert(
    !isset($plainNested->properties()[PropKey::TextSpans->value]),
    'Unstyled runs must not emit spans.',
);

// includeFontPadding: React Native Android default true, CSS/prop opt-out.
$padded = $typographyRender('<Text class="tight">Aa</Text>', '.tight { include-font-padding: false; }');
$assert(
    ($padded->properties()[PropKey::IncludeFontPadding->value] ?? null) === false,
    'include-font-padding: false must reach the native text node.',
);
$vendorPadded = $typographyRender('<Text class="tight">Aa</Text>', '.tight { -pam-include-font-padding: false; }');
$assert(
    ($vendorPadded->properties()[PropKey::IncludeFontPadding->value] ?? null) === false,
    '-pam-include-font-padding must be accepted as an alias.',
);
$attributePadded = $typographyRender('<Text includeFontPadding="false">Aa</Text>', '');
$assert(
    ($attributePadded->properties()[PropKey::IncludeFontPadding->value] ?? null) === false,
    'The includeFontPadding attribute must compile to a boolean.',
);

// Hairline and device-pixel units resolve against the live display density.
\Pam\Native\Internal\Runtime::dispatchEvent(0, EventKind::Dimensions->value, \Pam\Native\Internal\Wire::map([
    'width' => 411.0, 'height' => 914.0, 'density' => 2.625, 'fontScale' => 1.0,
]));
$hairline = $typographyRender(
    '<View class="row"><View class="line" /></View>',
    '.row { border-bottom: hairline solid #DDDDDD; } .line { height: 2dpx; border-width: hairline; }',
);
$lineView = $hairline->children()[0];
$assert(
    abs((float) $hairline->properties()[PropKey::BorderBottomWidth->value] - 1 / 2.625) < 1e-9
        && abs((float) $lineView->properties()[PropKey::Height->value] - 2 / 2.625) < 1e-9
        && abs((float) $lineView->properties()[PropKey::BorderWidth->value] - 1 / 2.625) < 1e-9,
    'hairline must be one physical pixel and Ndpx N physical pixels.',
);
// The same element re-resolves its environment-dependent styles (cached
// per environment) after the display density changes.
\Pam\Native\Internal\Runtime::dispatchEvent(0, EventKind::Dimensions->value, \Pam\Native\Internal\Wire::map([
    'width' => 411.0, 'height' => 914.0, 'density' => 2.0, 'fontScale' => 1.0,
]));
$denser = $typographyRender(
    '<View class="row"><View class="line" /></View>',
    '.row { border-bottom: hairline solid #DDDDDD; } .line { height: 2dpx; border-width: hairline; }',
);
$assert(
    abs((float) $denser->properties()[PropKey::BorderBottomWidth->value] - 0.5) < 1e-9
        && abs((float) $denser->children()[0]->properties()[PropKey::Height->value] - 1.0) < 1e-9,
    'Environment-dependent styles must follow a density change for the same element.',
);
\Pam\Native\Internal\Runtime::dispatchEvent(0, EventKind::Dimensions->value, \Pam\Native\Internal\Wire::map([
    'width' => 411.0, 'height' => 914.0, 'density' => 2.625, 'fontScale' => 1.0,
]));
$restored = $typographyRender(
    '<View class="row"><View class="line" /></View>',
    '.row { border-bottom: hairline solid #DDDDDD; } .line { height: 2dpx; border-width: hairline; }',
);
$assert(
    abs((float) $restored->properties()[PropKey::BorderBottomWidth->value] - 1 / 2.625) < 1e-9,
    'Environment-dependent styles must come back with the previous density.',
);
$assert(
    \Pam\Native\PixelRatio::hairlineWidth(4.0) === 0.5
        && \Pam\Native\PixelRatio::hairlineWidth(1.0) === 1.0
        && \Pam\Native\PixelRatio::roundToNearestPixel(10.3, 2.625) === round(10.3 * 2.625) / 2.625,
    'PixelRatio must follow React Native rounding.',
);

// Safe areas are known from the first render (host-exported boot metrics).
putenv('PAM_BOOT_METRICS={"width":411.4,"height":914.3,"density":2.625,"fontScale":1.0,"safeAreaTop":24.0,"safeAreaBottom":48.0}');
$bootMetrics = (new ReflectionMethod(\Pam\Native\Internal\Runtime::class, 'bootMetrics'))->invoke(null);
putenv('PAM_BOOT_METRICS');
$assert(
    $bootMetrics->width === 411.4 && $bootMetrics->density === 2.625
        && $bootMetrics->safeAreaTop === 24.0 && $bootMetrics->safeAreaBottom === 48.0,
    'Boot window metrics must include host safe areas before the first Dimensions event.',
);

// Pressed transforms: :active/:pressed translate like React Native buttons.
$pressed = $typographyRender(
    '<Pressable class="primary"><Text>Enviar</Text></Pressable>',
    '.primary { background-color: #1B7A4E; } .primary:active { transform: translateY(1.1px); opacity: 0.9; }',
);
$pressedStates = json_decode((string) ($pressed->properties()[PropKey::NativeStateStyles->value] ?? '{}'), true);
$assert(
    abs((float) ($pressedStates[(string) \Pam\Native\Style\StyleInteractionState::Pressed->value][(string) PropKey::TranslationY->value] ?? 0) - 1.1) < 1e-9
        && (float) ($pressed->properties()[PropKey::PressOpacity->value] ?? 0) === 0.9,
    'A :active translateY must become a native pressed-state translation: '.json_encode($pressedStates),
);

// Vector icons: react-native-vector-icons glyph maps rendered as icon-font text.
$iconProject = sys_get_temp_dir().'/pam-icon-'.bin2hex(random_bytes(4));
mkdir($iconProject.'/assets/fonts', 0o777, true);
file_put_contents($iconProject.'/assets/fonts/Ionicons.ttf', 'ttf');
file_put_contents($iconProject.'/assets/fonts/Ionicons.json', '{"heart":61744,"chatbubble-ellipses":61695}');
$previousDirectory = getcwd();
chdir($iconProject);
try {
    $icon = $typographyRender('<Icon name="heart" size="24" class="accent" />', '.accent { color: #1B7A4E; }');
    $iconProperties = $icon->properties();
    $assert(
        $iconProperties[PropKey::Text->value] === mb_chr(61744, 'UTF-8')
            && $iconProperties[PropKey::FontFamily->value] === 'asset://assets/fonts/Ionicons.ttf'
            && (float) $iconProperties[PropKey::FontSize->value] === 24.0
            && $iconProperties[PropKey::TextColor->value] === 0xFF1B7A4E
            && $iconProperties[PropKey::TextAllowFontScaling->value] === false,
        'Icon must render its glyph with the icon font, size and CSS color.',
    );
    $assert(\Pam\Native\UI\Icon::make('missing')->properties()[PropKey::Text->value] === '?', 'Unknown glyphs render "?" like React Native.');
    \Pam\Native\UI\Icon::register('Brand', 'asset://assets/fonts/Brand.ttf', ['logo' => 0xE001]);
    $assert(\Pam\Native\UI\Icon::has('logo', 'Brand') && !\Pam\Native\UI\Icon::has('heart', 'Brand'), 'Registered icon fonts use their own glyph maps.');
} finally {
    chdir((string) $previousDirectory);
    array_map('unlink', glob($iconProject.'/assets/fonts/*') ?: []);
    rmdir($iconProject.'/assets/fonts');
    rmdir($iconProject.'/assets');
    rmdir($iconProject);
}

// Media events: ready carries the natural size; buffering/load start events.
$mediaLog = [];
$player = \Pam\Native\UI\MediaPlayer::make('https://example.test/video.mp4')
    ->onReady(static function (\Pam\Native\MediaReadyEvent $event) use (&$mediaLog): void { $mediaLog[] = "ready:{$event->naturalWidth}x{$event->naturalHeight}"; })
    ->onBuffering(static function (bool $buffering) use (&$mediaLog): void { $mediaLog[] = $buffering ? 'buffering' : 'playing'; })
    ->onLoadStart(static function () use (&$mediaLog): void { $mediaLog[] = 'load'; });
$player->events()[EventKind::MediaLoadStart->value]('');
$player->events()[EventKind::MediaReady->value](\Pam\Native\Internal\Wire::map(['naturalWidth' => 1080, 'naturalHeight' => 1920, 'duration' => 12.5]));
$player->events()[EventKind::MediaBuffering->value](\Pam\Native\Internal\Wire::map(['buffering' => true]));
$assert($mediaLog === ['load', 'ready:1080x1920', 'buffering'], 'Media lifecycle events must decode their payloads: '.implode(',', $mediaLog));
$legacyReady = false;
\Pam\Native\UI\MediaPlayer::make('x.mp4')->onReady(static function () use (&$legacyReady): void { $legacyReady = true; })
    ->events()[EventKind::MediaReady->value](\Pam\Native\Internal\Wire::map(['naturalWidth' => 1]));
$assert($legacyReady, 'Parameterless onReady handlers keep working.');

// In-app toast with title and message.
\Pam\Native\System\Toast::message('Mensagem enviada', 'Seu convite foi entregue.', \Pam\Native\System\ToastType::Success, \Pam\Native\System\ToastPosition::Bottom);
$toastPayload = \Pam\Native\Internal\Wire::decodeMap(TestDiagnostics::$typedCall['payload'] ?? '');
$assert(
    ($toastPayload['title'] ?? null) === 'Mensagem enviada' && ($toastPayload['bottom'] ?? null) === true
        && ($toastPayload['accentColor'] ?? null) === 0xFF69C779,
    'Toast::message must send the in-app toast contract.',
);

// Sticky headers, full-span list rows and keyboard-aware scroll attributes.
$listTemplate = $typographyRender(
    '<VirtualizedList numColumns="2"><Column fullSpan="true" stickyHeader="true"><Text>Header</Text></Column><Column><Text>A</Text></Column></VirtualizedList>',
    '',
);
$listHeader = $listTemplate->children()[0]->properties();
$assert(
    ($listHeader[PropKey::ListFullSpan->value] ?? null) === true && ($listHeader[PropKey::StickyHeader->value] ?? null) === true,
    'List header attributes must compile to native booleans.',
);
$keyboardScroll = $typographyRender('<ScrollView keyboardInset="true"><Column /></ScrollView>', '');
$assert(($keyboardScroll->properties()[PropKey::ScrollKeyboardInset->value] ?? null) === true, 'keyboardInset must reach the scroll view.');
$offsetScroll = $typographyRender('<ScrollView keyboardInset="true" keyboardVerticalOffset="156"><Column /></ScrollView>', '');
$assert(($offsetScroll->properties()[PropKey::KeyboardVerticalOffset->value] ?? null) == 156, 'Keyboard-aware scroll clearance must use the existing keyboard offset property.');
