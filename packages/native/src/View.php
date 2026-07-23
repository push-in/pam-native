<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;

final class View implements Renderable
{
    private static ?string $basePath = null;
    private static ?string $cachePath = null;

    /** @param array<string, mixed> $data */
    private function __construct(
        private readonly string $name,
        private readonly array $data,
        private readonly ?object $scope = null,
    ) {
    }

    /** @param array<string, mixed> $data */
    public static function make(string $name, array $data = []): self
    {
        if (preg_match('/^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/', $name) !== 1) {
            throw new InvalidArgumentException('View names must use dot notation.');
        }

        return new self($name, $data);
    }

    public static function configure(string $basePath, ?string $cachePath = null): void
    {
        $resolved = realpath($basePath);

        if ($resolved === false || !is_dir($resolved)) {
            throw new InvalidArgumentException("View directory {$basePath} does not exist.");
        }

        self::$basePath = $resolved;
        self::$cachePath = $cachePath;
    }

    public function withScope(object $scope): self
    {
        return new self($this->name, $this->data, $scope);
    }

    public function toElement(): Element
    {
        $base = self::$basePath ?? getcwd().'/resources/native';
        $source = $base.'/'.str_replace('.', '/', $this->name).'.pam';
        $resolved = realpath($source);
        $baseResolved = realpath($base);

        if (
            $resolved === false
            || $baseResolved === false
            || !str_starts_with($resolved, $baseResolved.DIRECTORY_SEPARATOR)
        ) {
            throw new InvalidArgumentException("View {$this->name} was not found.");
        }

        $tree = TemplateCompiler::load($resolved, self::$cachePath);

        return TemplateRenderer::render($tree, $this->scope, $this->data);
    }
}
