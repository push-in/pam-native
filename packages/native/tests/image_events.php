<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\ImageErrorEvent;
use Pam\Native\ImageLoadEvent;
use Pam\Native\ImageProgressEvent;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateExpression;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\Wire;

if (!class_exists(TemplateRenderer::class)) {
    spl_autoload_register(static function (string $class): void {
        $prefix = 'Pam\\Native\\';
        if (!str_starts_with($class, $prefix)) return;
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) require $path;
    });
}

$assertImageEvent = static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
$imageEventScope = new class {
    public ?ImageLoadEvent $load = null;
    public ?ImageErrorEvent $error = null;
    public ?ImageProgressEvent $progress = null;
    public string $asset = '';
    public string $raw = '';
    public mixed $untyped = null;
    public int $withoutEventCalls = 0;

    public function loaded(ImageLoadEvent $event): void { $this->load = $event; }
    public function failed(ImageErrorEvent $event): void { $this->error = $event; }
    public function progressed(ImageProgressEvent $event): void { $this->progress = $event; }
    public function taggedLoad(string $asset, ImageLoadEvent $event): void
    {
        $this->asset = $asset;
        $this->load = $event;
    }
    public function raw(string $event): void { $this->raw = $event; }
    public function untyped($event): void { $this->untyped = $event; }
    public function withoutEvent(): void { $this->withoutEventCalls++; }
};
$imageEventCases = [
    [EventKind::ImageLoad, 'load', Wire::map(['uri' => 'file:///photo.jpg', 'width' => 320.5, 'height' => 240.0]), new ImageLoadEvent('file:///photo.jpg', 320.5, 240.0)],
    [EventKind::ImageError, 'error', Wire::map(['error' => 'Decode failed']), new ImageErrorEvent('Decode failed')],
    [EventKind::ImageProgress, 'progress', Wire::map(['loaded' => 32, 'total' => 128]), new ImageProgressEvent(32, 128)],
];
$directImageTemplate = TemplateCompiler::compile(<<<'PAM'
<Image source="file:///photo.jpg" on:load="loaded" on:error="failed" on:progress="progressed" />
PAM);
$expressionImageTemplate = TemplateCompiler::compile(<<<'PAM'
<Image source="file:///photo.jpg" on:load="taggedLoad('asset-2', $event)" on:error="failed($event)" on:progress="progressed($event)" />
PAM);

try {
    foreach ([false, true] as $generatedImageExpressions) {
        TemplateExpression::useGeneratedCode($generatedImageExpressions);
        foreach ([$directImageTemplate, $expressionImageTemplate] as $imageTemplate) {
            $imageElement = TemplateRenderer::render($imageTemplate, $imageEventScope, []);
            foreach ($imageEventCases as [$kind, $property, $payload, $expected]) {
                $imageEventScope->{$property} = null;
                $imageElement->events()[$kind->value]($payload);
                $assertImageEvent(
                    $imageEventScope->{$property} == $expected,
                    'Compiled image handlers must hydrate '.$expected::class.' from the native wire payload.',
                );
                // Component relays may forward an object that was already decoded.
                $imageElement->events()[$kind->value]($expected);
                $assertImageEvent(
                    $imageEventScope->{$property} === $expected,
                    'Already decoded image events must preserve object identity.',
                );
            }
        }
        $assertImageEvent($imageEventScope->asset === 'asset-2', 'Explicit event arguments must preserve the item key.');

        foreach (['raw', 'untyped', 'withoutEvent'] as $handler) {
            foreach ([false, true] as $explicit) {
                $binding = $explicit ? $handler.'('.($handler === 'withoutEvent' ? '' : '$event').')' : $handler;
                $legacyImage = TemplateRenderer::render(TemplateCompiler::compile(
                    '<Image on:load="'.$binding.'" on:error="'.$binding.'" on:progress="'.$binding.'" />',
                ), $imageEventScope, []);
                foreach ($imageEventCases as [$kind, , $payload]) {
                    $beforeCalls = $imageEventScope->withoutEventCalls;
                    $legacyImage->events()[$kind->value]($payload);
                    $assertImageEvent(
                        match ($handler) {
                            'raw' => $imageEventScope->raw === $payload,
                            'untyped' => $imageEventScope->untyped === $payload,
                            default => $imageEventScope->withoutEventCalls === $beforeCalls + 1,
                        },
                        'Existing string, untyped and zero-argument image handlers must retain their contract.',
                    );
                }
            }
        }
    }
} finally {
    TemplateExpression::useGeneratedCode(true);
}

echo "IMAGE_EVENTS_OK compiled=direct,expression generated=both typed=load,error,progress raw=preserved objects=preserved\n";
