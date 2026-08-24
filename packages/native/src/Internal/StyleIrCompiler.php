<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use JsonException;
use Pam\Native\Style\StyleInvalidationKind;
use Pam\Native\Style\StylePropertyCatalog;
use RuntimeException;

/** Produces the stable PAM Style IR, bytecode envelope and source map. */
final class StyleIrCompiler
{
    public const VERSION = 1;
    private const MAGIC = "PAMS\x01";

    private function __construct()
    {
    }

    /**
     * @param array<string, mixed> $sheet
     * @return array{
     *   ir: array<string, mixed>,
     *   bytecode: string,
     *   fingerprint: string,
     *   sourceMap: list<array<string, int|string>>,
     *   compatibility: list<array<string, int|string|list<string>|null>>
     * }
     */
    public static function compile(
        array $sheet,
        string $source,
        string $name,
    ): array {
        $sourceMap = self::sourceMap($source, $name);
        $dependencies = self::dependencies($source);
        $ir = [
            'version' => self::VERSION,
            'scope' => $sheet['scope'] ?? 1,
            'scopeId' => $sheet['scopeId'] ?? '',
            'rules' => [
                'classes' => $sheet['classes'] ?? [],
                'tags' => $sheet['tags'] ?? [],
                'cascade' => $sheet['classCascade'] ?? [],
                'selectors' => $sheet['cascadeRules'] ?? [],
            ],
            'tokens' => $sheet['tokens'] ?? [],
            'variables' => $sheet['variables'] ?? [],
            'variableRules' => $sheet['variableRules'] ?? [],
            'states' => $sheet['states'] ?? [],
            'stateRules' => $sheet['stateRules'] ?? [],
            'recipes' => $sheet['recipes'] ?? [],
            'queries' => $sheet['queries'] ?? [],
            'keyframes' => $sheet['keyframes'] ?? [],
            'fonts' => $sheet['fonts'] ?? [],
            'utilities' => StyleUtilityCompiler::manifest(),
            'dependencies' => $dependencies,
        ];
        try {
            $json = json_encode(
                $ir,
                JSON_THROW_ON_ERROR
                    | JSON_UNESCAPED_SLASHES
                    | JSON_PRESERVE_ZERO_FRACTION,
            );
        } catch (JsonException $error) {
            throw new RuntimeException(
                "Cannot encode PAM Style IR for {$name}.",
                previous: $error,
            );
        }
        $binary = self::MAGIC.pack('N', strlen($json)).$json;

        return [
            'ir' => $ir,
            'bytecode' => base64_encode($binary),
            'fingerprint' => hash('sha256', $binary),
            'sourceMap' => $sourceMap,
            'compatibility' => StylePropertyCatalog::manifest(),
        ];
    }

    /** @return array<string, int> */
    private static function dependencies(string $source): array
    {
        $dependencies = [];
        if (str_contains($source, 'var(')) {
            $dependencies['variables'] = StyleInvalidationKind::Value->value;
        }
        if (preg_match('/:(?:pressed|focused|focus-visible|disabled|selected|checked|hover|hovered|state\()/i', $source) === 1) {
            $dependencies['states'] = StyleInvalidationKind::State->value;
        }
        if (str_contains($source, '@container')) {
            $dependencies['container'] = StyleInvalidationKind::Container->value;
        }
        if (str_contains($source, '@media')) {
            $dependencies['viewport'] = StyleInvalidationKind::Viewport->value;
        }
        if (preg_match('/(?:prefers-color-scheme|light-dark\(|dynamic-color\()/i', $source) === 1) {
            $dependencies['theme'] = StyleInvalidationKind::Theme->value;
        }
        if (preg_match('/(?:env\(|android-resource\(|android-attribute\()/i', $source) === 1) {
            $dependencies['environment'] = StyleInvalidationKind::Environment->value;
        }
        ksort($dependencies, SORT_STRING);

        return $dependencies;
    }

    /** @return list<array<string, int|string>> */
    private static function sourceMap(string $source, string $name): array
    {
        $entries = [];
        if (preg_match_all('/(^|[;{])\s*([a-zA-Z-]+)\s*:/m', $source, $matches, PREG_OFFSET_CAPTURE) === false) {
            return [];
        }
        foreach ($matches[2] as [$property, $offset]) {
            $definition = StylePropertyCatalog::find($property);
            if ($definition === null) {
                continue;
            }
            $prefix = substr($source, 0, $offset);
            $line = substr_count($prefix, "\n") + 1;
            $lastNewline = strrpos($prefix, "\n");
            $column = $lastNewline === false ? $offset + 1 : $offset - $lastNewline;
            $entries[] = [
                'propertyId' => $definition->id,
                'source' => $name,
                'line' => $line,
                'column' => $column,
            ];
        }

        return $entries;
    }
}
