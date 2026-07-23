<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use InvalidArgumentException;

final class Wire
{
    public const MAX_VALUE_BYTES = 1_048_576;

    /** @param list<string> $items */
    public static function stringList(array $items): string
    {
        $output = self::u32(count($items));

        foreach ($items as $item) {
            $output .= self::sized($item);
        }

        return $output;
    }

    /**
     * @param array<string, list<string>> $sections
     */
    public static function stringSections(array $sections): string
    {
        $output = self::u32(count($sections));

        foreach ($sections as $title => $items) {
            $output .= self::sized($title).self::u32(count($items));

            foreach ($items as $item) {
                $output .= self::sized($item);
            }
        }

        if (strlen($output) > self::MAX_VALUE_BYTES) {
            throw new InvalidArgumentException('Section data cannot exceed one megabyte.');
        }

        return $output;
    }

    /**
     * @param array<string, string|int|float|bool> $values
     */
    public static function map(array $values): string
    {
        $output = self::u16(count($values));

        foreach ($values as $key => $value) {
            if ($key === '' || strlen($key) > 255) {
                throw new InvalidArgumentException('Wire map keys must contain between 1 and 255 bytes.');
            }

            $output .= self::u16(strlen($key)).$key;
            $output .= match (true) {
                is_string($value) => "\x01".self::sized($value),
                is_int($value) => "\x02".pack('P', $value),
                is_float($value) => "\x03".pack('e', $value),
                is_bool($value) => "\x04".($value ? "\x01" : "\x00"),
            };
        }

        return $output;
    }

    /** @return array<string, string|int|float|bool> */
    public static function decodeMap(string $payload): array
    {
        $offset = 0;
        $count = self::readU16($payload, $offset);
        $values = [];

        for ($index = 0; $index < $count; $index++) {
            $keyLength = self::readU16($payload, $offset);
            $key = self::readBytes($payload, $offset, $keyLength);
            $tag = ord(self::readBytes($payload, $offset, 1));
            $values[$key] = match ($tag) {
                1 => self::readBytes($payload, $offset, self::readU32($payload, $offset)),
                2 => self::readInteger($payload, $offset),
                3 => self::readFloat($payload, $offset),
                4 => ord(self::readBytes($payload, $offset, 1)) === 1,
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

        return $result['value'];
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
