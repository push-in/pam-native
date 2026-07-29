<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\FileReference;
use Pam\Native\ImageCropRatio;
use Pam\Native\ImageFilterType;
use Pam\Native\Modules\NativeModules;

final class ImageEditor
{
    private function __construct()
    {
    }

    /** @param Closure(?FileReference, string): void $callback */
    public static function render(
        FileReference $source,
        ImageCropRatio $cropRatio,
        ImageFilterType $filter,
        int $quarterTurns,
        bool $flipHorizontal,
        string $overlayText,
        Closure $callback,
        int $brightness = 0,
        int $contrast = 0,
        int $saturation = 0,
        string $sticker = '',
    ): int {
        return NativeModules::call(
            'image-editor',
            'render',
            [
                'brightness' => self::adjustment($brightness),
                'contrast' => self::adjustment($contrast),
                'cropRatio' => $cropRatio->value,
                'filter' => $filter->value,
                'flipHorizontal' => $flipHorizontal ? 1 : 0,
                'overlayText' => mb_substr(trim($overlayText), 0, 120),
                'path' => $source->path,
                'quarterTurns' => (($quarterTurns % 4) + 4) % 4,
                'saturation' => self::adjustment($saturation),
                'sticker' => mb_substr(trim($sticker), 0, 8),
            ],
            static function ($result) use ($callback): void {
                if (!$result->succeeded()) {
                    $callback(null, $result->message());

                    return;
                }
                $values = $result->values();
                $path = (string) ($values['path'] ?? '');
                if ($path === '') {
                    $callback(null, 'The image editor returned no output.');

                    return;
                }
                $callback(
                    new FileReference(
                        path: $path,
                        name: (string) ($values['name'] ?? 'image-edit.jpg'),
                        mimeType: 'image/jpeg',
                        size: (int) ($values['size'] ?? 0),
                    ),
                    '',
                );
            },
        );
    }

    private static function adjustment(int $value): int
    {
        return max(-100, min(100, $value));
    }
}
