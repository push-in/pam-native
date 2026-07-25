<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;

final readonly class DeepLink
{
    private string $expression;

    /** @var list<string> */
    private array $parameterNames;

    public function __construct(
        public string $pattern,
        public string $route,
    ) {
        if ($pattern === '' || $pattern[0] !== '/' || strlen($pattern) > 512) {
            throw new InvalidArgumentException('Deep-link patterns must be absolute paths of at most 512 bytes.');
        }
        $names = [];
        $quoted = preg_quote($pattern, '#');
        $expression = preg_replace_callback(
            '/\\\\\{([A-Za-z][A-Za-z0-9_]{0,63})\\\\\}/',
            static function (array $match) use (&$names): string {
                if (in_array($match[1], $names, true)) {
                    throw new InvalidArgumentException("Duplicate deep-link parameter {$match[1]}.");
                }
                $names[] = $match[1];

                return '([^/]+)';
            },
            $quoted,
        );
        $this->expression = '#^'.($expression ?? $quoted).'$#D';
        $this->parameterNames = $names;
    }

    /** @return array<string, string>|null */
    public function match(string $path): ?array
    {
        $matches = [];
        if (preg_match($this->expression, $path, $matches) !== 1) return null;
        $params = [];
        foreach ($this->parameterNames as $index => $name) {
            $params[$name] = rawurldecode($matches[$index + 1]);
        }

        return $params;
    }
}
