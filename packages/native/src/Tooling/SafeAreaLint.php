<?php

declare(strict_types=1);

namespace Pam\Native\Tooling;

use Pam\Native\Element;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Renderable;
use RuntimeException;

use function array_map;
use function escapeshellarg;
use function file_put_contents;
use function getenv;
use function implode;
use function is_array;
use function is_file;
use function is_string;
use function json_decode;
use function proc_close;
use function proc_open;
use function stream_get_contents;
use function sys_get_temp_dir;
use function tempnam;
use function unlink;

/**
 * Safe-area lint for tests and CI: lays out a rendered tree with the real
 * PAM Native engine (`pam-native safe-area-lint`) under one or more inset
 * configurations and returns every text, input, button, pressable or list
 * end that would end inside a system-bar, cutout or home-indicator inset.
 *
 * ```php
 * $violations = SafeAreaLint::check($screen, [[0, 24, 0, 48], [0, 24, 0, 24]]);
 * ```
 *
 * The CLI is `PAM_NATIVE_CLI` when set, else the package's `bin/pam-native`
 * launcher (which resolves the CLI matching this SDK version).
 */
final class SafeAreaLint
{
    /** Three-button navigation (48) and gesture navigation (24) under a 24 pt status bar. */
    public const array DEFAULT_INSETS = [[0.0, 24.0, 0.0, 48.0], [0.0, 24.0, 0.0, 24.0]];

    private function __construct()
    {
    }

    /** Full binary frame of a rendered tree. */
    public static function frame(Renderable|Element $root): string
    {
        $element = $root instanceof Element ? $root : $root->toElement();
        $frame = (new TreeEncoder())->encode($element)['frame'];
        if (!is_string($frame)) {
            throw new RuntimeException('The tree produced no frame.');
        }

        return $frame;
    }

    /**
     * @param list<array{0: float|int, 1: float|int, 2: float|int, 3: float|int}> $insets left, top, right, bottom
     *
     * @return list<array{insets: list<float>, id: int, kind: string, content: string, edge: string, intrusion: float, testId: ?string, label: ?string}>
     */
    public static function check(
        Renderable|Element|string $root,
        array $insets = self::DEFAULT_INSETS,
        float $width = 360.0,
        float $height = 780.0,
        bool $explicitOnly = false,
    ): array {
        $frame = is_string($root) ? $root : self::frame($root);
        $path = tempnam(sys_get_temp_dir(), 'pam-safe-area-');
        if (!is_string($path)) {
            throw new RuntimeException('Cannot create a temporary frame file.');
        }
        try {
            file_put_contents($path, $frame);
            $command = [self::cli(), 'safe-area-lint', $path, '--json', '--viewport', $width . 'x' . $height];
            if ($explicitOnly) {
                // Without the engine's automatic safe area: what the
                // application clears by itself (renderers before it).
                $command[] = '--explicit-only';
            }
            foreach ($insets as $edges) {
                $command[] = '--insets';
                $command[] = implode(',', [(float) $edges[0], (float) $edges[1], (float) $edges[2], (float) $edges[3]]);
            }
            $process = proc_open(
                implode(' ', array_map(escapeshellarg(...), $command)),
                [1 => ['pipe', 'w'], 2 => ['pipe', 'w']],
                $pipes,
            );
            if ($process === false) {
                throw new RuntimeException('Cannot start pam-native safe-area-lint.');
            }
            $output = (string) stream_get_contents($pipes[1]);
            $errors = (string) stream_get_contents($pipes[2]);
            $status = proc_close($process);
            $decoded = json_decode($output, true);
            if ($status > 1 || !is_array($decoded)) {
                throw new RuntimeException('pam-native safe-area-lint failed: ' . $errors . $output);
            }
            $violations = [];
            foreach ($decoded as $configuration) {
                foreach ($configuration['violations'] as $violation) {
                    $violations[] = ['insets' => $configuration['insets']] + $violation;
                }
            }

            return $violations;
        } finally {
            @unlink($path);
        }
    }

    private static function cli(): string
    {
        $override = getenv('PAM_NATIVE_CLI');
        if (is_string($override) && $override !== '') {
            return $override;
        }
        $launcher = __DIR__ . '/../../bin/pam-native';
        if (!is_file($launcher)) {
            throw new RuntimeException('pam-native CLI not found; set PAM_NATIVE_CLI.');
        }

        return $launcher;
    }
}
