<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\PropKey;
use Pam\Native\Protocol;
use Pam\Native\ProtocolMismatchException;
use Pam\Native\RendererContract;
use Pam\Native\UI\Text;
use Pam\Native\UI\View as ExtensionView;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
$throws = static function (Closure $body, string $needle) use ($assert): void {
    try {
        $body();
    } catch (ProtocolMismatchException $error) {
        $assert(str_contains($error->getMessage(), $needle), "Unexpected protocol error: {$error->getMessage()}");

        return;
    }
    $assert(false, "Expected a protocol mismatch mentioning {$needle}.");
};

// --- Tables: closed core, extension from 1001, same u16 wire field ----------
$assert(
    Protocol::CORE_PROPERTY_LIMIT === 543 && Protocol::CORE_EVENT_LIMIT === 80
        && Protocol::EXTENSION_BASE === 1001 && Protocol::EXTENSION_LIMIT === 65_535,
    'The core tables close at 543/80 and the extension table starts at 1001.',
);
$assert(
    PropKey::ListFollowEndOnResize->value === 1001 && PropKey::ScrollPageTransform->value === 1002
        && PropKey::MediaStartBufferMs->value === 1003 && PropKey::MediaIdleSurface->value === 1004,
    'The extension table starts with the port reservations 1001-1004.',
);
$local = RendererContract::local();
$coreMax = max(array_filter(array_map(static fn (PropKey $key): int => $key->value, PropKey::cases()), static fn (int $v): bool => $v < 1001));
$assert(
    $local->propertyCeiling === $coreMax && $local->extensionPropertyCeiling === 1004
        && $local->eventCeiling === max(array_map(static fn (EventKind $kind): int => $kind->value, EventKind::cases()))
        && $local->extensionEventCeiling === 1000,
    'The local contract declares the highest core and extension identifiers of the SDK tables.',
);

// --- Contract exchange --------------------------------------------------------
$legacy = RendererContract::fromJson(null);
$assert(
    !$legacy->declared && $legacy->propertyCeiling === 543 && $legacy->extensionPropertyCeiling === 1000
        && $legacy->eventCeiling === 80 && $legacy->extensionEventCeiling === 1000,
    'A host that exports no contract keeps the full core table and gets no extension IDs.',
);
$golden = '{"abi":1,"events":[79,1000],"properties":[540,1001],"protocol":[1,1]}';
$parsed = RendererContract::fromJson($golden);
$assert($parsed->declared && $parsed->toJson() === $golden, 'The contract JSON round-trips byte for byte (host golden).');
$assert(
    RendererContract::fromJson('{"protocol":[1,1],"abi":1,"properties":[540,1001],"events":[79,1000],"future":true}')->toJson() === $golden,
    'Key order and unknown future fields do not change the contract.',
);
foreach ([
    '{', '[]', '{"abi":1}', '{"abi":"1","events":[79,1000],"properties":[540,1001],"protocol":[1,1]}',
    '{"abi":1,"events":[79],"properties":[540,1001],"protocol":[1,1]}',
    '{"abi":1,"events":[79,1000],"properties":[544,1001],"protocol":[1,1]}',
    '{"abi":1,"events":[79,999],"properties":[540,1001],"protocol":[1,1]}',
    '{"abi":1,"events":[79,1000],"properties":[540,65536],"protocol":[1,1]}',
    '{"abi":1,"events":[79,1000],"properties":[540,1001],"protocol":[2,1]}',
    str_repeat(' ', 1_025),
] as $malformed) {
    $throws(static fn () => RendererContract::fromJson($malformed), 'renderer contract');
}

// --- Boot handshake -------------------------------------------------------------
$parsed->assertCompatible();
$legacy->assertCompatible();
$throws(
    static fn () => RendererContract::fromJson('{"abi":2,"events":[79,1000],"properties":[540,1001],"protocol":[1,1]}')->assertCompatible(),
    'ABI 2 is incompatible',
);
$throws(
    static fn () => RendererContract::fromJson('{"abi":1,"events":[79,1000],"properties":[540,1001],"protocol":[2,3]}')->assertCompatible(),
    'protocol ranges do not overlap',
);

// --- Encoder: extension IDs only reach renderers that declare them --------------
$withExtension = static fn (): ExtensionView => (fn (): ExtensionView => $this->withProperty(PropKey::ListFollowEndOnResize, true))
    ->call(ExtensionView::make(Text::make('rows')));
$coreOnly = static fn (): ExtensionView => ExtensionView::make(Text::make('rows'))->key('core');

try {
    RendererContract::use($legacy);
    $frame = (new TreeEncoder())->encode($coreOnly())['frame'];
    $assert(is_string($frame) && $frame !== '', 'An old renderer keeps receiving apps that use only core IDs.');
    $throws(
        static fn () => (new TreeEncoder())->encode($withExtension()),
        'does not implement property ListFollowEndOnResize (1001); it predates the extension table',
    );

    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1000],"properties":[540,1000],"protocol":[1,1]}'));
    $throws(
        static fn () => (new TreeEncoder())->encode($withExtension()),
        'it declares core IDs up to 540 and no extension IDs',
    );

    RendererContract::use($parsed);
    $frame = (new TreeEncoder())->encode($withExtension())['frame'];
    $assert(
        is_string($frame) && str_contains($frame, pack('v', 1001)."\x04\x01"),
        'A renderer that declares 1001 receives it in the ordinary u16 property field.',
    );

    // A patch that adds the property on a later frame is checked too.
    RendererContract::use($legacy);
    $encoder = new TreeEncoder();
    $encoder->encode(ExtensionView::make(Text::make('rows')));
    $throws(static fn () => $encoder->encode($withExtension()), 'ListFollowEndOnResize (1001)');

    // Events: core kinds pass, undeclared extension kinds are refused.
    $assert($legacy->supportsEvent(EventKind::Press->value) && !$legacy->supportsEvent(1001), 'Event ceilings follow the contract.');
    $throws(static fn () => $legacy->requireEvent(1001), 'does not implement event unknown (1001)');
    $assert(
        $parsed->supportsProperty(540) && !$parsed->supportsProperty(541) && !$parsed->supportsProperty(1000)
            && $parsed->supportsProperty(1001) && !$parsed->supportsProperty(1002) && !$parsed->supportsProperty(0),
        'A declared renderer accepts exactly its core and extension ranges.',
    );
} finally {
    RendererContract::use(null);
}
