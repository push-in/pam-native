<?php

declare(strict_types=1);

namespace Pam\Native\Web;

/** A solid PNG for launcher icons when the app has none (no GD needed). */
final class Png
{
    public static function solid(int $width, int $height, int $red, int $green, int $blue): string
    {
        $row = "\0".str_repeat(chr($red).chr($green).chr($blue), $width);
        $data = gzcompress(str_repeat($row, $height), 9);

        return "\x89PNG\r\n\x1a\n"
            .self::chunk('IHDR', pack('NNCCCCC', $width, $height, 8, 2, 0, 0, 0))
            .self::chunk('IDAT', $data === false ? '' : $data)
            .self::chunk('IEND', '');
    }

    private static function chunk(string $type, string $data): string
    {
        return pack('N', strlen($data)).$type.$data.pack('N', crc32($type.$data));
    }
}
