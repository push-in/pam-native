<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

use Pam\Native\TemplateException;
use Throwable;

/**
 * Structured, path-shortened description of an uncaught runtime error. It is
 * what the native error overlay renders and what App::onError() listeners
 * (crash reporters such as Sentry) receive next to the original Throwable.
 */
final readonly class RuntimeError
{
    private const int MAX_FRAMES = 64;
    private const int SNIPPET_CONTEXT = 3;
    private const int MAX_SOURCE_BYTES = 2_097_152;
    private const int MAX_SNIPPET_LINE = 240;

    /**
     * @param list<StackFrame> $frames
     * @param array{file: string, line: int, start: int, lines: list<string>}|null $snippet
     */
    public function __construct(
        public string $type,
        public string $message,
        public string $file,
        public int $line,
        public int $column,
        public RuntimeErrorPhase $phase,
        public array $frames,
        public ?int $appFrame,
        public ?array $snippet,
        public string $fingerprint,
    ) {
    }

    public function fatal(): bool
    {
        return $this->phase->fatal();
    }

    public static function from(
        Throwable $error,
        RuntimeErrorPhase $phase,
        bool $withSnippet = true,
    ): self {
        $template = $error instanceof TemplateException ? $error->template : null;
        $file = self::shortenPath($template ?? $error->getFile());
        $line = $error instanceof TemplateException ? $error->templateLine : $error->getLine();
        $column = $error instanceof TemplateException ? $error->templateColumn : 1;
        $frames = self::frames($error);
        if ($template !== null) {
            array_unshift($frames, new StackFrame($file, $line, 'template', StackFrame::APP));
        }
        $appFrame = null;
        foreach ($frames as $index => $frame) {
            if ($frame->kind === StackFrame::APP) {
                $appFrame = $index;
                break;
            }
        }
        $snippet = null;
        if ($withSnippet) {
            $source = $appFrame !== null ? $frames[$appFrame] : null;
            $snippet = $source !== null && $source->file !== null
                ? self::snippet($template ?? self::sourcePath($error, $appFrame), $source->file, $source->line)
                : null;
        }

        return new self(
            type: $error::class,
            message: $error->getMessage(),
            file: $file,
            line: $line,
            column: $column,
            phase: $phase,
            frames: $frames,
            appFrame: $appFrame,
            snippet: $snippet,
            fingerprint: hash('xxh3', $error::class."\0".$error->getMessage()."\0".$file."\0".$line),
        );
    }

    /**
     * Strips device bundle prefixes (`/data/user/0/<pkg>/files/pam/releases/<hash>/`)
     * and the project root, so frames read like `app/Screens/Chat.php`.
     */
    public static function shortenPath(string $path): string
    {
        $short = preg_replace('#^.*?/files/pam/(?:ota-)?releases/[^/]+/#', '', $path, 1) ?? $path;
        if ($short !== $path) {
            return $short;
        }
        foreach (self::projectRoots() as $root) {
            if (str_starts_with($path, $root.'/')) {
                return substr($path, strlen($root) + 1);
            }
        }

        return $path;
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'type' => $this->type,
            'message' => $this->message,
            'file' => $this->file,
            'line' => $this->line,
            'column' => $this->column,
            'phase' => $this->phase->value,
            'fatal' => $this->fatal(),
            'frames' => array_map(static fn (StackFrame $frame): array => $frame->toArray(), $this->frames),
            'appFrame' => $this->appFrame,
            'snippet' => $this->snippet,
            'fingerprint' => $this->fingerprint,
        ];
    }

    /** Human-readable trace with shortened paths, like Throwable::getTraceAsString(). */
    public function traceAsString(): string
    {
        $lines = [];
        foreach ($this->frames as $index => $frame) {
            $location = $frame->file === null ? '[internal function]' : $frame->file.'('.$frame->line.')';
            $lines[] = '#'.$index.' '.$location.': '.$frame->call;
        }

        return implode("\n", $lines);
    }

    /** @return list<StackFrame> */
    private static function frames(Throwable $error): array
    {
        $trace = $error->getTrace();
        $frames = [];
        $file = $error->getFile();
        $line = $error->getLine();
        foreach ($trace as $entry) {
            $frames[] = self::frame($file, $line, self::call($entry));
            if (count($frames) >= self::MAX_FRAMES) {
                return $frames;
            }
            $file = isset($entry['file']) ? (string) $entry['file'] : null;
            $line = (int) ($entry['line'] ?? 0);
        }
        $frames[] = self::frame($file, $line, '{main}');

        return $frames;
    }

    private static function frame(?string $file, int $line, string $call): StackFrame
    {
        if ($file === null || $file === '') {
            return new StackFrame(null, 0, $call, StackFrame::INTERNAL);
        }
        $kind = StackFrame::APP;
        $framework = dirname(__DIR__);
        if (str_starts_with($file, $framework.'/') || str_contains($file, '/vendor/pushinbr/pam-native')) {
            $kind = StackFrame::FRAMEWORK;
        } elseif (str_contains($file, '/vendor/') || str_starts_with($file, 'vendor/')) {
            $kind = StackFrame::VENDOR;
        }

        return new StackFrame(self::shortenPath($file), $line, $call, $kind);
    }

    /** @param array{function: string, class?: class-string, type?: '->'|'::'} $entry */
    private static function call(array $entry): string
    {
        $function = $entry['function'];
        $class = $entry['class'] ?? '';
        $type = $entry['type'] ?? '';

        $call = ($class !== '' ? $class.$type : '').$function.'()';
        // Anonymous classes and closures embed NUL bytes and absolute paths.
        $call = preg_replace('/@anonymous\x00[^$]*\$[0-9a-f]+/', '@anonymous', $call) ?? $call;

        return preg_replace('/\{closure:[^{}]*\}/', '{closure}', $call) ?? $call;
    }

    private static function sourcePath(Throwable $error, int $appFrame): ?string
    {
        if ($appFrame === 0) {
            return $error->getFile();
        }
        $entry = $error->getTrace()[$appFrame - 1] ?? null;

        return is_array($entry) && isset($entry['file']) ? (string) $entry['file'] : null;
    }

    /** @return array{file: string, line: int, start: int, lines: list<string>}|null */
    private static function snippet(?string $path, string $file, int $line): ?array
    {
        if ($path === null || $line < 1 || !is_file($path) || !is_readable($path)) {
            return null;
        }
        $size = @filesize($path);
        if ($size === false || $size > self::MAX_SOURCE_BYTES) {
            return null;
        }
        $source = @file($path, FILE_IGNORE_NEW_LINES);
        if ($source === false || $line > count($source)) {
            return null;
        }
        $start = max(1, $line - self::SNIPPET_CONTEXT);
        $end = min(count($source), $line + self::SNIPPET_CONTEXT);
        $lines = [];
        for ($number = $start; $number <= $end; $number++) {
            $lines[] = self::clip(rtrim($source[$number - 1]));
        }

        return ['file' => $file, 'line' => $line, 'start' => $start, 'lines' => $lines];
    }

    /** Clips long source lines without ext-mbstring (absent on Android). */
    private static function clip(string $line): string
    {
        if (strlen($line) <= self::MAX_SNIPPET_LINE) {
            return $line;
        }

        return preg_match('/^.{0,'.self::MAX_SNIPPET_LINE.'}/su', $line, $match) === 1
            ? $match[0].'…'
            : substr($line, 0, self::MAX_SNIPPET_LINE).'…';
    }

    /** @return list<string> */
    private static function projectRoots(): array
    {
        $roots = [];
        $script = $_SERVER['SCRIPT_FILENAME'] ?? null;
        if (is_string($script) && $script !== '' && str_starts_with($script, '/')) {
            $roots[] = dirname($script);
        }
        $cwd = getcwd();
        if ($cwd !== false && $cwd !== '/') {
            $roots[] = $cwd;
        }

        $roots = array_values(array_unique($roots));
        // The outermost root keeps the most context (`app/Chat.php`, not `Chat.php`).
        usort($roots, static fn (string $a, string $b): int => strlen($a) <=> strlen($b));

        return $roots;
    }
}
