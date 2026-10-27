<?php

declare(strict_types=1);

namespace Pam\Native;

final class Protocol
{
    public const string SDK_VERSION = '1.44.0';
    public const int ABI_VERSION = 1;
    public const int MINIMUM_VERSION = 1;
    public const int VERSION = 1;
    public const string TREE_MAGIC = 'PNT1';
    public const string PATCH_MAGIC = 'PNP1';
    public const string BATCH_MAGIC = 'PNB1';

    /**
     * The core identifier tables are closed: properties 1–543 and events
     * 1–80. Every later identifier is appended to the extension table, which
     * starts at 1001 and uses the same u16 wire field, so the frame bytes do
     * not change. A renderer declares the highest identifiers it implements in
     * its {@see RendererContract}; the SDK refuses to send an extension
     * identifier the renderer does not declare.
     */
    public const int CORE_PROPERTY_LIMIT = 543;
    public const int CORE_EVENT_LIMIT = 80;
    public const int EXTENSION_BASE = 1001;
    public const int EXTENSION_LIMIT = 65_535;

    /** @var list<string> */
    public const array CAPABILITIES = [
        'compiler.freeze.v1',
        'plugins.composer.v1',
        'renderer.incremental.v1',
        'runtime.modules.v1',
        'wire.binary.v1',
    ];

    private function __construct()
    {
    }

    public static function supports(int $version): bool
    {
        return $version >= self::MINIMUM_VERSION && $version <= self::VERSION;
    }

    public static function handshake(array $capabilities = self::CAPABILITIES): ProtocolHandshake
    {
        return new ProtocolHandshake(
            abiVersion: self::ABI_VERSION,
            minimumProtocolVersion: self::MINIMUM_VERSION,
            maximumProtocolVersion: self::VERSION,
            capabilities: $capabilities,
        );
    }

    public static function negotiate(ProtocolHandshake $peer): ProtocolCompatibilityReport
    {
        if ($peer->abiVersion !== self::ABI_VERSION) {
            return ProtocolCompatibilityReport::incompatible(
                ProtocolCompatibilityStatus::AbiMismatch,
                "ABI {$peer->abiVersion} is incompatible with ABI ".self::ABI_VERSION.'.',
            );
        }

        $minimum = max(self::MINIMUM_VERSION, $peer->minimumProtocolVersion);
        $maximum = min(self::VERSION, $peer->maximumProtocolVersion);
        if ($minimum > $maximum) {
            return ProtocolCompatibilityReport::incompatible(
                ProtocolCompatibilityStatus::ProtocolMismatch,
                'The supported protocol ranges do not overlap.',
            );
        }

        $capabilities = array_values(array_intersect(self::CAPABILITIES, $peer->capabilities));
        sort($capabilities, SORT_STRING);

        return ProtocolCompatibilityReport::compatible($maximum, $capabilities);
    }
}
