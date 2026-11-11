<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use PhpParser\Node\Stmt;

/** A class, interface, enum or component of the app or of a Composer package, collected before translation. */
final class ClassInfo
{
    public bool $isComponent = false;
    public ?string $template = null;
    public int $templateLine = 1;
    public string $templateFile = '';
    public string $style = '';
    public bool $styleScoped = true;
    public bool $isInterface = false;
    /** @var list<string> */
    public array $interfaces = [];

    /**
     * @param array<string, bool> $methods lower-case name => static
     * @param array<string, true> $properties
     */
    public function __construct(
        public readonly string $name,
        public readonly string $file,
        public readonly Stmt\ClassLike $node,
        public readonly ?string $parent,
        public readonly bool $isEnum,
        public readonly array $methods,
        public readonly array $properties,
    ) {
    }

    public function shortName(): string
    {
        $position = strrpos($this->name, '\\');

        return $position === false ? $this->name : substr($this->name, $position + 1);
    }
}
