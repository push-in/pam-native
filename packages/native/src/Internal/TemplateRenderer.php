<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use Pam\Native\Align;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\ImageFit;
use Pam\Native\InputSyncMode;
use Pam\Native\KeyboardAvoidingBehavior;
use Pam\Native\KeyboardType;
use Pam\Native\ModalPresentation;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\StatusBarAppearance;
use Pam\Native\TemplateRegistry;
use Pam\Native\TemplateException;
use Pam\Native\UI\ActivityIndicator;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\CustomView;
use Pam\Native\UI\DrawerLayoutAndroid;
use Pam\Native\UI\FlatList;
use Pam\Native\UI\Image;
use Pam\Native\UI\ImageBackground;
use Pam\Native\UI\Input;
use Pam\Native\UI\InputAccessoryView;
use Pam\Native\UI\KeyboardAvoidingView;
use Pam\Native\UI\Modal;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\RefreshControl;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\SectionList;
use Pam\Native\UI\Spacer;
use Pam\Native\UI\StatusBar;
use Pam\Native\UI\Text;
use Pam\Native\UI\Toggle;
use Pam\Native\UI\View as NativeView;
use ReflectionMethod;
use ReflectionProperty;
use RuntimeException;
use Stringable;
use Traversable;

final class TemplateRenderer
{
    /** @var array<string, ReflectionMethod> */
    private static array $methods = [];

    /** @var array<string, ReflectionProperty> */
    private static array $properties = [];

    /** @var array<string, PropKey> */
    private const PROPERTIES = [
        'width' => PropKey::Width,
        'height' => PropKey::Height,
        'flexGrow' => PropKey::FlexGrow,
        'padding' => PropKey::Padding,
        'paddingHorizontal' => PropKey::PaddingHorizontal,
        'paddingVertical' => PropKey::PaddingVertical,
        'gap' => PropKey::Gap,
        'margin' => PropKey::Margin,
        'marginHorizontal' => PropKey::MarginHorizontal,
        'marginVertical' => PropKey::MarginVertical,
        'minWidth' => PropKey::MinWidth,
        'minHeight' => PropKey::MinHeight,
        'maxWidth' => PropKey::MaxWidth,
        'maxHeight' => PropKey::MaxHeight,
        'backgroundColor' => PropKey::BackgroundColor,
        'textColor' => PropKey::TextColor,
        'fontSize' => PropKey::FontSize,
        'borderRadius' => PropKey::BorderRadius,
        'borderWidth' => PropKey::BorderWidth,
        'borderColor' => PropKey::BorderColor,
        'opacity' => PropKey::Opacity,
        'alignItems' => PropKey::AlignItems,
        'alignSelf' => PropKey::AlignSelf,
        'justifyContent' => PropKey::JustifyContent,
        'textAlign' => PropKey::TextAlign,
        'fontWeight' => PropKey::FontWeight,
        'numberOfLines' => PropKey::NumberOfLines,
        'multiline' => PropKey::Multiline,
        'secure' => PropKey::Secure,
        'keyboardType' => PropKey::KeyboardType,
        'autoComplete' => PropKey::AutoComplete,
        'debounce' => PropKey::InputDebounceMs,
        'sync' => PropKey::InputSyncMode,
        'checked' => PropKey::Checked,
        'loading' => PropKey::Loading,
        'progressColor' => PropKey::ProgressColor,
        'fit' => PropKey::ImageFit,
        'tintColor' => PropKey::TintColor,
        'elevation' => PropKey::Elevation,
        'visible' => PropKey::Visible,
        'presentation' => PropKey::ModalPresentation,
        'statusBarColor' => PropKey::StatusBarColor,
        'statusBarStyle' => PropKey::StatusBarStyle,
        'statusBarHidden' => PropKey::StatusBarHidden,
        'keyboardBehavior' => PropKey::KeyboardBehavior,
        'refreshing' => PropKey::Refreshing,
        'scrollEnabled' => PropKey::ScrollEnabled,
        'showsScrollIndicator' => PropKey::ShowsScrollIndicator,
        'selected' => PropKey::Selected,
        'rippleColor' => PropKey::RippleColor,
        'pressedOpacity' => PropKey::PressOpacity,
        'collapsable' => PropKey::Collapsable,
        'accessibilityRole' => PropKey::AccessibilityRole,
        'accessibilityHint' => PropKey::AccessibilityHint,
        'translationX' => PropKey::TranslationX,
        'translationY' => PropKey::TranslationY,
        'scaleX' => PropKey::ScaleX,
        'scaleY' => PropKey::ScaleY,
        'rotation' => PropKey::Rotation,
        'animationDuration' => PropKey::AnimationDurationMs,
        'animationEasing' => PropKey::AnimationEasing,
        'animate' => PropKey::AnimateChanges,
        'rowHeight' => PropKey::ListRowHeight,
        'prefetch' => PropKey::ListPrefetch,
        'endReachedThreshold' => PropKey::EndReachedThreshold,
        'drawerOpen' => PropKey::DrawerOpen,
        'drawerPosition' => PropKey::DrawerPosition,
        'letterSpacing' => PropKey::LetterSpacing,
        'lineHeight' => PropKey::LineHeight,
        'placeholderColor' => PropKey::PlaceholderColor,
        'selectionColor' => PropKey::SelectionColor,
        'maxLength' => PropKey::MaxLength,
        'autoFocus' => PropKey::AutoFocus,
        'returnKeyType' => PropKey::ReturnKeyType,
        'hitSlop' => PropKey::HitSlop,
        'zIndex' => PropKey::ZIndex,
        'overflow' => PropKey::Overflow,
    ];

    /** @var array<string, EventKind> */
    private const EVENTS = [
        'on:press' => EventKind::Press,
        'on:longPress' => EventKind::LongPress,
        'on:change' => EventKind::Change,
        'on:focus' => EventKind::Focus,
        'on:blur' => EventKind::Blur,
        'on:submit' => EventKind::Submit,
        'on:scroll' => EventKind::Scroll,
        'on:refresh' => EventKind::Refresh,
        'on:toggle' => EventKind::Toggle,
        'on:endReached' => EventKind::EndReached,
        'on:drawerOpen' => EventKind::DrawerOpen,
        'on:drawerClose' => EventKind::DrawerClose,
        'on:event' => EventKind::Native,
    ];

    private function __construct()
    {
    }

    /**
     * @param array<string, mixed> $data
     */
    public static function render(
        CompiledTemplateNode $tree,
        ?object $scope,
        array $data,
    ): Element
    {
        $rendered = self::nodes($tree->children, $scope, $data);
        $elements = array_values(array_filter($rendered, static fn (mixed $value): bool => $value instanceof Element));

        if (count($rendered) !== 1 || count($elements) !== 1) {
            throw new RuntimeException('A Pam Native view must render exactly one root element.');
        }

        return $elements[0];
    }

    /**
     * @param list<CompiledTemplateNode> $nodes
     * @param array<string, mixed> $data
     * @return list<Element|string>
     */
    private static function nodes(array $nodes, ?object $scope, array $data): array
    {
        $output = [];

        foreach ($nodes as $node) {
            if ($node->kind === 2) {
                $output[] = self::interpolate($node->value, $scope, $data);
                continue;
            }

            $tag = $node->name;
            $attributes = $node->attributes;
            $children = $node->children;

            if ($tag === 'If') {
                $condition = self::value($attributes['condition'] ?? false, $scope, $data);

                if ((bool) $condition) {
                    array_push($output, ...self::nodes($children, $scope, $data));
                }

                continue;
            }

            if ($tag === 'Each') {
                $items = self::value($attributes['items'] ?? [], $scope, $data);
                $name = (string) ($attributes['as'] ?? 'item');

                if (preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/', $name) !== 1) {
                    throw new RuntimeException('Each "as" value must be a safe variable name.');
                }

                if ($items instanceof Traversable) {
                    $items = iterator_to_array($items);
                }

                if (!is_array($items)) {
                    throw new RuntimeException('Each items must resolve to an array or Traversable.');
                }

                foreach ($items as $index => $item) {
                    array_push(
                        $output,
                        ...self::nodes($children, $scope, [
                            ...$data,
                            $name => $item,
                            $name.'Index' => $index,
                        ]),
                    );
                }

                continue;
            }

            if ($tag === 'Slot') {
                $name = (string) ($attributes['name'] ?? 'slot');
                $slot = $data[$name] ?? [];
                if ($slot instanceof Renderable) {
                    $slot = [$slot];
                }
                if (!is_array($slot)) {
                    throw new RuntimeException("Slot {$name} must resolve to renderable content.");
                }
                if ($slot === [] && $children !== []) {
                    array_push($output, ...self::nodes($children, $scope, $data));
                    continue;
                }
                foreach ($slot as $content) {
                    if (!$content instanceof Renderable) {
                        throw new RuntimeException("Slot {$name} contains non-renderable content.");
                    }
                    $output[] = $content->toElement();
                }
                continue;
            }

            try {
                $output[] = self::tag($tag, $attributes, $children, $scope, $data);
            } catch (TemplateException $error) {
                throw $error;
            } catch (RuntimeException $error) {
                throw new TemplateException(
                    $error->getMessage(),
                    $node->source,
                    $node->line,
                    $node->column,
                );
            }
        }

        return $output;
    }

    /**
     * @param array<string, string|bool> $attributes
     * @param list<CompiledTemplateNode> $childNodes
     * @param array<string, mixed> $data
     */
    private static function tag(
        string $tag,
        array $attributes,
        array $childNodes,
        ?object $scope,
        array $data,
    ): Element {
        $renderedChildren = self::nodes($childNodes, $scope, $data);
        $children = array_values(array_filter(
            $renderedChildren,
            static fn (mixed $value): bool => $value instanceof Element,
        ));
        $text = implode('', array_filter(
            $renderedChildren,
            static fn (mixed $value): bool => is_string($value),
        ));

        if ($text !== '' && !in_array($tag, ['Text', 'Button'], true)) {
            throw new RuntimeException("Text content is not valid inside {$tag}; wrap it in Text.");
        }

        if ($children !== [] && in_array($tag, ['Text', 'Button'], true)) {
            throw new RuntimeException("{$tag} cannot contain element children.");
        }
        $values = [];

        foreach ($attributes as $name => $raw) {
            if (isset(self::EVENTS[$name]) || $name === 'class') {
                continue;
            }

            $values[ltrim($name, ':')] = self::value($raw, $scope, $data);
        }

        $element = match ($tag) {
            'Screen' => Screen::make(...$children),
            'Column' => Column::make(...$children),
            'Row' => Row::make(...$children),
            'View' => NativeView::make(...$children),
            'Text' => Text::make(self::stringValue($values['text'] ?? $text, 'Text content')),
            'Button' => Button::make(self::stringValue(
                $values['label'] ?? $values['text'] ?? $text,
                'Button label',
            )),
            'Input', 'TextInput' => Input::make(self::stringValue(
                $values['value'] ?? self::modelValue($values, $scope),
                'Input value',
            )),
            'Image' => Image::make(self::stringValue($values['source'] ?? '', 'Image source')),
            'ImageBackground' => ImageBackground::make(
                self::stringValue($values['source'] ?? '', 'Image source'),
                ...$children,
            ),
            'Scroll', 'ScrollView' => Scroll::make(self::singleChild($children, $tag)),
            'FlatList', 'NativeList', 'VirtualizedList' => FlatList::make(
                self::stringItems($values['items'] ?? []),
            ),
            'SectionList' => SectionList::make(self::sections($values['sections'] ?? [])),
            'Spacer' => Spacer::make(self::floatValue($values['size'] ?? 8.0, 'Spacer size')),
            'Pressable', 'TouchableOpacity', 'TouchableHighlight',
            'TouchableWithoutFeedback', 'TouchableNativeFeedback' => Pressable::make(...$children),
            'ActivityIndicator' => ActivityIndicator::make(
                self::boolValue($values['visible'] ?? true, 'ActivityIndicator visible'),
            ),
            'Switch' => Toggle::make(self::boolValue($values['checked'] ?? false, 'Switch checked')),
            'Modal' => Modal::make(
                self::singleChild($children, $tag),
                self::boolValue($values['visible'] ?? true, 'Modal visible'),
                self::modalPresentation($values['presentation'] ?? 'dialog'),
            ),
            'KeyboardAvoidingView' => KeyboardAvoidingView::make(
                self::singleChild($children, $tag),
                self::keyboardBehavior($values['behavior'] ?? $values['keyboardBehavior'] ?? 'resize'),
            ),
            'RefreshControl' => RefreshControl::make(
                self::singleChild($children, $tag),
                self::boolValue($values['refreshing'] ?? false, 'RefreshControl refreshing'),
            ),
            'StatusBar' => StatusBar::make(
                isset($values['color']) ? self::intValue($values['color'], 'StatusBar color') : null,
                self::statusBarAppearance($values['appearance'] ?? 'dark'),
                self::boolValue($values['hidden'] ?? false, 'StatusBar hidden'),
            ),
            'SafeAreaView' => SafeAreaView::make(...$children),
            'DrawerLayoutAndroid' => DrawerLayoutAndroid::make(
                self::childAt($children, 0, $tag),
                self::childAt($children, 1, $tag),
            ),
            'InputAccessoryView' => InputAccessoryView::make(...$children),
            'Native', 'CustomView' => CustomView::make(
                self::stringValue($values['name'] ?? '', 'Native view name'),
                self::scalarMap($values['properties'] ?? []),
            ),
            default => self::custom($tag, $values, $children, $scope),
        };

        $class = $attributes['class'] ?? null;

        if (is_string($class)) {
            $element = self::classes($element, self::interpolate($class, $scope, $data));
        }

        $element = self::attributes($element, $values);

        foreach (self::EVENTS as $name => $event) {
            if (isset($attributes[$name])) {
                $element = $element->on(
                    $event,
                    self::handler($attributes[$name], $event, $scope, $data),
                );
            }
        }

        if (isset($values['model'])) {
            if (!$element instanceof Input) {
                throw new RuntimeException('The model attribute is only valid on Input.');
            }

            $element = $element->onChange(self::modelHandler(
                self::stringValue($values['model'], 'Input model'),
                $scope,
            ));
        }

        return $element;
    }

    /**
     * @param array<string, mixed> $attributes
     */
    private static function attributes(Element $element, array $attributes): Element
    {
        if (isset($attributes['key'])) {
            $element = $element->key(self::stringValue($attributes['key'], 'Element key'));
        }

        if (isset($attributes['accessibilityLabel'])) {
            $element = $element->accessibilityLabel(self::stringValue(
                $attributes['accessibilityLabel'],
                'Accessibility label',
            ));
        }

        if (isset($attributes['testId'])) {
            $element = $element->testId(self::stringValue($attributes['testId'], 'Test ID'));
        }

        if (isset($attributes['enabled'])) {
            $element = $element->enabled(self::boolValue($attributes['enabled'], 'Enabled'));
        }

        if (isset($attributes['placeholder']) && $element instanceof Input) {
            $element = $element->placeholder(self::stringValue(
                $attributes['placeholder'],
                'Input placeholder',
            ));
        }

        foreach (self::PROPERTIES as $name => $key) {
            if (!array_key_exists($name, $attributes)) {
                continue;
            }

            $value = self::propertyValue($key, $attributes[$name]);

            if ($value !== null) {
                $element = $element->property($key, $value);
            }
        }

        return $element;
    }

    private static function propertyValue(PropKey $key, mixed $value): string|int|float|bool|null
    {
        return match ($key) {
            PropKey::AlignItems, PropKey::AlignSelf => self::named($value, [
                'start' => 1, 'center' => 2, 'end' => 3, 'stretch' => 4,
            ]),
            PropKey::JustifyContent => self::named($value, [
                'start' => 1, 'center' => 2, 'end' => 3, 'space-between' => 4,
                'space-around' => 5, 'space-evenly' => 6,
            ]),
            PropKey::TextAlign => self::named($value, ['start' => 1, 'center' => 2, 'end' => 3]),
            PropKey::KeyboardType => self::named($value, [
                'text' => KeyboardType::Text->value,
                'email' => KeyboardType::Email->value,
                'number' => KeyboardType::Number->value,
                'phone' => KeyboardType::Phone->value,
                'decimal' => KeyboardType::Decimal->value,
                'url' => KeyboardType::Url->value,
            ]),
            PropKey::InputSyncMode => self::named($value, [
                'native' => InputSyncMode::Native->value,
                'debounced' => InputSyncMode::Debounced->value,
                'immediate' => InputSyncMode::Immediate->value,
                'blur' => InputSyncMode::OnBlur->value,
                'submit' => InputSyncMode::OnSubmit->value,
            ]),
            PropKey::ImageFit => self::named($value, [
                'cover' => ImageFit::Cover->value,
                'contain' => ImageFit::Contain->value,
                'fill' => ImageFit::Fill->value,
                'center' => ImageFit::Center->value,
            ]),
            PropKey::ModalPresentation => self::named($value, [
                'fullScreen' => 1, 'dialog' => 2, 'sheet' => 3,
            ]),
            PropKey::StatusBarStyle => self::named($value, ['dark' => 1, 'light' => 2]),
            PropKey::KeyboardBehavior => self::named($value, [
                'resize' => 1, 'pan' => 2, 'padding' => 3,
            ]),
            PropKey::Overflow => self::named($value, ['visible' => 1, 'hidden' => 2]),
            default => is_string($value) || is_int($value) || is_float($value) || is_bool($value)
                ? $value
                : null,
        };
    }

    /** @param array<string, int> $values */
    private static function named(mixed $value, array $values): int
    {
        if (is_int($value)) {
            return $value;
        }

        $name = self::stringValue($value, 'Template option');

        if (!isset($values[$name])) {
            throw new InvalidArgumentException("Unknown template option {$name}.");
        }

        return $values[$name];
    }

    private static function classes(Element $element, string $classes): Element
    {
        foreach (preg_split('/\\s+/', trim($classes)) ?: [] as $class) {
            if ($class === '') {
                continue;
            }

            $registered = TemplateRegistry::classProperties($class);

            if ($registered !== null) {
                foreach ($registered as $key => $value) {
                    $element = $element->property(PropKey::from($key), $value);
                }

                continue;
            }

            $utility = self::utility($class);

            if ($utility === null) {
                throw new RuntimeException("Unknown Pam Native class {$class}.");
            }

            [$key, $value] = $utility;
            $element = $element->property($key, $value);
        }

        return $element;
    }

    /** @return array{PropKey, int|float}|null */
    private static function utility(string $class): ?array
    {
        $fixed = [
            'flex-1' => [PropKey::FlexGrow, 1.0],
            'w-full' => [PropKey::Width, 10_000.0],
            'bg-white' => [PropKey::BackgroundColor, 0xFFFFFFFF],
            'bg-black' => [PropKey::BackgroundColor, 0xFF000000],
            'text-white' => [PropKey::TextColor, 0xFFFFFFFF],
            'text-black' => [PropKey::TextColor, 0xFF000000],
            'items-start' => [PropKey::AlignItems, Align::Start->value],
            'items-center' => [PropKey::AlignItems, Align::Center->value],
            'items-end' => [PropKey::AlignItems, Align::End->value],
            'items-stretch' => [PropKey::AlignItems, Align::Stretch->value],
        ];

        if (isset($fixed[$class])) {
            return $fixed[$class];
        }

        if (preg_match('/^(p|px|py|m|mx|my|gap|rounded|elevation)-(\\d+(?:\\.\\d+)?)$/', $class, $match) === 1) {
            $key = match ($match[1]) {
                'p' => PropKey::Padding,
                'px' => PropKey::PaddingHorizontal,
                'py' => PropKey::PaddingVertical,
                'm' => PropKey::Margin,
                'mx' => PropKey::MarginHorizontal,
                'my' => PropKey::MarginVertical,
                'gap' => PropKey::Gap,
                'rounded' => PropKey::BorderRadius,
                'elevation' => PropKey::Elevation,
            };

            return [$key, (float) $match[2] * 4.0];
        }

        if (preg_match('/^opacity-(\\d{1,3})$/', $class, $match) === 1) {
            return [PropKey::Opacity, min(100, (int) $match[1]) / 100];
        }

        return null;
    }

    /**
     * @param array<string, mixed> $values
     * @param list<Element> $children
     */
    private static function custom(
        string $tag,
        array $values,
        array $children,
        ?object $scope,
    ): Element {
        $factory = TemplateRegistry::factory($tag);

        if ($factory === null) {
            throw new RuntimeException("Unknown Pam Native tag {$tag}.");
        }

        $result = $factory($values, $children, $scope);

        if ($result instanceof \Pam\Native\View && $scope !== null) {
            $result = $result->withScope($scope);
        }

        return $result->toElement();
    }

    /**
     * @param array<string, mixed> $data
     */
    private static function handler(
        string|bool $raw,
        EventKind $kind,
        ?object $scope,
        array $data,
    ): Closure {
        $resolved = self::value($raw, $scope, $data);

        if ($resolved instanceof Closure) {
            return $resolved;
        }

        if ($scope === null || !is_string($resolved) || !method_exists($scope, $resolved)) {
            throw new RuntimeException(
                'Template event handler '.get_debug_type($resolved).' does not exist.',
            );
        }

        $method = self::$methods[$scope::class.'::'.$resolved]
            ??= new ReflectionMethod($scope, $resolved);

        if (!$method->isPublic()) {
            throw new RuntimeException("Template event handler {$resolved} must be public.");
        }

        return static function (string $payload = '') use ($kind, $method, $scope): void {
            if ($method->getNumberOfParameters() === 0) {
                $method->invoke($scope);
                return;
            }

            $value = $kind === EventKind::Toggle ? $payload === '1' : $payload;
            $method->invoke($scope, $value);
        };
    }

    private static function modelHandler(string $model, ?object $scope): Closure
    {
        $property = ltrim($model, '$');

        if (
            $scope === null
            || preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/', $property) !== 1
            || !property_exists($scope, $property)
        ) {
            throw new RuntimeException("Model {$model} is not a component property.");
        }

        $reflection = self::reflectionProperty($scope, $property);

        return static function (string $value) use ($reflection, $scope): void {
            $reflection->setValue($scope, $value);
        };
    }

    /** @param array<string, mixed> $values */
    private static function modelValue(array $values, ?object $scope): string
    {
        if (!isset($values['model'])) {
            return '';
        }

        $property = ltrim(self::stringValue($values['model'], 'Input model'), '$');

        return self::stringValue(
            self::path('$'.$property, $scope, []),
            'Input model value',
        );
    }

    /**
     * @param array<string, mixed> $data
     */
    private static function value(mixed $raw, ?object $scope, array $data): mixed
    {
        if (!is_string($raw)) {
            return $raw;
        }

        if (preg_match('/^\\$[A-Za-z_][A-Za-z0-9_]*(?:(?:\\.|->)[A-Za-z_][A-Za-z0-9_]*)*$/', $raw) === 1) {
            return self::path($raw, $scope, $data);
        }

        return match ($raw) {
            'true' => true,
            'false' => false,
            'null' => null,
            default => self::literal($raw, $scope, $data),
        };
    }

    /** @param array<string, mixed> $data */
    private static function literal(string $raw, ?object $scope, array $data): string|int|float
    {
        if (preg_match('/^-?\\d+$/', $raw) === 1) {
            return (int) $raw;
        }

        if (preg_match('/^-?\\d+\\.\\d+$/', $raw) === 1) {
            return (float) $raw;
        }

        if (preg_match('/^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$/', $raw, $color) === 1) {
            $hex = strlen($color[1]) === 6 ? 'FF'.$color[1] : $color[1];

            return (int) hexdec($hex);
        }

        return self::interpolate($raw, $scope, $data);
    }

    /** @param array<string, mixed> $data */
    private static function interpolate(string $value, ?object $scope, array $data): string
    {
        return preg_replace_callback(
            '/\\{\\{\\s*(\\$[A-Za-z_][A-Za-z0-9_]*(?:(?:\\.|->)[A-Za-z_][A-Za-z0-9_]*)*)\\s*\\}\\}/',
            static function (array $match) use ($scope, $data): string {
                $resolved = self::path($match[1], $scope, $data);

                if (
                    !is_string($resolved)
                    && !is_int($resolved)
                    && !is_float($resolved)
                    && !is_bool($resolved)
                    && !$resolved instanceof Stringable
                ) {
                    throw new RuntimeException("Template expression {$match[1]} is not printable.");
                }

                return (string) $resolved;
            },
            $value,
        ) ?? $value;
    }

    /** @param array<string, mixed> $data */
    private static function path(string $path, ?object $scope, array $data): mixed
    {
        $segments = preg_split('/\\.|->/', ltrim($path, '$')) ?: [];
        $first = array_shift($segments);

        if ($first === null) {
            throw new RuntimeException('Template expression is empty.');
        }

        if (array_key_exists($first, $data)) {
            $value = $data[$first];
        } elseif ($scope !== null && property_exists($scope, $first)) {
            $value = self::reflectionProperty($scope, $first)->getValue($scope);
        } else {
            throw new RuntimeException("Template expression {$path} is undefined.");
        }

        foreach ($segments as $segment) {
            $value = match (true) {
                is_array($value) && array_key_exists($segment, $value) => $value[$segment],
                is_object($value) && property_exists($value, $segment) => $value->{$segment},
                default => throw new RuntimeException("Cannot resolve template expression {$path}."),
            };
        }

        return $value;
    }

    private static function reflectionProperty(object $scope, string $property): ReflectionProperty
    {
        return self::$properties[$scope::class.'::$'.$property]
            ??= new ReflectionProperty($scope, $property);
    }

    /** @param list<Element> $children */
    private static function singleChild(array $children, string $tag): Element
    {
        if (count($children) !== 1) {
            throw new RuntimeException("{$tag} requires exactly one child.");
        }

        return $children[0];
    }

    /** @param list<Element> $children */
    private static function childAt(array $children, int $index, string $tag): Element
    {
        return $children[$index]
            ?? throw new RuntimeException("{$tag} requires two children.");
    }

    /** @return list<string> */
    private static function stringItems(mixed $items): array
    {
        if ($items instanceof Traversable) {
            $items = iterator_to_array($items, false);
        }

        if (!is_array($items)) {
            throw new RuntimeException('List items must resolve to an array.');
        }

        return array_map(
            static fn (mixed $item): string => self::stringValue($item, 'List item'),
            array_values($items),
        );
    }

    /** @return array<string, list<string>> */
    private static function sections(mixed $sections): array
    {
        if (!is_array($sections)) {
            throw new RuntimeException('SectionList sections must resolve to an array.');
        }

        $result = [];

        foreach ($sections as $title => $items) {
            $result[(string) $title] = self::stringItems($items);
        }

        return $result;
    }

    private static function modalPresentation(mixed $value): ModalPresentation
    {
        return match ($value) {
            1, 'fullScreen' => ModalPresentation::FullScreen,
            3, 'sheet' => ModalPresentation::Sheet,
            default => ModalPresentation::Dialog,
        };
    }

    private static function keyboardBehavior(mixed $value): KeyboardAvoidingBehavior
    {
        return match ($value) {
            2, 'pan' => KeyboardAvoidingBehavior::Pan,
            3, 'padding' => KeyboardAvoidingBehavior::Padding,
            default => KeyboardAvoidingBehavior::Resize,
        };
    }

    private static function statusBarAppearance(mixed $value): StatusBarAppearance
    {
        return match ($value) {
            2, 'light' => StatusBarAppearance::Light,
            default => StatusBarAppearance::Dark,
        };
    }

    private static function stringValue(mixed $value, string $label): string
    {
        if (
            is_string($value)
            || is_int($value)
            || is_float($value)
            || is_bool($value)
            || $value instanceof Stringable
        ) {
            return (string) $value;
        }

        throw new RuntimeException("{$label} must be printable.");
    }

    private static function floatValue(mixed $value, string $label): float
    {
        if (is_int($value) || is_float($value)) {
            return (float) $value;
        }
        if (is_string($value) && is_numeric($value)) {
            return (float) $value;
        }

        throw new RuntimeException("{$label} must be numeric.");
    }

    private static function intValue(mixed $value, string $label): int
    {
        if (is_int($value)) {
            return $value;
        }
        if (is_string($value) && preg_match('/^-?\\d+$/', $value) === 1) {
            return (int) $value;
        }

        throw new RuntimeException("{$label} must be an integer.");
    }

    private static function boolValue(mixed $value, string $label): bool
    {
        if (is_bool($value)) {
            return $value;
        }
        if ($value === 1 || $value === '1' || $value === 'true') {
            return true;
        }
        if ($value === 0 || $value === '0' || $value === 'false') {
            return false;
        }

        throw new RuntimeException("{$label} must be a boolean.");
    }

    /** @return array<string, string|int|float|bool> */
    private static function scalarMap(mixed $value): array
    {
        if (!is_array($value)) {
            throw new RuntimeException('Native view properties must resolve to a map.');
        }
        $properties = [];

        foreach ($value as $key => $property) {
            if (
                !is_string($key)
                || (!is_string($property)
                    && !is_int($property)
                    && !is_float($property)
                    && !is_bool($property))
            ) {
                throw new RuntimeException('Native view properties must be named scalars.');
            }

            $properties[$key] = $property;
        }

        return $properties;
    }
}
