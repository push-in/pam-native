<?php

declare(strict_types=1);

namespace Pam\Native;

use JsonException;

use function getenv;
use function is_array;
use function is_int;
use function is_string;
use function json_decode;
use function json_encode;
use function strlen;

/**
 * What the installed renderer (Rust engine plus Android/iOS host) implements.
 *
 * Hosts export it before PHP starts as compact JSON in
 * `PAM_RENDERER_CONTRACT`:
 *
 *     {"abi":1,"events":[79,1000],"properties":[540,1001],"protocol":[1,1]}
 *
 * `properties[0]`/`events[0]` are the highest core identifiers implemented
 * (at most 543/80) and `properties[1]`/`events[1]` the highest extension
 * identifiers (`Protocol::EXTENSION_BASE - 1` when none). A host that exports
 * nothing predates the extension table: it is treated as the complete core
 * table without extensions, exactly how such hosts behaved before.
 */
final class RendererContract
{
    public const string ENVIRONMENT = 'PAM_RENDERER_CONTRACT';
    private const int MAX_JSON_BYTES = 1_024;

    private static ?self $current = null;
    private static ?self $local = null;

    public function __construct(
        public readonly int $abiVersion,
        public readonly int $minimumProtocolVersion,
        public readonly int $maximumProtocolVersion,
        public readonly int $propertyCeiling,
        public readonly int $extensionPropertyCeiling,
        public readonly int $eventCeiling,
        public readonly int $extensionEventCeiling,
        public readonly bool $declared = true,
    ) {
        $none = Protocol::EXTENSION_BASE - 1;
        if (
            $abiVersion < 1 || $abiVersion > 65_535
            || $minimumProtocolVersion < 1 || $maximumProtocolVersion < $minimumProtocolVersion
            || $maximumProtocolVersion > 65_535
            || $propertyCeiling < 1 || $propertyCeiling > Protocol::CORE_PROPERTY_LIMIT
            || $eventCeiling < 1 || $eventCeiling > Protocol::CORE_EVENT_LIMIT
            || $extensionPropertyCeiling < $none || $extensionPropertyCeiling > Protocol::EXTENSION_LIMIT
            || $extensionEventCeiling < $none || $extensionEventCeiling > Protocol::EXTENSION_LIMIT
        ) {
            throw new ProtocolMismatchException('The renderer contract is out of range.');
        }
    }

    /** A host that exports no contract: the full core table, no extensions. */
    public static function legacy(): self
    {
        $none = Protocol::EXTENSION_BASE - 1;

        return new self(
            Protocol::ABI_VERSION,
            Protocol::MINIMUM_VERSION,
            Protocol::VERSION,
            Protocol::CORE_PROPERTY_LIMIT,
            $none,
            Protocol::CORE_EVENT_LIMIT,
            $none,
            declared: false,
        );
    }

    /** The identifiers this SDK knows, in the shape a host exports. */
    public static function local(): self
    {
        if (self::$local !== null) {
            return self::$local;
        }
        [$property, $extensionProperty] = self::ceilings(PropKey::cases());
        [$event, $extensionEvent] = self::ceilings(EventKind::cases());

        return self::$local = new self(
            Protocol::ABI_VERSION,
            Protocol::MINIMUM_VERSION,
            Protocol::VERSION,
            $property,
            $extensionProperty,
            $event,
            $extensionEvent,
        );
    }

    public static function fromJson(?string $json): self
    {
        if ($json === null || $json === '') {
            return self::legacy();
        }
        if (strlen($json) > self::MAX_JSON_BYTES) {
            throw new ProtocolMismatchException('The renderer contract is malformed.');
        }
        try {
            $values = json_decode($json, true, 4, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new ProtocolMismatchException('The renderer contract is malformed.', previous: $error);
        }
        $pair = static function (mixed $value): array {
            if (!is_array($value) || !array_is_list($value) || count($value) !== 2
                || !is_int($value[0]) || !is_int($value[1])) {
                throw new ProtocolMismatchException('The renderer contract is malformed.');
            }

            return $value;
        };
        if (!is_array($values) || !is_int($values['abi'] ?? null)) {
            throw new ProtocolMismatchException('The renderer contract is malformed.');
        }
        [$minimum, $maximum] = $pair($values['protocol'] ?? null);
        [$property, $extensionProperty] = $pair($values['properties'] ?? null);
        [$event, $extensionEvent] = $pair($values['events'] ?? null);

        return new self($values['abi'], $minimum, $maximum, $property, $extensionProperty, $event, $extensionEvent);
    }

    /** The installed renderer's contract, read once from the host environment. */
    public static function current(): self
    {
        if (self::$current === null) {
            $raw = getenv(self::ENVIRONMENT);
            self::$current = self::fromJson(is_string($raw) ? $raw : null);
        }

        return self::$current;
    }

    /** Overrides the host contract (embedders and tests); null re-reads it. */
    public static function use(?self $contract): void
    {
        self::$current = $contract;
    }

    /** Canonical JSON, byte-identical to what the Android and iOS hosts export. */
    public function toJson(): string
    {
        return json_encode([
            'abi' => $this->abiVersion,
            'events' => [$this->eventCeiling, $this->extensionEventCeiling],
            'properties' => [$this->propertyCeiling, $this->extensionPropertyCeiling],
            'protocol' => [$this->minimumProtocolVersion, $this->maximumProtocolVersion],
        ], JSON_THROW_ON_ERROR);
    }

    public function negotiate(): ProtocolCompatibilityReport
    {
        return Protocol::negotiate(new ProtocolHandshake(
            abiVersion: $this->abiVersion,
            minimumProtocolVersion: $this->minimumProtocolVersion,
            maximumProtocolVersion: $this->maximumProtocolVersion,
            capabilities: Protocol::CAPABILITIES,
        ));
    }

    /** The boot handshake: refuses an incompatible renderer before the first frame. */
    public function assertCompatible(): void
    {
        $report = $this->negotiate();
        if (!$report->isCompatible()) {
            throw new ProtocolMismatchException(
                'The installed Pam Native renderer is incompatible with this application: '.$report->message
                .' Rebuild the application with a matching renderer.',
            );
        }
    }

    public function supportsProperty(int $key): bool
    {
        return $key >= 1 && ($key <= $this->propertyCeiling
            || ($key >= Protocol::EXTENSION_BASE && $key <= $this->extensionPropertyCeiling));
    }

    public function supportsEvent(int $kind): bool
    {
        return $kind >= 1 && ($kind <= $this->eventCeiling
            || ($kind >= Protocol::EXTENSION_BASE && $kind <= $this->extensionEventCeiling));
    }

    public function requireProperty(int $key): void
    {
        if (!$this->supportsProperty($key)) {
            $name = PropKey::tryFrom($key)?->name ?? 'unknown';
            throw new ProtocolMismatchException(
                "The installed Pam Native renderer does not implement property {$name} ({$key}); "
                .$this->describe($this->propertyCeiling, $this->extensionPropertyCeiling)
                .' Rebuild the application with a renderer that ships this property.',
            );
        }
    }

    public function requireEvent(int $kind): void
    {
        if (!$this->supportsEvent($kind)) {
            $name = EventKind::tryFrom($kind)?->name ?? 'unknown';
            throw new ProtocolMismatchException(
                "The installed Pam Native renderer does not implement event {$name} ({$kind}); "
                .$this->describe($this->eventCeiling, $this->extensionEventCeiling)
                .' Rebuild the application with a renderer that ships this event.',
            );
        }
    }

    private function describe(int $core, int $extension): string
    {
        $declared = $this->declared ? 'it declares' : 'it predates the extension table and supports';

        return $extension < Protocol::EXTENSION_BASE
            ? "{$declared} core IDs up to {$core} and no extension IDs."
            : "{$declared} core IDs up to {$core} and extension IDs up to {$extension}.";
    }

    /**
     * @param list<\BackedEnum> $cases
     * @return array{int, int}
     */
    private static function ceilings(array $cases): array
    {
        $core = 0;
        $extension = Protocol::EXTENSION_BASE - 1;
        foreach ($cases as $case) {
            $value = (int) $case->value;
            if ($value >= Protocol::EXTENSION_BASE) {
                $extension = max($extension, $value);
            } else {
                $core = max($core, $value);
            }
        }

        return [$core, $extension];
    }
}
