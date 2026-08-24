<?php

declare(strict_types=1);

namespace Pam\Native\Style;

/**
 * Stable, generated-documentation source of truth for CSS-to-native support.
 * IDs are public ABI and must remain sequential and append-only.
 */
final class StylePropertyCatalog
{
    /** @var list<StylePropertyDefinition>|null */
    private static ?array $definitions = null;

    private function __construct()
    {
    }

    /** @return list<StylePropertyDefinition> */
    public static function all(): array
    {
        return self::$definitions ??= self::build();
    }

    public static function find(string $cssName): ?StylePropertyDefinition
    {
        $normalized = strtolower(trim($cssName));
        foreach (self::all() as $definition) {
            if (
                $definition->cssName === $normalized
                || in_array($normalized, $definition->aliases, true)
            ) {
                return $definition;
            }
        }

        return null;
    }

    /** @return list<array<string, int|string|list<string>|null>> */
    public static function manifest(): array
    {
        return array_map(
            static fn (StylePropertyDefinition $definition): array =>
                $definition->toArray(),
            self::all(),
        );
    }

    /** @return list<StylePropertyDefinition> */
    private static function build(): array
    {
        $rows = [
            ['align-items', 'alignItems', StyleRenderCost::Layout],
            ['align-self', 'alignSelf', StyleRenderCost::Layout],
            ['aspect-ratio', 'aspectRatio', StyleRenderCost::Layout],
            ['background-color', 'backgroundColor', StyleRenderCost::Paint, ['background']],
            ['border-color', 'borderColor', StyleRenderCost::Paint],
            ['border-radius', 'borderRadius', StyleRenderCost::Paint],
            ['border-style', 'borderStyle', StyleRenderCost::Paint],
            ['border-width', 'borderWidth', StyleRenderCost::Layout],
            ['bottom', 'bottom', StyleRenderCost::Layout],
            ['box-shadow', 'shadow', StyleRenderCost::Paint],
            ['color', 'textColor', StyleRenderCost::Paint],
            ['column-gap', 'gridColumnGap', StyleRenderCost::Layout],
            ['display', 'display', StyleRenderCost::Layout],
            ['elevation', 'elevation', StyleRenderCost::Paint],
            ['flex', 'flex', StyleRenderCost::Layout],
            ['flex-basis', 'flexBasis', StyleRenderCost::Layout],
            ['flex-direction', 'flexDirection', StyleRenderCost::Layout],
            ['flex-grow', 'flexGrow', StyleRenderCost::Layout],
            ['flex-shrink', 'flexShrink', StyleRenderCost::Layout],
            ['flex-wrap', 'flexWrap', StyleRenderCost::Layout],
            ['font-family', 'fontFamily', StyleRenderCost::Layout],
            ['font-size', 'fontSize', StyleRenderCost::Layout],
            ['font-style', 'fontStyle', StyleRenderCost::Layout],
            ['font-weight', 'fontWeight', StyleRenderCost::Layout],
            ['gap', 'gap', StyleRenderCost::Layout],
            ['grid-column', 'gridSpan', StyleRenderCost::Layout],
            ['grid-template-columns', 'gridColumns', StyleRenderCost::Layout],
            ['height', 'height', StyleRenderCost::Layout],
            ['inset', 'inset', StyleRenderCost::Layout],
            ['justify-content', 'justifyContent', StyleRenderCost::Layout],
            ['left', 'left', StyleRenderCost::Layout],
            ['letter-spacing', 'letterSpacing', StyleRenderCost::Layout],
            ['line-height', 'lineHeight', StyleRenderCost::Layout],
            ['margin', 'margin', StyleRenderCost::Layout],
            ['max-height', 'maxHeight', StyleRenderCost::Layout],
            ['max-width', 'maxWidth', StyleRenderCost::Layout],
            ['min-height', 'minHeight', StyleRenderCost::Layout],
            ['min-width', 'minWidth', StyleRenderCost::Layout],
            ['object-fit', 'imageFit', StyleRenderCost::Paint],
            ['opacity', 'opacity', StyleRenderCost::Composite],
            ['overflow', 'overflow', StyleRenderCost::Paint],
            ['padding', 'padding', StyleRenderCost::Layout],
            ['position', 'positionType', StyleRenderCost::Layout],
            ['place-items', 'placeItems', StyleRenderCost::Layout],
            ['right', 'right', StyleRenderCost::Layout],
            ['row-gap', 'gridRowGap', StyleRenderCost::Layout],
            ['text-align', 'textAlign', StyleRenderCost::Layout],
            ['text-decoration', 'textDecoration', StyleRenderCost::Paint],
            ['text-transform', 'textTransform', StyleRenderCost::Layout],
            ['top', 'top', StyleRenderCost::Layout],
            ['transform', 'transform', StyleRenderCost::Composite],
            ['visibility', 'visible', StyleRenderCost::Paint],
            ['width', 'width', StyleRenderCost::Layout],
            ['z-index', 'zIndex', StyleRenderCost::Composite],
        ];
        $definitions = [];
        foreach ($rows as $index => $row) {
            /** @var list<string> $aliases */
            $aliases = $row[3] ?? [];
            $definitions[] = new StylePropertyDefinition(
                id: $index + 1,
                cssName: $row[0],
                nativeName: $row[1],
                compatibility: StyleCompatibility::Native,
                cost: $row[2],
                aliases: $aliases,
            );
        }

        return $definitions;
    }
}
