<?php

declare(strict_types=1);

namespace Pam\Native;

/** Encoding of a ViewCapture image. */
enum ImageCaptureFormat: int
{
    /** Lossless, keeps transparency (stickers, cards). */
    case Png = 1;
    /** Smaller, opaque (photos, backgrounds). */
    case Jpeg = 2;

    public function mimeType(): string
    {
        return $this === self::Png ? 'image/png' : 'image/jpeg';
    }
}
