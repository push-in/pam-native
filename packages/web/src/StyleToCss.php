<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use RuntimeException;

/**
 * A component `<style>` as browser CSS: scoped rules get the component's
 * scope class, PAM media features (`device-type`, `input-mode`) resolve for
 * the build target, and an author `:focus` rule replaces the default ring.
 */
final class StyleToCss
{
    /** @param array<string, string> $features PAM media feature => value for this target */
    public static function compile(string $style, ?string $scopeClass, array $features, string $file): string
    {
        $style = preg_replace('#/\*.*?\*/#s', '', $style) ?? $style;
        $position = 0;

        return self::rules($style, $position, $scopeClass, $features, $file);
    }

    /** @param array<string, string> $features */
    private static function rules(string $css, int &$position, ?string $scope, array $features, string $file): string
    {
        $out = '';
        $length = strlen($css);
        while ($position < $length) {
            $open = strpos($css, '{', $position);
            $close = strpos($css, '}', $position);
            if ($close !== false && ($open === false || $close < $open)) {
                $position = $close + 1;

                return $out;
            }
            if ($open === false) {
                if (trim(substr($css, $position)) !== '') {
                    throw new RuntimeException("{$file}: unterminated rule in <style>.");
                }
                $position = $length;

                return $out;
            }
            $prelude = trim(substr($css, $position, $open - $position));
            $position = $open + 1;
            if (str_starts_with($prelude, '@media')) {
                $inner = self::rules($css, $position, $scope, $features, $file);
                $query = self::media(trim(substr($prelude, 6)), $features);
                if ($query === true) {
                    $out .= $inner;
                } elseif (is_string($query)) {
                    $out .= "@media {$query}{{$inner}}";
                }
                continue;
            }
            if (str_starts_with($prelude, '@')) {
                $out .= $prelude.'{'.self::rules($css, $position, null, $features, $file).'}';
                continue;
            }
            $end = strpos($css, '}', $position);
            if ($end === false) {
                throw new RuntimeException("{$file}: unterminated rule {$prelude} in <style>.");
            }
            $body = trim(substr($css, $position, $end - $position));
            $position = $end + 1;
            $selectors = array_map('trim', explode(',', $prelude));
            if (array_filter($selectors, static fn (string $s): bool => str_contains($s, ':focus')) !== []) {
                $body = rtrim($body, '; ').';outline:none';
            }
            $out .= implode(',', array_map(static fn (string $s): string => self::scoped($s, $scope), $selectors))
                .'{'.self::declarations($body).'}';
        }

        return $out;
    }

    /** `.tile:focus` → `.tile.s1:focus` (scope on the last compound). */
    private static function scoped(string $selector, ?string $scope): string
    {
        if ($scope === null || $selector === '' || str_starts_with($selector, ':root') || in_array($selector, ['html', 'body', '*'], true)) {
            return $selector;
        }
        if (preg_match('/^(.*?)([^\s>+~]+)$/s', $selector, $match) !== 1) {
            return $selector;
        }
        $last = $match[2];
        $pseudo = strpos($last, ':');
        $last = $pseudo === false ? $last.'.'.$scope : substr($last, 0, $pseudo).'.'.$scope.substr($last, $pseudo);

        return $match[1].$last;
    }

    /**
     * PAM media features resolve here; other queries stay for the browser.
     *
     * @param array<string, string> $features
     */
    private static function media(string $query, array $features): bool|string|null
    {
        $known = false;
        $result = preg_replace_callback('/\(\s*(device-type|input-mode)\s*:\s*([a-z-]+)\s*\)/i', static function (array $m) use ($features, &$known): string {
            $known = true;

            return ($features[strtolower($m[1])] ?? '') === strtolower($m[2]) ? '__true__' : '__false__';
        }, $query) ?? $query;
        if (!$known) {
            return $query;
        }
        $parts = array_map('trim', preg_split('/\band\b/i', $result) ?: []);
        if (in_array('__false__', $parts, true)) {
            return null;
        }
        $rest = array_values(array_filter($parts, static fn (string $p): bool => $p !== '__true__' && $p !== ''));

        return $rest === [] ? true : implode(' and ', $rest);
    }

    private static function declarations(string $body): string
    {
        $out = [];
        foreach (array_filter(array_map('trim', explode(';', $body))) as $declaration) {
            $colon = strpos($declaration, ':');
            if ($colon === false) {
                continue;
            }
            $property = strtolower(trim(substr($declaration, 0, $colon)));
            $value = trim(substr($declaration, $colon + 1));
            if ($property === 'flex' && preg_match('/^\d+(\.\d+)?$/D', $value) === 1) {
                // React Native `flex: N` (grow N, shrink 1, basis 0).
                $value = "{$value} 1 0%";
            }
            $out[] = "{$property}:{$value}";
        }

        return implode(';', $out);
    }
}
