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
        $kinds = [];
        $expression = '';
        $segments = $pattern === '/' ? [] : explode('/', trim($pattern, '/'));
        if ($segments === []) $expression = '/';
        foreach ($segments as $index => $segment) {
            if (preg_match('/^\{([A-Za-z][A-Za-z0-9_]{0,63})([?*]?)\}$/D', $segment, $match) !== 1) {
                if (str_contains($segment, '{') || str_contains($segment, '}')) {
                    throw new InvalidArgumentException('Deep-link parameters must occupy a complete path segment.');
                }
                $expression .= '/'.preg_quote($segment, '#');
                continue;
            }
            $name = $match[1];
            $kind = $match[2];
            if (isset($kinds[$name])) throw new InvalidArgumentException("Duplicate deep-link parameter {$name}.");
            if ($kind === '*' && $index !== count($segments) - 1) {
                throw new InvalidArgumentException('A wildcard deep-link parameter must be the final segment.');
            }
            $names[] = $name;
            $kinds[$name] = $kind;
            $expression .= match ($kind) {
                '?' => '(?:/([^/]+))?',
                '*' => '/(.+)',
                default => '/([^/]+)',
            };
        }
        $this->expression = '#^'.$expression.'$#D';
        $this->parameterNames = $names;
    }

    /** @return array<string, string>|null */
    public function match(string $path): ?array
    {
        $matches = [];
        if (preg_match($this->expression, $path, $matches) !== 1) return null;
        $params = [];
        foreach ($this->parameterNames as $index => $name) {
            $value = $matches[$index + 1] ?? '';
            if ($value !== '') $params[$name] = rawurldecode($value);
        }

        return $params;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function build(array $params): ?string
    {
        $used = [];
        $built = [];
        $segments = $this->pattern === '/' ? [] : explode('/', trim($this->pattern, '/'));
        foreach ($segments as $segment) {
            if (preg_match('/^\{([A-Za-z][A-Za-z0-9_]{0,63})([?*]?)\}$/D', $segment, $match) !== 1) {
                $built[] = $segment;
                continue;
            }
            $name = $match[1];
            $kind = $match[2];
            $value = $params[$name] ?? null;
            if ($value === null) {
                if ($kind === '?') continue;
                return null;
            }
            $used[$name] = true;
            $text = self::scalarToString($value);
            $built[] = $kind === '*'
                ? implode('/', array_map('rawurlencode', explode('/', trim($text, '/'))))
                : rawurlencode($text);
        }
        $path = '/'.implode('/', $built);
        $query = [];
        foreach ($params as $key => $value) {
            if (!isset($used[$key]) && $value !== null) {
                $query[$key] = self::scalarToString($value);
            }
        }

        return $query === [] ? $path : $path.'?'.http_build_query($query, '', '&', PHP_QUERY_RFC3986);
    }

    private static function scalarToString(string|int|float|bool $value): string
    {
        if (is_bool($value)) return $value ? '1' : '0';
        return (string) $value;
    }
}
