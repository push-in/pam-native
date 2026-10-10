<?php

declare(strict_types=1);

use Pam\Native\CapturedImage;
use Pam\Native\ImageCaptureFormat;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\Modules\NativeModuleTransport;
use Pam\Native\System\ViewCapture;

if (!class_exists(ViewCapture::class)) {
    spl_autoload_register(static function (string $class): void {
        if (!str_starts_with($class, 'Pam\\Native\\')) return;
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen('Pam\\Native\\'))).'.php';
        if (is_file($path)) require $path;
    });
}

$viewCaptureCalls = [];
$viewCaptureReply = null;
NativeModules::useTransport(new class ($viewCaptureCalls, $viewCaptureReply) implements NativeModuleTransport {
    public function __construct(private array &$calls, private ?array &$reply)
    {
    }

    public function invoke(int $requestId, string $module, string $method, string $payload, Closure $complete): void
    {
        $this->calls[] = [$module, $method, Wire::decodeMap($payload)];
        [$status, $body] = $this->reply ?? [ModuleResultStatus::Failure, 'unset'];
        $complete($status, $body);
    }
});

$captured = [];
$viewCaptureReply = [ModuleResultStatus::Success, Wire::map(['path' => 'story-stickers/capture-1.png', 'name' => 'capture-1.png', 'size' => 2048, 'width' => 1008, 'height' => 750, 'mimeType' => 'image/png'])];
ViewCapture::capture('story-sticker-1', static function (?CapturedImage $image, string $error) use (&$captured): void {
    $captured[] = [$image, $error];
}, pixelRatio: 3.0, directory: 'story-stickers');
[$module, $method, $values] = $viewCaptureCalls[0] ?? ['', '', []];
$image = $captured[0][0] ?? null;
if ($module !== 'view-capture' || $method !== 'capture' || ($values['ref'] ?? '') !== 'story-sticker-1'
    || (float) ($values['pixelRatio'] ?? 0) !== 3.0 || (int) ($values['format'] ?? 0) !== ImageCaptureFormat::Png->value
    || ($values['directory'] ?? '') !== 'story-stickers'
    || !$image instanceof CapturedImage || $image->file->path !== 'story-stickers/capture-1.png'
    || $image->file->mimeType !== 'image/png' || $image->file->size !== 2048
    || $image->width !== 1008 || $image->height !== 750 || abs($image->aspectRatio() - 1.344) > 0.001) {
    throw new RuntimeException('ViewCapture must send the ref, ratio, format and folder and map the private image: '.json_encode([$viewCaptureCalls, $captured]));
}

$viewCaptureReply = [ModuleResultStatus::Failure, 'No mounted view has nativeRef missing.'];
ViewCapture::capture('missing', static function (?CapturedImage $image, string $error) use (&$captured): void {
    $captured[] = [$image, $error];
}, format: ImageCaptureFormat::Jpeg);
if (!array_key_exists(1, $captured) || $captured[1][0] !== null || ($captured[1][1] ?? '') !== 'No mounted view has nativeRef missing.'
    || (int) ($viewCaptureCalls[1][2]['format'] ?? 0) !== ImageCaptureFormat::Jpeg->value
    || ($viewCaptureCalls[1][2]['directory'] ?? '') !== 'view-captures') {
    throw new RuntimeException("A failed capture reports its error without an image: ".json_encode([$viewCaptureCalls[1] ?? null, $captured[1] ?? null]));
}

foreach ([
    static fn () => ViewCapture::capture('bad ref', static fn () => null),
    static fn () => ViewCapture::capture('ok', static fn () => null, pixelRatio: -1.0),
    static fn () => ViewCapture::capture('ok', static fn () => null, pixelRatio: NAN),
    static fn () => ViewCapture::capture('ok', static fn () => null, directory: '../outside'),
    static fn () => ViewCapture::capture('ok', static fn () => null, directory: 'a/../b'),
] as $bad) {
    try {
        $bad();
        throw new RuntimeException('An unsafe view capture request was accepted.');
    } catch (InvalidArgumentException) {
    }
}
NativeModules::useTransport(null);
echo "VIEW_CAPTURE_OK ref=native-ref ratio=bounded folder=private result=file+size\n";
