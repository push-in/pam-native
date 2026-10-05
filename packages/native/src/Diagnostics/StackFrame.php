<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

final readonly class StackFrame
{
    public const string APP = 'app';
    public const string FRAMEWORK = 'framework';
    public const string VENDOR = 'vendor';
    public const string INTERNAL = 'internal';

    public function __construct(
        /** App-relative path, or null for internal PHP functions. */
        public ?string $file,
        public int $line,
        /** `Class->method()`, `function()` or `{main}`. */
        public string $call,
        /** One of the APP, FRAMEWORK, VENDOR or INTERNAL constants. */
        public string $kind,
    ) {
    }

    /** @return array{file: ?string, line: int, call: string, kind: string} */
    public function toArray(): array
    {
        return [
            'file' => $this->file,
            'line' => $this->line,
            'call' => $this->call,
            'kind' => $this->kind,
        ];
    }
}
