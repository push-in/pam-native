<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;

/** Generates typed, dependency-free token APIs for every PAM Native language. */
final class StyleTokenGenerator
{
    private function __construct()
    {
    }

    /** @param array<string,string> $tokens @return array{php:string,kotlin:string,swift:string} */
    public static function generate(array $tokens, string $namespace = 'App\\Style', string $type = 'Tokens'): array
    {
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/D', $type) !== 1) {
            throw new RuntimeException('Invalid generated token type name.');
        }
        $php = ["<?php", '', 'declare(strict_types=1);', '', "namespace {$namespace};", '', "final class {$type}", '{'];
        $kotlin = ["package dev.pam.generated", '', "public object {$type} {"];
        $swift = ["public enum {$type} {"];
        foreach ($tokens as $name => $value) {
            $identifier = self::identifier($name);
            $literal = var_export($value, true);
            $php[] = "    public const string {$identifier} = {$literal};";
            $escaped = addcslashes($value, "\\\"");
            $kotlin[] = "    public const val {$identifier}: String = \"{$escaped}\"";
            $swift[] = "    public static let {$identifier}: String = \"{$escaped}\"";
        }
        $php[] = '}';
        $kotlin[] = '}';
        $swift[] = '}';
        return ['php' => implode("\n", $php)."\n", 'kotlin' => implode("\n", $kotlin)."\n", 'swift' => implode("\n", $swift)."\n"];
    }

    private static function identifier(string $name): string
    {
        $identifier = strtoupper((string) preg_replace('/[^A-Za-z0-9]+/', '_', trim($name, '-')));
        if ($identifier === '' || ctype_digit($identifier[0])) {
            $identifier = 'TOKEN_'.$identifier;
        }
        return $identifier;
    }
}
