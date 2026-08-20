<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use InvalidArgumentException;

final class Wire
{
    public const MAX_VALUE_BYTES = 1_048_576;
    public const MAX_LIST_ITEMS = 100_000;
    public const MAX_SECTIONS = 10_000;
    public const MAX_SECTION_ENTRIES = 100_000;

    /** @param list<string> $items */
    public static function stringList(array $items): string
    {
        if (count($items) > self::MAX_LIST_ITEMS) {
            throw new InvalidArgumentException('String lists cannot contain more than 100,000 items.');
        }

        $output = self::u32(count($items));

        foreach ($items as $item) {
            $output .= self::sized(self::validatedText($item));
            if (strlen($output) > self::MAX_VALUE_BYTES) {
                throw new InvalidArgumentException('String lists cannot exceed one megabyte.');
            }
        }

        return $output;
    }

    /**
     * @param array<string, list<string>> $sections
     */
    public static function stringSections(array $sections): string
    {
        if (count($sections) > self::MAX_SECTIONS) {
            throw new InvalidArgumentException('Section lists cannot contain more than 10,000 sections.');
        }

        $output = self::u32(count($sections));
        $entries = count($sections);

        foreach ($sections as $title => $items) {
            $entries += count($items);
            if ($entries > self::MAX_SECTION_ENTRIES) {
                throw new InvalidArgumentException('Section lists cannot contain more than 100,000 entries.');
            }
            $output .= self::sized(self::validatedText($title)).self::u32(count($items));
            if (strlen($output) > self::MAX_VALUE_BYTES) {
                throw new InvalidArgumentException('Section data cannot exceed one megabyte.');
            }

            foreach ($items as $item) {
                $output .= self::sized(self::validatedText($item));
                if (strlen($output) > self::MAX_VALUE_BYTES) {
                    throw new InvalidArgumentException('Section data cannot exceed one megabyte.');
                }
            }
        }

        return $output;
    }

    /**
     * @param array<string, string|int|float|bool> $values
     */
    public static function map(array $values): string
    {
        $output = self::u16(count($values));
        ksort($values, SORT_STRING);

        foreach ($values as $key => $value) {
            if (preg_match('/^[A-Za-z][A-Za-z0-9_]{0,254}$/D', $key) !== 1) {
                throw new InvalidArgumentException('Wire map keys must use the portable identifier format.');
            }

            $output .= self::u16(strlen($key)).$key;
            $output .= match (true) {
                is_string($value) => "\x01".self::sized(self::validatedText($value)),
                is_int($value) => "\x02".pack('P', $value),
                is_float($value) => "\x03".pack('e', self::validatedFloat($value)),
                is_bool($value) => "\x04".($value ? "\x01" : "\x00"),
                default => throw new InvalidArgumentException(
                    "Wire map value for {$key} must be a string, integer, float, or boolean.",
                ),
            };

            if (strlen($output) > self::MAX_VALUE_BYTES) {
                throw new InvalidArgumentException('Wire maps cannot exceed one megabyte.');
            }
        }

        return $output;
    }

    /** @return array<string, string|int|float|bool> */
    public static function decodeMap(string $payload): array
    {
        if (strlen($payload) > self::MAX_VALUE_BYTES) {
            throw new InvalidArgumentException('Wire maps cannot exceed one megabyte.');
        }

        $offset = 0;
        $count = self::readU16($payload, $offset);
        $values = [];

        for ($index = 0; $index < $count; $index++) {
            $keyLength = self::readU16($payload, $offset);
            $key = self::readBytes($payload, $offset, $keyLength);
            if (preg_match('/^[A-Za-z][A-Za-z0-9_]{0,254}$/D', $key) !== 1) {
                throw new InvalidArgumentException('Wire map contains an invalid key.');
            }
            if (array_key_exists($key, $values)) {
                throw new InvalidArgumentException('Wire map contains a duplicate key.');
            }
            $tag = ord(self::readBytes($payload, $offset, 1));
            $values[$key] = match ($tag) {
                1 => self::validatedText(
                    self::readBytes($payload, $offset, self::readU32($payload, $offset)),
                ),
                2 => self::readInteger($payload, $offset),
                3 => self::readFloat($payload, $offset),
                4 => self::readBoolean($payload, $offset),
                default => throw new InvalidArgumentException("Unknown wire value tag {$tag}."),
            };
        }

        if ($offset !== strlen($payload)) {
            throw new InvalidArgumentException('Wire payload contains trailing bytes.');
        }

        return $values;
    }

    public static function u16(int $value): string
    {
        if ($value < 0 || $value > 65_535) {
            throw new InvalidArgumentException('Value does not fit an unsigned 16-bit integer.');
        }

        return pack('v', $value);
    }

    public static function u32(int $value): string
    {
        if ($value < 0 || $value > 4_294_967_295) {
            throw new InvalidArgumentException('Value does not fit an unsigned 32-bit integer.');
        }

        return pack('V', $value);
    }

    public static function u64(int $value): string
    {
        if ($value < 0) {
            throw new InvalidArgumentException('Node identifiers cannot be negative.');
        }

        return pack('P', $value);
    }

    public static function sized(string $value): string
    {
        if (strlen($value) > self::MAX_VALUE_BYTES) {
            throw new InvalidArgumentException('Wire values cannot exceed one megabyte.');
        }

        return self::u32(strlen($value)).$value;
    }

    private static function validatedText(string $value): string
    {
        if (preg_match('//u', $value) !== 1) {
            throw new InvalidArgumentException('Wire text must contain valid UTF-8.');
        }

        return $value;
    }

    private static function validatedFloat(float $value): float
    {
        if (!is_finite($value)) {
            throw new InvalidArgumentException('Wire decimals must be finite.');
        }

        return $value;
    }

    private static function readU16(string $payload, int &$offset): int
    {
        $result = unpack('vvalue', self::readBytes($payload, $offset, 2));

        if ($result === false || !isset($result['value']) || !is_int($result['value'])) {
            throw new InvalidArgumentException('Cannot decode an unsigned 16-bit integer.');
        }

        return $result['value'];
    }

    private static function readU32(string $payload, int &$offset): int
    {
        $result = unpack('Vvalue', self::readBytes($payload, $offset, 4));

        if ($result === false || !isset($result['value']) || !is_int($result['value'])) {
            throw new InvalidArgumentException('Cannot decode an unsigned 32-bit integer.');
        }

        return $result['value'];
    }

    private static function readInteger(string $payload, int &$offset): int
    {
        $result = unpack('Pvalue', self::readBytes($payload, $offset, 8));

        if ($result === false || !isset($result['value']) || !is_int($result['value'])) {
            throw new InvalidArgumentException('Cannot decode an integer.');
        }

        return $result['value'];
    }

    private static function readFloat(string $payload, int &$offset): float
    {
        $result = unpack('evalue', self::readBytes($payload, $offset, 8));

        if ($result === false || !isset($result['value']) || !is_float($result['value'])) {
            throw new InvalidArgumentException('Cannot decode a floating-point value.');
        }

        return self::validatedFloat($result['value']);
    }

    private static function readBoolean(string $payload, int &$offset): bool
    {
        return match (ord(self::readBytes($payload, $offset, 1))) {
            0 => false,
            1 => true,
            default => throw new InvalidArgumentException('Wire map contains an invalid boolean.'),
        };
    }

    private static function readBytes(string $payload, int &$offset, int $length): string
    {
        if ($length < 0 || $offset + $length > strlen($payload)) {
            throw new InvalidArgumentException('Wire payload is truncated.');
        }

        $value = substr($payload, $offset, $length);
        $offset += $length;

        return $value;
    }
}
