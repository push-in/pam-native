<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\Animation\Drag;
use Pam\Native\CapturedImage;
use Pam\Native\FileReference;
use Pam\Native\ImageCaptureFormat;
use Pam\Native\Modules\NativeModules;

/**
 * Renders a mounted view and its subtree into a private image file, like
 * React Native's view-shot: the view is found by its `nativeRef` attribute
 * and drawn with its own content only, so a view kept invisible by an
 * ancestor (opacity 0, off screen, under other content) captures as it would
 * look on screen. Typical use: compose a sticker, card or share image with
 * ordinary components and real text layout, then hand the file to the image
 * editor, an upload or the share sheet.
 *
 * ```php
 * ViewCapture::capture('share-card', function (?CapturedImage $image, string $error): void {
 *     if ($image !== null) Share::files([$image->file]);
 * }, pixelRatio: 3.0);
 * ```
 */
final class ViewCapture
{
    private function __construct()
    {
    }

    /**
     * @param string $ref the `nativeRef` of a mounted, laid-out view
     * @param Closure(?CapturedImage, string): void $callback the image, or null and the error
     * @param float $pixelRatio output pixels per point; 0 uses the screen density. Each side is capped at 4096 px.
     * @param string $directory private folder (below the app's files) for the image
     */
    public static function capture(
        string $ref,
        Closure $callback,
        float $pixelRatio = 0.0,
        ImageCaptureFormat $format = ImageCaptureFormat::Png,
        int $quality = 92,
        string $directory = 'view-captures',
    ): int {
        if (!is_finite($pixelRatio) || $pixelRatio < 0.0 || $pixelRatio > 8.0) {
            throw new InvalidArgumentException('ViewCapture pixelRatio must be between 0 (screen density) and 8.');
        }
        $directory = trim($directory, '/');
        if (preg_match('#^[A-Za-z0-9_.-]{1,64}(/[A-Za-z0-9_.-]{1,64}){0,3}$#D', $directory) !== 1
            || preg_match('#(^|/)\.{1,2}(/|$)#', $directory) === 1) {
            throw new InvalidArgumentException('ViewCapture directory must be a relative folder of letters, digits, "_", "-" or ".".');
        }

        return NativeModules::call(
            'view-capture',
            'capture',
            [
                'directory' => $directory,
                'format' => $format->value,
                'pixelRatio' => $pixelRatio,
                'quality' => max(1, min(100, $quality)),
                'ref' => Drag::ref($ref),
            ],
            static function ($result) use ($callback, $format): void {
                if (!$result->succeeded()) {
                    $callback(null, $result->message());

                    return;
                }
                $values = $result->values();
                $path = (string) ($values['path'] ?? '');
                if ($path === '') {
                    $callback(null, 'The view capture returned no image.');

                    return;
                }
                $callback(
                    new CapturedImage(
                        new FileReference(
                            path: $path,
                            name: (string) ($values['name'] ?? basename($path)),
                            mimeType: (string) ($values['mimeType'] ?? $format->mimeType()),
                            size: (int) ($values['size'] ?? 0),
                        ),
                        (int) ($values['width'] ?? 0),
                        (int) ($values['height'] ?? 0),
                    ),
                    '',
                );
            },
        );
    }
}
