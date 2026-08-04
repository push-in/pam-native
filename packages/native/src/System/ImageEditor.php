<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\FileReference;
use Pam\Native\ImageCropRatio;
use Pam\Native\ImageFilterType;
use Pam\Native\ImageTextLayerStyle;
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
        int $maxWidth = 0,
        int $maxHeight = 0,
        int $outputQuality = 94,
        string $drawing = '',
        array $textLayers = [],
    ): int {
        return NativeModules::call(
            'image-editor',
            'render',
            [
                'brightness' => self::adjustment($brightness),
                'contrast' => self::adjustment($contrast),
                'cropRatio' => $cropRatio->value,
                'drawing' => self::drawing($drawing),
                'filter' => $filter->value,
                'flipHorizontal' => $flipHorizontal ? 1 : 0,
                'maxHeight' => max(0, $maxHeight),
                'maxWidth' => max(0, $maxWidth),
                'textLayers' => self::textLayers($textLayers),
                'overlayText' => mb_substr(trim($overlayText), 0, 120),
                'outputQuality' => max(1, min(100, $outputQuality)),
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

    private static function drawing(string $value): string
    {
        $value = trim($value);
        if ($value === '') {
            return '';
        }
        if (strlen($value) > 524_288 || !json_validate($value, 64)) {
            throw new InvalidArgumentException(
                'Image editor drawing must be valid JSON no larger than 512 KiB.',
            );
        }

        return $value;
    }

    /** @param list<array<string, mixed>> $layers */
    private static function textLayers(array $layers): string
    {
        if (count($layers) > 80) {
            throw new InvalidArgumentException('Image editor accepts at most 80 text layers.');
        }
        $normalized = [];
        foreach ($layers as $layer) {
            if (!is_array($layer)) {
                throw new InvalidArgumentException('Image editor text layers must be maps.');
            }
            $text = mb_substr(trim((string) ($layer['text'] ?? '')), 0, 500);
            if ($text === '') {
                continue;
            }
            $color = strtoupper(trim((string) ($layer['color'] ?? '#FFFFFF')));
            if (preg_match('/^#[0-9A-F]{6}([0-9A-F]{2})?$/', $color) !== 1) {
                $color = '#FFFFFF';
            }
            $normalized[] = [
                'color' => $color,
                'rotation' => max(-M_PI * 2, min(M_PI * 2, (float) ($layer['rotation'] ?? 0.0))),
                'scale' => max(0.25, min(4.0, (float) ($layer['scale'] ?? 1.0))),
                'styleType' => ImageTextLayerStyle::tryFrom(
                    (int) ($layer['style_type'] ?? $layer['styleType'] ?? 1),
                )?->value ?? ImageTextLayerStyle::Plain->value,
                'text' => $text,
                'x' => max(0.0, min(1.0, (float) ($layer['x'] ?? 0.5))),
                'y' => max(0.0, min(1.0, (float) ($layer['y'] ?? 0.5))),
            ];
        }
        try {
            return json_encode($normalized, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('Image editor text layers are invalid.', previous: $error);
        }
    }
}
