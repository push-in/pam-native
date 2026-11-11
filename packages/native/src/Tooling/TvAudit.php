<?php

declare(strict_types=1);

namespace Pam\Native\Tooling;

use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\PamPhpCompiler;

use function array_keys;
use function implode;
use function is_dir;
use function sprintf;
use function str_starts_with;
use function strlen;
use function substr;

/**
 * Warns about actions a TV remote cannot reach: an element that reacts only to
 * touch gestures (long press, double tap, drag, raw touches) with no press,
 * key handler or focus stop. Run by the CLI when the app targets android-tv;
 * it never fails the build.
 */
final class TvAudit
{
    /** Template events and attributes only a touch screen can trigger. */
    private const array TOUCH_ONLY = [
        'on:longPress' => 'longPress',
        'on:doubleTap' => 'doubleTap',
        'on:gestureBegin' => 'gesture',
        'on:gestureUpdate' => 'gesture',
        'on:gestureEnd' => 'gesture',
        'on:dragStart' => 'drag',
        'on:drop' => 'drop',
        'gestureDrag' => 'drag',
        'drag' => 'drag',
        'p-touch-start' => 'touch',
        'p-touch-move' => 'touch',
        'p-touch-end' => 'touch',
    ];

    /** Any one of these makes the element reachable from the remote. */
    private const array REACHABLE = ['on:press', 'on:keyDown', 'focusable', ':focusable', 'href', 'route', 'to'];

    /** @return list<string> one line per finding, `file:line: message` */
    public static function findings(string $sourcePath, string $cachePath): array
    {
        if (!is_dir($sourcePath)) {
            return [];
        }
        $components = PamPhpCompiler::compileDirectory($sourcePath, $cachePath);
        $withTvVariant = [];
        foreach ($components as $component) {
            if (PamPhpCompiler::variantOf($component->source) === 'tv') {
                $withTvVariant[$component->className] = true;
            }
        }
        $findings = [];
        foreach ($components as $component) {
            $variant = PamPhpCompiler::variantOf($component->source);
            if ($variant === 'phone' || $variant === 'tablet') {
                continue;
            }
            if ($variant === null && isset($withTvVariant[$component->className])) {
                continue;
            }
            self::walk($component->template, $sourcePath, $findings);
        }

        return $findings;
    }

    public static function run(string $root): int
    {
        // The cache stays out of the staged bundle.
        $findings = self::findings(
            $root.'/src',
            sys_get_temp_dir().'/pam-tv-audit-'.hash('xxh64', $root),
        );
        foreach ($findings as $finding) {
            fwrite(STDERR, 'PAM Native warning: '.$finding."\n");
        }

        return 0;
    }

    /** @param list<string> $findings */
    private static function walk(CompiledTemplateNode $node, string $root, array &$findings): void
    {
        $attributes = [];
        foreach (array_keys($node->attributes) as $name) {
            $name = (string) $name;
            $attributes[str_starts_with($name, '@') ? 'on:'.substr($name, 1) : $name] = true;
        }
        $gestures = [];
        foreach (self::TOUCH_ONLY as $attribute => $gesture) {
            if (isset($attributes[$attribute]) || isset($attributes[':'.$attribute])) {
                $gestures[$gesture] = true;
            }
        }
        if ($gestures !== [] && $node->name !== 'Input') {
            $reachable = false;
            foreach (self::REACHABLE as $attribute) {
                if (isset($attributes[$attribute])) {
                    $reachable = true;
                    break;
                }
            }
            if (!$reachable) {
                $file = str_starts_with($node->source, $root.'/')
                    ? 'src/'.substr($node->source, strlen($root) + 1)
                    : $node->source;
                $findings[] = sprintf(
                    '%s:%d: <%s> reacts only to touch (%s); a TV remote cannot reach it. '
                    .'Add on:press, or focusable="true" with on:keyDown, or a .tv.pam.php variant.',
                    $file,
                    $node->line,
                    $node->name,
                    implode(', ', array_keys($gestures)),
                );
            }
        }
        foreach ($node->children as $child) {
            if ($child instanceof CompiledTemplateNode) {
                self::walk($child, $root, $findings);
            }
        }
    }
}
