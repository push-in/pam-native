<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Align;
use Pam\Native\AccessibilityRole;
use Pam\Native\AccessibilityCheckedState;
use Pam\Native\AccessibilityImportance;
use Pam\Native\AccessibilityLiveRegion;
use Pam\Native\ActivityIndicatorSize;
use Pam\Native\AnimationKind;
use Pam\Native\AnimationEasing;
use Pam\Native\AnimationFillMode;
use Pam\Native\AnimationKeyframe;
use Pam\Native\AnimationPlayState;
use Pam\Native\BottomSheetKeyboardBehavior;
use Pam\Native\Component;
use Pam\Native\DrawingMode;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\FlexWrap;
use Pam\Native\GestureComposition;
use Pam\Native\GestureDirection;
use Pam\Native\GestureEvent;
use Pam\Native\GestureType;
use Pam\Native\ImageFit;
use Pam\Native\ImageCachePolicy;
use Pam\Native\ImageResizeMethod;
use Pam\Native\InputAutoCapitalize;
use Pam\Native\InputAutofillImportance;
use Pam\Native\InputMode;
use Pam\Native\InputSubmitBehavior;
use Pam\Native\InputSyncMode;
use Pam\Native\InputTextAlignVertical;
use Pam\Native\KeyboardAvoidingBehavior;
use Pam\Native\KeyboardType;
use Pam\Native\LayoutDirection;
use Pam\Native\ModalAnimationType;
use Pam\Native\ModalPresentation;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\RefreshIndicatorSize;
use Pam\Native\Renderable;
use Pam\Native\ReturnKeyType;
use Pam\Native\SafeAreaMode;
use Pam\Native\ScrollKeyboardDismissMode;
use Pam\Native\ScrollOverScrollMode;
use Pam\Native\StatusBarAppearance;
use Pam\Native\TemplateRegistry;
use Pam\Native\TemplateException;
use Pam\Native\TextBreakStrategy;
use Pam\Native\TextDataDetectorType;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextHyphenationFrequency;
use Pam\Native\UI\ActivityIndicator;
use Pam\Native\UI\Animated;
use Pam\Native\UI\Button;
use Pam\Native\UI\BottomSheet;
use Pam\Native\UI\Column;
use Pam\Native\UI\CustomView;
use Pam\Native\UI\DrawerLayoutAndroid;
use Pam\Native\UI\DrawingCanvas;
use Pam\Native\UI\FlatList;
use Pam\Native\UI\Grid;
use Pam\Native\UI\GestureDetector;
use Pam\Native\UI\Image;
use Pam\Native\UI\ImageBackground;
use Pam\Native\UI\Input;
use Pam\Native\UI\InteractionRegion;
use Pam\Native\UI\InputAccessoryView;
use Pam\Native\UI\KeyboardAvoidingView;
use Pam\Native\UI\Modal;
use Pam\Native\UI\MediaPlayer;
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
use Pam\Native\UI\VirtualGrid;
use Pam\Native\UI\VirtualizedList;
use Pam\Native\UI\WebView;
use Pam\Native\NativeMenuItem;
use Pam\Native\MediaCachePolicy;
use Pam\Native\MediaPriority;
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
        'secureTextEntry' => PropKey::Secure,
        'keyboardType' => PropKey::KeyboardType,
        'autoComplete' => PropKey::AutoComplete,
        'editable' => PropKey::InputEditable,
        'autoCorrect' => PropKey::InputAutoCorrect,
        'autoCapitalize' => PropKey::InputAutoCapitalize,
        'caretHidden' => PropKey::InputCaretHidden,
        'contextMenuHidden' => PropKey::InputContextMenuHidden,
        'cursorColor' => PropKey::InputCursorColor,
        'disableFullscreenUI' => PropKey::InputDisableFullscreenUi,
        'importantForAutofill' => PropKey::InputAutofillImportance,
        'inputMode' => PropKey::InputMode,
        'minLines' => PropKey::InputMinLines,
        'rows' => PropKey::InputMinLines,
        'selectTextOnFocus' => PropKey::InputSelectTextOnFocus,
        'selectionStart' => PropKey::InputSelectionStart,
        'selectionEnd' => PropKey::InputSelectionEnd,
        'showSoftInputOnFocus' => PropKey::InputShowSoftInputOnFocus,
        'submitBehavior' => PropKey::InputSubmitBehavior,
        'textAlignVertical' => PropKey::InputTextAlignVertical,
        'returnKeyLabel' => PropKey::InputReturnKeyLabel,
        'underlineColorAndroid' => PropKey::InputUnderlineColor,
        'debounce' => PropKey::InputDebounceMs,
        'sync' => PropKey::InputSyncMode,
        'checked' => PropKey::Checked,
        'loading' => PropKey::Loading,
        'progressColor' => PropKey::ProgressColor,
        'fit' => PropKey::ImageFit,
        'resizeMode' => PropKey::ImageFit,
        'tintColor' => PropKey::TintColor,
        'defaultSource' => PropKey::ImageDefaultSource,
        'loadingIndicatorSource' => PropKey::ImageLoadingIndicatorSource,
        'fadeDuration' => PropKey::ImageFadeDurationMs,
        'resizeMethod' => PropKey::ImageResizeMethod,
        'resizeMultiplier' => PropKey::ImageResizeMultiplier,
        'progressiveRenderingEnabled' =>
            PropKey::ImageProgressiveRenderingEnabled,
        'cache' => PropKey::ImageCachePolicy,
        'cachePolicy' => PropKey::ImageCachePolicy,
        'overlayColor' => PropKey::ImageOverlayColor,
        'srcSet' => PropKey::ImageSourceSet,
        'elevation' => PropKey::Elevation,
        'visible' => PropKey::Visible,
        'presentation' => PropKey::ModalPresentation,
        'animationType' => PropKey::ModalAnimationType,
        'backdropColor' => PropKey::ModalBackdropColor,
        'transparent' => PropKey::ModalTransparent,
        'hardwareAccelerated' => PropKey::ModalHardwareAccelerated,
        'navigationBarTranslucent' => PropKey::ModalNavigationBarTranslucent,
        'allowSwipeDismissal' => PropKey::ModalAllowSwipeDismissal,
        'javaScriptEnabled' => PropKey::WebViewJavaScriptEnabled,
        'domStorageEnabled' => PropKey::WebViewDomStorageEnabled,
        'userAgent' => PropKey::WebViewUserAgent,
        'injectedJavaScript' => PropKey::WebViewInjectedJavaScript,
        'allowsInlineMedia' => PropKey::WebViewAllowsInlineMedia,
        'allowedHosts' => PropKey::WebViewAllowedHosts,
        'autoPlay' => PropKey::MediaAutoPlay,
        'controls' => PropKey::MediaControls,
        'loop' => PropKey::MediaLoop,
        'muted' => PropKey::MediaMuted,
        'volume' => PropKey::MediaVolume,
        'currentTime' => PropKey::MediaCurrentTime,
        'playbackRate' => PropKey::MediaPlaybackRate,
        'draggable' => PropKey::Draggable,
        'dragData' => PropKey::DragData,
        'dropEnabled' => PropKey::DropEnabled,
        'statusBarColor' => PropKey::StatusBarColor,
        'statusBarStyle' => PropKey::StatusBarStyle,
        'statusBarHidden' => PropKey::StatusBarHidden,
        'statusBarAnimated' => PropKey::StatusBarAnimated,
        'statusBarTranslucent' => PropKey::StatusBarTranslucent,
        'keyboardBehavior' => PropKey::KeyboardBehavior,
        'refreshing' => PropKey::Refreshing,
        'refreshColors' => PropKey::RefreshColors,
        'progressBackgroundColor' => PropKey::RefreshProgressBackgroundColor,
        'progressViewOffset' => PropKey::RefreshProgressViewOffset,
        'refreshIndicatorSize' => PropKey::RefreshIndicatorSize,
        'scrollEnabled' => PropKey::ScrollEnabled,
        'showsScrollIndicator' => PropKey::ShowsScrollIndicator,
        'showsHorizontalScrollIndicator' => PropKey::ShowsScrollIndicator,
        'showsVerticalScrollIndicator' => PropKey::ShowsScrollIndicator,
        'contentOffsetX' => PropKey::ScrollContentOffsetX,
        'contentOffsetY' => PropKey::ScrollContentOffsetY,
        'anchorToEnd' => PropKey::ScrollAnchorToEnd,
        'maintainVisibleContentPosition' => PropKey::ScrollMaintainVisibleContentPosition,
        'autoScrollToEndThreshold' => PropKey::ScrollAutoScrollToEndThreshold,
        'scrollTargetTestId' => PropKey::ScrollTargetTestId,
        'scrollRequest' => PropKey::ScrollRequest,
        'scrollTargetOffset' => PropKey::ScrollTargetOffset,
        'brushColor' => PropKey::DrawingColor,
        'drawingColor' => PropKey::DrawingColor,
        'brushWidth' => PropKey::DrawingWidth,
        'drawingWidth' => PropKey::DrawingWidth,
        'drawingMode' => PropKey::DrawingMode,
        'clearRequest' => PropKey::DrawingClearRequest,
        'undoRequest' => PropKey::DrawingUndoRequest,
        'fillViewport' => PropKey::ScrollFillViewport,
        'overScrollMode' => PropKey::ScrollOverScrollMode,
        'nestedScrollEnabled' => PropKey::ScrollNestedEnabled,
        'fadingEdgeLength' => PropKey::ScrollFadingEdgeLength,
        'persistentScrollbar' => PropKey::ScrollPersistentScrollbar,
        'pagingEnabled' => PropKey::ScrollPagingEnabled,
        'snapToInterval' => PropKey::ScrollSnapInterval,
        'decelerationRate' => PropKey::ScrollDecelerationRate,
        'keyboardDismissMode' => PropKey::ScrollKeyboardDismissMode,
        'animating' => PropKey::ActivityAnimating,
        'hidesWhenStopped' => PropKey::ActivityHidesWhenStopped,
        'indicatorSize' => PropKey::ActivitySize,
        'trackColorFalse' => PropKey::SwitchTrackColorFalse,
        'trackColorTrue' => PropKey::SwitchTrackColorTrue,
        'thumbColor' => PropKey::SwitchThumbColor,
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
        'estimatedRowHeight' => PropKey::ListRowHeight,
        'prefetch' => PropKey::ListPrefetch,
        'numColumns' => PropKey::ListNumColumns,
        'inverted' => PropKey::ListInverted,
        'initialScrollIndex' => PropKey::ListInitialScrollIndex,
        'removeClippedSubviews' => PropKey::ListRemoveClippedSubviews,
        'endReachedThreshold' => PropKey::EndReachedThreshold,
        'drawerOpen' => PropKey::DrawerOpen,
        'drawerPosition' => PropKey::DrawerPosition,
        'drawerType' => PropKey::DrawerType,
        'drawerWidth' => PropKey::DrawerWidth,
        'drawerOverlayColor' => PropKey::DrawerOverlayColor,
        'drawerSwipeEnabled' => PropKey::DrawerSwipeEnabled,
        'drawerSwipeEdgeWidth' => PropKey::DrawerSwipeEdgeWidth,
        'drawerSwipeMinDistance' => PropKey::DrawerSwipeMinDistance,
        'drawerKeyboardDismissMode' => PropKey::DrawerKeyboardDismissMode,
        'drawerHideStatusBarOnOpen' => PropKey::DrawerHideStatusBarOnOpen,
        'drawerStatusBarAnimation' => PropKey::DrawerStatusBarAnimation,
        'drawerPermanentBreakpoint' => PropKey::DrawerPermanentBreakpoint,
        'letterSpacing' => PropKey::LetterSpacing,
        'lineHeight' => PropKey::LineHeight,
        'placeholderColor' => PropKey::PlaceholderColor,
        'selectionColor' => PropKey::SelectionColor,
        'selectable' => PropKey::TextSelectable,
        'ellipsizeMode' => PropKey::TextEllipsizeMode,
        'allowFontScaling' => PropKey::TextAllowFontScaling,
        'maxFontSizeMultiplier' => PropKey::TextMaxFontSizeMultiplier,
        'adjustsFontSizeToFit' => PropKey::TextAdjustsFontSizeToFit,
        'minimumFontScale' => PropKey::TextMinimumFontScale,
        'textBreakStrategy' => PropKey::TextBreakStrategy,
        'androidHyphenationFrequency' => PropKey::TextHyphenationFrequency,
        'dataDetectorType' => PropKey::TextDataDetectorType,
        'maxLength' => PropKey::MaxLength,
        'autoFocus' => PropKey::AutoFocus,
        'returnKeyType' => PropKey::ReturnKeyType,
        'hitSlop' => PropKey::HitSlop,
        'hitSlopLeft' => PropKey::HitSlopLeft,
        'hitSlopTop' => PropKey::HitSlopTop,
        'hitSlopRight' => PropKey::HitSlopRight,
        'hitSlopBottom' => PropKey::HitSlopBottom,
        'pressRetentionLeft' => PropKey::PressRetentionLeft,
        'pressRetentionTop' => PropKey::PressRetentionTop,
        'pressRetentionRight' => PropKey::PressRetentionRight,
        'pressRetentionBottom' => PropKey::PressRetentionBottom,
        'delayLongPress' => PropKey::PressDelayLongMs,
        'delayPressIn' => PropKey::PressDelayInMs,
        'delayPressOut' => PropKey::PressDelayOutMs,
        'androidDisableSound' => PropKey::PressAndroidDisableSound,
        'rippleBorderless' => PropKey::RippleBorderless,
        'rippleRadius' => PropKey::RippleRadius,
        'rippleForeground' => PropKey::RippleForeground,
        'rippleAlpha' => PropKey::RippleAlpha,
        'zIndex' => PropKey::ZIndex,
        'overflow' => PropKey::Overflow,
        'flexDirection' => PropKey::FlexDirection,
        'flexWrap' => PropKey::FlexWrap,
        'layoutDirection' => PropKey::LayoutDirection,
        'gestureType' => PropKey::GestureType,
        'gestureEnabled' => PropKey::GestureEnabled,
        'gestureMinPointers' => PropKey::GestureMinPointers,
        'gestureMaxPointers' => PropKey::GestureMaxPointers,
        'gestureDirection' => PropKey::GestureDirection,
        'gestureComposition' => PropKey::GestureComposition,
        'gestureMinDistance' => PropKey::GestureMinDistance,
        'gestureMinDuration' => PropKey::GestureMinDurationMs,
        'flexShrink' => PropKey::FlexShrink,
        'paddingLeft' => PropKey::PaddingLeft,
        'paddingTop' => PropKey::PaddingTop,
        'paddingRight' => PropKey::PaddingRight,
        'paddingBottom' => PropKey::PaddingBottom,
        'marginLeft' => PropKey::MarginLeft,
        'marginTop' => PropKey::MarginTop,
        'marginRight' => PropKey::MarginRight,
        'marginBottom' => PropKey::MarginBottom,
        'position' => PropKey::PositionType,
        'left' => PropKey::Left,
        'top' => PropKey::Top,
        'right' => PropKey::Right,
        'bottom' => PropKey::Bottom,
        'leftPercent' => PropKey::LeftPercent,
        'topPercent' => PropKey::TopPercent,
        'rightPercent' => PropKey::RightPercent,
        'bottomPercent' => PropKey::BottomPercent,
        'aspectRatio' => PropKey::AspectRatio,
        'borderTopLeftRadius' => PropKey::BorderTopLeftRadius,
        'borderTopRightRadius' => PropKey::BorderTopRightRadius,
        'borderBottomRightRadius' => PropKey::BorderBottomRightRadius,
        'borderBottomLeftRadius' => PropKey::BorderBottomLeftRadius,
        'borderLeftWidth' => PropKey::BorderLeftWidth,
        'borderTopWidth' => PropKey::BorderTopWidth,
        'borderRightWidth' => PropKey::BorderRightWidth,
        'borderBottomWidth' => PropKey::BorderBottomWidth,
        'textDecoration' => PropKey::TextDecoration,
        'textTransform' => PropKey::TextTransform,
        'fontStyle' => PropKey::FontStyle,
        'widthPercent' => PropKey::WidthPercent,
        'heightPercent' => PropKey::HeightPercent,
        'maxWidthPercent' => PropKey::MaxWidthPercent,
        'maxHeightPercent' => PropKey::MaxHeightPercent,
        'pointerEvents' => PropKey::PointerEvents,
        'safeAreaBottom' => PropKey::SafeAreaBottom,
        'blurRadius' => PropKey::BlurRadius,
        'fontFamily' => PropKey::FontFamily,
        'marginLeftAuto' => PropKey::MarginLeftAuto,
        'translationXPercent' => PropKey::TranslationXPercent,
        'animationKind' => PropKey::AnimationKind,
        'accessible' => PropKey::Accessible,
        'accessibilityLiveRegion' => PropKey::AccessibilityLiveRegion,
        'importantForAccessibility' => PropKey::AccessibilityImportance,
        'accessibilityExpanded' => PropKey::AccessibilityExpanded,
        'accessibilityBusy' => PropKey::AccessibilityBusy,
        'accessibilityCheckedState' => PropKey::AccessibilityCheckedState,
        'accessibilityValueMin' => PropKey::AccessibilityValueMin,
        'accessibilityValueMax' => PropKey::AccessibilityValueMax,
        'accessibilityValueNow' => PropKey::AccessibilityValueNow,
        'accessibilityValueText' => PropKey::AccessibilityValueText,
        'safeAreaEdgeTop' => PropKey::SafeAreaTop,
        'safeAreaEdgeRight' => PropKey::SafeAreaRight,
        'safeAreaEdgeBottom' => PropKey::SafeAreaBottomEdge,
        'safeAreaEdgeLeft' => PropKey::SafeAreaLeft,
        'safeAreaMode' => PropKey::SafeAreaMode,
        'keyboardVerticalOffset' => PropKey::KeyboardVerticalOffset,
        'keyboardAvoidingEnabled' => PropKey::KeyboardAvoidingEnabled,
        'columns' => PropKey::GridColumns,
        'span' => PropKey::GridSpan,
        'spanSm' => PropKey::GridSpanSm,
        'spanMd' => PropKey::GridSpanMd,
        'spanLg' => PropKey::GridSpanLg,
        'spanXl' => PropKey::GridSpanXl,
        'offset' => PropKey::GridOffset,
        'offsetSm' => PropKey::GridOffsetSm,
        'offsetMd' => PropKey::GridOffsetMd,
        'offsetLg' => PropKey::GridOffsetLg,
        'offsetXl' => PropKey::GridOffsetXl,
        'order' => PropKey::GridOrder,
        'orderSm' => PropKey::GridOrderSm,
        'orderMd' => PropKey::GridOrderMd,
        'orderLg' => PropKey::GridOrderLg,
        'orderXl' => PropKey::GridOrderXl,
        'gutterX' => PropKey::GridColumnGap,
        'gutterY' => PropKey::GridRowGap,
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
        'on:close' => EventKind::Native,
        'on:loadStart' => EventKind::ImageLoadStart,
        'on:progress' => EventKind::ImageProgress,
        'on:load' => EventKind::ImageLoad,
        'on:error' => EventKind::ImageError,
        'on:loadEnd' => EventKind::ImageLoadEnd,
        'on:endEditing' => EventKind::InputEndEditing,
        'on:selectionChange' => EventKind::InputSelectionChange,
        'on:contentSizeChange' => EventKind::InputContentSizeChange,
        'on:keyPress' => EventKind::InputKeyPress,
        'on:pressIn' => EventKind::PressIn,
        'on:pressOut' => EventKind::PressOut,
        'on:pressMove' => EventKind::PressMove,
        'on:requestClose' => EventKind::ModalRequestClose,
        'on:show' => EventKind::ModalShow,
        'on:dismiss' => EventKind::ModalDismiss,
        'on:orientationChange' => EventKind::ModalOrientationChange,
        'p-click-outside' => EventKind::ClickOutside,
        'p-intersect' => EventKind::Intersect,
        'p-mutate' => EventKind::Mutate,
        'p-resize' => EventKind::Resize,
        'p-scroll' => EventKind::Scroll,
        'p-touch-start' => EventKind::TouchStart,
        'p-touch-move' => EventKind::TouchMove,
        'p-touch-end' => EventKind::TouchEnd,
        'on:gestureBegin' => EventKind::GestureBegin,
        'on:gestureUpdate' => EventKind::GestureUpdate,
        'on:gestureEnd' => EventKind::GestureEnd,
        'on:gestureCancel' => EventKind::GestureCancel,
        'on:sheetChange' => EventKind::BottomSheetChange,
        'on:sheetDismiss' => EventKind::BottomSheetDismiss,
        'on:webLoad' => EventKind::WebViewLoad,
        'on:webError' => EventKind::WebViewError,
        'on:message' => EventKind::WebViewMessage,
        'on:ready' => EventKind::MediaReady,
        'on:mediaProgress' => EventKind::MediaProgress,
        'on:end' => EventKind::MediaEnd,
        'on:mediaError' => EventKind::MediaError,
        'on:dragStart' => EventKind::DragStart,
        'on:dragEnd' => EventKind::DragEnd,
        'on:drop' => EventKind::Drop,
        'on:menuAction' => EventKind::MenuAction,
        'on:animationComplete' => EventKind::AnimationComplete,
        'on:cacheHit' => EventKind::MediaCacheHit,
        'on:cacheMiss' => EventKind::MediaCacheMiss,
        'on:cacheProgress' => EventKind::MediaCacheProgress,
        'on:cacheReady' => EventKind::MediaCacheReady,
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
        $data['__pamStyles'] = self::styleSheet($tree);
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

        $branchMatched = false;

        foreach ($nodes as $index => $node) {
            $nodeData = [
                ...$data,
                '__pamNodePath' => self::nodePath($data, $index),
            ];
            if ($node->kind === 2) {
                $output[] = self::interpolate($node->value, $scope, $nodeData);
                $branchMatched = false;
                continue;
            }

            $tag = $node->name;
            $attributes = self::directiveAliases($node->attributes);
            $node = self::withAttributes($node, $attributes);
            $children = $node->children;

            if (isset($attributes['p-for'])) {
                $directive = $attributes['p-for'];
                if (
                    !is_string($directive)
                    || preg_match(
                        '/^\s*\$?([A-Za-z_][A-Za-z0-9_]*)\s+in\s+(.+)\s*$/D',
                        $directive,
                        $match,
                    ) !== 1
                ) {
                    throw new RuntimeException(
                        'p-for must use "$item in $items" syntax.',
                    );
                }
                $items = TemplateExpression::evaluate($match[2], $scope, $nodeData);
                if (is_int($items)) {
                    $items = $items > 0 ? range(1, $items) : [];
                } elseif ($items instanceof Traversable) {
                    $items = iterator_to_array($items);
                }
                if (!is_array($items)) {
                    throw new RuntimeException(
                        'p-for source must resolve to an integer, array, or Traversable.',
                    );
                }
                $loopNode = self::withoutAttributes($node, ['p-for']);
                foreach ($items as $itemIndex => $item) {
                    array_push(
                        $output,
                        ...self::nodes([$loopNode], $scope, [
                            ...$nodeData,
                            $match[1] => $item,
                            $match[1].'Index' => $itemIndex,
                            '__pamNodePath' =>
                                self::pathValue(
                                    $nodeData['__pamNodePath'] ?? 'root',
                                ).'.'.(string) $itemIndex,
                        ]),
                    );
                }
                $branchMatched = false;
                continue;
            }

            if (isset($attributes['p-if'])) {
                $condition = self::dynamicValue(
                    $attributes['p-if'],
                    $scope,
                    $nodeData,
                );
                $branchMatched = (bool) $condition;
                if (!$branchMatched) {
                    continue;
                }
            } elseif (isset($attributes['p-else-if'])) {
                if ($branchMatched) {
                    continue;
                }
                $branchMatched = (bool) self::dynamicValue(
                    $attributes['p-else-if'],
                    $scope,
                    $nodeData,
                );
                if (!$branchMatched) {
                    continue;
                }
            } elseif (isset($attributes['p-else'])) {
                if ($branchMatched) {
                    $branchMatched = false;
                    continue;
                }
                $branchMatched = true;
            } else {
                $branchMatched = false;
            }

            $node = self::withoutAttributes(
                $node,
                ['p-if', 'p-else-if', 'p-else'],
            );
            $attributes = $node->attributes;

            if ($tag === 'If') {
                $condition = self::value(
                    $attributes['condition'] ?? false,
                    $scope,
                    $nodeData,
                );

                if ((bool) $condition) {
                    array_push($output, ...self::nodes($children, $scope, $nodeData));
                }

                continue;
            }

            if ($tag === 'Each') {
                $items = self::value($attributes['items'] ?? [], $scope, $nodeData);
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
                            ...$nodeData,
                            $name => $item,
                            $name.'Index' => $index,
                        ]),
                    );
                }

                continue;
            }

            if ($tag === 'Slot') {
                $name = (string) ($attributes['name'] ?? 'slot');
                $slot = $nodeData[$name] ?? [];
                if ($slot instanceof Renderable) {
                    $slot = [$slot];
                }
                if (!is_array($slot)) {
                    throw new RuntimeException("Slot {$name} must resolve to renderable content.");
                }
                if ($slot === [] && $children !== []) {
                    array_push($output, ...self::nodes($children, $scope, $nodeData));
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
                $output[] = self::tag(
                    $tag,
                    $attributes,
                    $children,
                    $scope,
                    $nodeData,
                );
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
     * @param list<string> $names
     */
    private static function withoutAttributes(
        CompiledTemplateNode $node,
        array $names,
    ): CompiledTemplateNode {
        $attributes = array_diff_key($node->attributes, array_flip($names));
        $copy = new CompiledTemplateNode(
            kind: $node->kind,
            name: $node->name,
            attributes: $attributes,
            source: $node->source,
            line: $node->line,
            column: $node->column,
            value: $node->value,
        );
        $copy->children = $node->children;

        return $copy;
    }

    /** @param array<string, mixed> $data */
    private static function nodePath(array $data, int $index): string
    {
        $parent = $data['__pamNodePath'] ?? 'root';

        return (is_string($parent) || is_int($parent)
            ? (string) $parent
            : 'root').'.'.$index;
    }

    private static function pathValue(mixed $value): string
    {
        return is_string($value) || is_int($value)
            ? (string) $value
            : 'root';
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
        $factory = TemplateRegistry::factory($tag);
        if ($factory === null) {
            $attributes = self::nativeEventAliases($attributes);
        }
        $resolvedClass = self::classValue($attributes, $scope, $data);
        $attributes = [
            ...self::scopedStyleAttributes($tag, $resolvedClass, $data),
            ...$attributes,
        ];
        if (isset($attributes['bind:value'])) {
            $attributes[':value'] = $attributes['bind:value'];
            $attributes['model'] = ltrim(
                self::stringValue(
                    $attributes['bind:value'],
                    'bind:value target',
                ),
                '$',
            );
            unset($attributes['bind:value']);
        }
        $checkedBinding = null;
        if (isset($attributes['bind:checked'])) {
            $checkedBinding = self::stringValue(
                $attributes['bind:checked'],
                'bind:checked target',
            );
            $attributes[':checked'] = $attributes['bind:checked'];
            unset($attributes['bind:checked']);
        }
        $values = [];

        foreach ($attributes as $name => $raw) {
            if (
                isset(self::EVENTS[$name])
                || $name === 'class'
                || $name === ':class'
                || $name === 'p-ripple'
                || $name === ':p-ripple'
                || str_starts_with($name, '@')
            ) {
                continue;
            }

            $valueName = ltrim($name, ':');
            if ($factory === null && in_array($tag, ['Image', 'MediaPlayer'], true)) {
                $valueName = preg_replace_callback(
                    '/-([a-z])/',
                    static fn (array $match): string => strtoupper($match[1]),
                    $valueName,
                ) ?? $valueName;
            }
            $values[$valueName] = str_starts_with($name, ':')
                ? self::dynamicValue($raw, $scope, $data)
                : self::value($raw, $scope, $data);
        }
        $rippleAttribute = $attributes[':p-ripple'] ?? $attributes['p-ripple'] ?? null;
        if (array_key_exists(':p-ripple', $attributes)) {
            $rippleAttribute = self::dynamicValue($rippleAttribute, $scope, $data);
        } elseif (array_key_exists('p-ripple', $attributes)) {
            $rippleAttribute = self::value($rippleAttribute, $scope, $data);
        }
        if ($rippleAttribute !== null && $rippleAttribute !== false) {
            $ripple = is_array($rippleAttribute) ? $rippleAttribute : [];
            $values['rippleColor'] = $ripple['color']
                ?? (is_int($rippleAttribute) ? $rippleAttribute : 0);
            $values['rippleAlpha'] = $ripple['alpha'] ?? 0.12;
            $values['rippleBorderless'] = $ripple['borderless'] ?? false;
            $values['rippleForeground'] = $ripple['foreground'] ?? false;
            if (isset($ripple['radius'])) {
                $values['rippleRadius'] = $ripple['radius'];
            }
        }
        if ($factory !== null && $resolvedClass !== null) {
            $values['className'] = $resolvedClass;
        }
        if ($factory !== null) {
            $values['__pamNodePath'] = $data['__pamNodePath'] ?? $tag;
        }

        $inheritedVariants = $data['__pamParentVariants'] ?? [];
        if (!is_array($inheritedVariants)) {
            $inheritedVariants = [];
        }
        $ownVariants = array_filter(
            $values,
            static fn (mixed $value, string $name): bool =>
                !str_starts_with($name, '__pam')
                && self::isDeclarativeContextValue($value),
            ARRAY_FILTER_USE_BOTH,
        );
        $ownHandlers = [];
        foreach (self::EVENTS as $name => $event) {
            if (isset($attributes[$name])) {
                $ownHandlers[$event->value] = self::handler(
                    $attributes[$name],
                    $event,
                    $scope,
                    $data,
                );
            }
        }
        $componentEvents = [];
        if ($factory !== null) {
            foreach ($attributes as $name => $raw) {
                if (!str_starts_with($name, '@')) {
                    continue;
                }
                $event = substr($name, 1);
                if (
                    $event === ''
                    || preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $event)
                        !== 1
                ) {
                    throw new RuntimeException(
                        "Invalid component event {$name}.",
                    );
                }
                $componentEvents[$event] = self::componentHandler(
                    $raw,
                    $scope,
                    $data,
                );
            }
        }
        $inheritedEventContexts = $data['__pamEventContexts'] ?? [];
        if (!is_array($inheritedEventContexts)) {
            $inheritedEventContexts = [];
        }
        $childEventContexts = $inheritedEventContexts;
        if ($factory !== null && $ownHandlers !== []) {
            $childEventContexts[$tag] = [
                'props' => [...$inheritedVariants, ...$values],
                'events' => $ownHandlers,
            ];
        }
        $childData = [
            ...$data,
            '__pamParentVariants' => [
                ...$inheritedVariants,
                ...$ownVariants,
            ],
            '__pamEventContexts' => $childEventContexts,
        ];
        [$defaultNodes, $slotNodes] = $factory === null
            ? [$childNodes, []]
            : self::componentSlotNodes($childNodes);
        $renderedChildren = self::nodes($defaultNodes, $scope, $childData);
        $children = array_values(array_filter(
            $renderedChildren,
            static fn (mixed $value): bool => $value instanceof Element,
        ));
        $slots = ['slot' => $children];
        foreach ($slotNodes as $slotName => $nodes) {
            $renderedSlot = self::nodes($nodes, $scope, $childData);
            $slotElements = array_values(array_filter(
                $renderedSlot,
                static fn (mixed $value): bool => $value instanceof Element,
            ));
            if (count($slotElements) !== count($renderedSlot)) {
                throw new RuntimeException(
                    "Named slot {$slotName} must contain renderable elements.",
                );
            }
            $slots[$slotName] = $slotElements;
        }
        $text = implode('', array_filter(
            $renderedChildren,
            static fn (mixed $value): bool => is_string($value),
        ));

        if ($factory === null && $text !== '' && !in_array($tag, ['Text', 'Button'], true)) {
            throw new RuntimeException("Text content is not valid inside {$tag}; wrap it in Text.");
        }

        if ($factory === null && $children !== [] && in_array($tag, ['Text', 'Button'], true)) {
            throw new RuntimeException("{$tag} cannot contain element children.");
        }
        if ($text !== '') {
            $values['text'] ??= $text;
        }
        if ($factory !== null && $inheritedVariants !== []) {
            $values['__parentVariants'] = $inheritedVariants;
        }
        if ($factory !== null && $inheritedEventContexts !== []) {
            $values['__pamEventContexts'] = $inheritedEventContexts;
        }
        if ($factory !== null) {
            $values['__pamSlots'] = $slots;
            $values['__pamComponentEvents'] = $componentEvents;
        }

        $element = $factory !== null
            ? $factory($values, $children, $scope)->toElement()
            : match ($tag) {
            'Screen' => Screen::make(...$children),
            'Column' => Column::make(...$children),
            'Row' => Row::make(...$children),
            'Grid' => Grid::make(...$children),
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
            'DrawingCanvas' => DrawingCanvas::make(
                self::stringValue($values['source'] ?? '', 'DrawingCanvas source'),
                self::stringValue($values['value'] ?? '', 'DrawingCanvas value'),
            ),
            'ImageBackground' => ImageBackground::make(
                self::stringValue($values['source'] ?? '', 'Image source'),
                ...$children,
            ),
            'Scroll' => Scroll::make(self::singleChild($children, $tag)),
            'ScrollView' => Scroll::make(self::scrollViewContent(
                $children,
                self::boolValue(
                    $values['horizontal'] ?? false,
                    'ScrollView horizontal',
                ),
            )),
            'FlatList', 'NativeList' => FlatList::make(
                self::stringItems($values['items'] ?? []),
            ),
            'VirtualizedList' => $children === []
                ? FlatList::make(self::stringItems($values['items'] ?? []))
                : VirtualizedList::make(...$children),
            'VirtualGrid' => VirtualGrid::make(
                max(1, self::intValue(
                    $values['columns'] ?? $values['numColumns'] ?? 2,
                    'VirtualGrid columns',
                )),
                ...$children,
            ),
            'SectionList' => SectionList::make(self::sections($values['sections'] ?? [])),
            'Spacer' => Spacer::make(self::floatValue($values['size'] ?? 8.0, 'Spacer size')),
            'GestureDetector' => GestureDetector::make(
                self::gestureType($values['gestureType'] ?? 'tap'),
                self::singleChild($children, $tag),
            ),
            'Pressable', 'TouchableOpacity', 'TouchableHighlight',
            'TouchableWithoutFeedback', 'TouchableNativeFeedback' => Pressable::make(...$children),
            'ActivityIndicator' => ActivityIndicator::make(self::boolValue(
                $values['animating'] ?? $values['visible'] ?? true,
                'ActivityIndicator animating',
            ))
                ->hidesWhenStopped(self::boolValue(
                    $values['hidesWhenStopped'] ?? true,
                    'ActivityIndicator hidesWhenStopped',
                ))
                ->size(self::activityIndicatorSize($values['size'] ?? 'small')),
            'Switch' => Toggle::make(self::boolValue($values['checked'] ?? false, 'Switch checked')),
            'Modal' => Modal::make(
                self::singleChild($children, $tag),
                self::boolValue($values['visible'] ?? true, 'Modal visible'),
                self::modalPresentation($values['presentation'] ?? 'dialog'),
            ),
            'BottomSheet' => BottomSheet::make(
                self::singleChild($children, $tag),
                self::bottomSheetSnapPoints($values['snapPoints'] ?? [0.5, 0.9]),
                self::intValue($values['index'] ?? 0, 'BottomSheet index'),
                self::boolValue($values['visible'] ?? true, 'BottomSheet visible'),
            )
                ->dismissible(self::boolValue(
                    $values['dismissible'] ?? true,
                    'BottomSheet dismissible',
                ))
                ->backdropDismiss(self::boolValue(
                    $values['backdropDismiss'] ?? true,
                    'BottomSheet backdropDismiss',
                ))
                ->handleVisible(self::boolValue(
                    $values['handleVisible'] ?? true,
                    'BottomSheet handleVisible',
                ))
                ->dragEnabled(self::boolValue(
                    $values['dragEnabled'] ?? true,
                    'BottomSheet dragEnabled',
                ))
                ->cornerRadius(self::floatValue(
                    $values['cornerRadius'] ?? 20.0,
                    'BottomSheet cornerRadius',
                ))
                ->keyboardBehavior(self::bottomSheetKeyboardBehavior(
                    $values['keyboardBehavior'] ?? 'interactive',
                )),
            'WebView' => WebView::make(self::stringValue(
                $values['source'] ?? $values['url'] ?? '',
                'WebView source',
            ))
                ->javaScriptEnabled(self::boolValue(
                    $values['javaScriptEnabled'] ?? true,
                    'WebView javaScriptEnabled',
                ))
                ->domStorageEnabled(self::boolValue(
                    $values['domStorageEnabled'] ?? true,
                    'WebView domStorageEnabled',
                ))
                ->allowsInlineMedia(self::boolValue(
                    $values['allowsInlineMedia'] ?? true,
                    'WebView allowsInlineMedia',
                )),
            'Video', 'Audio', 'MediaPlayer' => MediaPlayer::make(
                self::stringValue($values['source'] ?? '', 'Media source'),
                $tag === 'Audio'
                    ? \Pam\Native\MediaType::Audio
                    : \Pam\Native\MediaType::Video,
            )
                ->autoPlay(self::boolValue($values['autoPlay'] ?? false, 'Media autoPlay'))
                ->controls(self::boolValue($values['controls'] ?? true, 'Media controls'))
                ->loop(self::boolValue($values['loop'] ?? false, 'Media loop'))
                ->muted(self::boolValue($values['muted'] ?? false, 'Media muted'))
                ->volume(self::floatValue($values['volume'] ?? 1.0, 'Media volume'))
                ->currentTime(self::floatValue(
                    $values['currentTime'] ?? 0.0,
                    'Media currentTime',
                ))
                ->playbackRate(self::floatValue(
                    $values['playbackRate'] ?? 1.0,
                    'Media playbackRate',
                )),
            'InteractionRegion' => InteractionRegion::make(...$children)
                ->draggable(
                    self::stringValue($values['dragData'] ?? '', 'Interaction dragData'),
                    self::boolValue($values['draggable'] ?? false, 'Interaction draggable'),
                )
                ->acceptsDrop(self::boolValue(
                    $values['dropEnabled'] ?? false,
                    'Interaction dropEnabled',
                ))
                ->contextMenu(self::nativeMenuItems($values['menuItems'] ?? [])),
            'Animated' => Animated::make(
                self::singleChild($children, $tag),
                self::animationKeyframes($values['keyframes'] ?? []),
                self::intValue($values['durationMs'] ?? 300, 'Animated durationMs'),
                self::animationEasing($values['easing'] ?? 'easeInOut'),
            )
                ->iterations(self::intValue(
                    $values['iterations'] ?? 1,
                    'Animated iterations',
                ))
                ->delay(self::intValue($values['delayMs'] ?? 0, 'Animated delayMs'))
                ->fillMode(self::animationFillMode($values['fillMode'] ?? 'forwards'))
                ->playState(self::animationPlayState($values['playState'] ?? 'running'))
                ->autoReverse(self::boolValue(
                    $values['autoReverse'] ?? false,
                    'Animated autoReverse',
                )),
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
                ...$children,
            ),
            default => self::custom($tag, $values, $children, $scope),
            };

        if ($factory === null && ($element instanceof Image || $element instanceof MediaPlayer)) {
            $element = self::mediaCacheAttributes($element, $values);
        }

        if ($resolvedClass !== null) {
            $element = self::classes($element, $resolvedClass, $data);
        }

        $element = self::attributes($element, $values);

        foreach (self::EVENTS as $name => $event) {
            if (isset($attributes[$name])) {
                $handler = $ownHandlers[$event->value]
                    ?? throw new RuntimeException(
                        "Template event {$name} was not resolved.",
                    );
                if ($factory !== null) {
                    $handler = TemplateRegistry::adaptEvent(
                        $tag,
                        $event,
                        $handler,
                        $values,
                    );
                }
                if ($factory === null && $element instanceof Input) {
                    $element = match ($event) {
                        EventKind::InputEndEditing =>
                            $element->onEndEditing($handler),
                        EventKind::InputSelectionChange =>
                            $element->onSelectionChange($handler),
                        EventKind::InputContentSizeChange =>
                            $element->onContentSizeChange($handler),
                        EventKind::InputKeyPress =>
                            $element->onKeyPress($handler),
                        default => $element->on($event, $handler),
                    };
                } elseif ($factory === null && $element instanceof Pressable) {
                    $element = match ($event) {
                        EventKind::PressIn => $element->onPressIn($handler),
                        EventKind::PressOut => $element->onPressOut($handler),
                        EventKind::PressMove => $element->onPressMove($handler),
                        default => $element->on($event, $handler),
                    };
                } elseif ($factory === null && $element instanceof Modal) {
                    $element = match ($event) {
                        EventKind::ModalRequestClose =>
                            $element->onRequestClose($handler),
                        EventKind::ModalShow => $element->onShow($handler),
                        EventKind::ModalDismiss => $element->onDismiss($handler),
                        EventKind::ModalOrientationChange =>
                            $element->onOrientationChange($handler),
                        default => $element->on($event, $handler),
                    };
                } elseif ($factory === null && $element instanceof BottomSheet) {
                    $element = match ($event) {
                        EventKind::BottomSheetChange => $element->onChange($handler),
                        EventKind::BottomSheetDismiss => $element->onDismiss($handler),
                        default => $element->on($event, $handler),
                    };
                } else {
                    $element = $element->on($event, $handler);
                }
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
        if ($checkedBinding !== null) {
            if ($element->kind() !== NodeKind::Switch) {
                throw new RuntimeException(
                    'bind:checked is only valid on Switch or Toggle.',
                );
            }
            $element = $element->on(
                EventKind::Toggle,
                self::checkedModelHandler($checkedBinding, $scope),
            );
        }

        return $element;
    }

    private static function mediaCacheAttributes(
        Image|MediaPlayer $element,
        array $values,
    ): Image|MediaPlayer {
        if (array_key_exists('cache', $values)) {
            $element = $element->cache(self::mediaCachePolicy($values['cache']));
        }
        if (isset($values['cacheKey'])) {
            $element = $element->cacheKey(self::stringValue($values['cacheKey'], 'Media cacheKey'));
        }
        if (isset($values['cacheMaxAge'])) {
            $element = $element->maxAge(self::durationMs($values['cacheMaxAge'], 'Media cacheMaxAge'));
        }
        if (isset($values['cacheTags'])) {
            $raw = $values['cacheTags'];
            $tags = is_array($raw)
                ? array_values($raw)
                : (preg_split('/[,\n]+/', self::stringValue($raw, 'Media cacheTags')) ?: []);
            $element = $element->cacheTags(array_map(
                static fn (mixed $tag): string => trim(self::stringValue($tag, 'Media cache tag')),
                $tags,
            ));
        }
        if (isset($values['pinOffline'])) {
            $element = $element->pinOffline(self::boolValue($values['pinOffline'], 'Media pinOffline'));
        }
        if (isset($values['priority'])) {
            $element = $element->priority(self::mediaPriority($values['priority']));
        }
        if (isset($values['cacheMaxBytes'])) {
            $element = $element->maxCacheSize(self::byteSize(
                $values['cacheMaxBytes'],
                'Media cacheMaxBytes',
            ));
        }
        if (isset($values['checksum'])) {
            $element = $element->checksum(self::stringValue($values['checksum'], 'Media checksum'));
        }
        if (isset($values['thumbnail'])) {
            $element = $element->thumbnail(self::stringValue($values['thumbnail'], 'Media thumbnail'));
        }
        if ($element instanceof Image && isset($values['resizeWidth'], $values['resizeHeight'])) {
            $element = $element->resize(
                self::intValue($values['resizeWidth'], 'Image resizeWidth'),
                self::intValue($values['resizeHeight'], 'Image resizeHeight'),
            );
        }
        if ($element instanceof MediaPlayer) {
            if (isset($values['streamingCache'])) {
                $element = $element->streamingCache(self::boolValue(
                    $values['streamingCache'],
                    'Media streamingCache',
                ));
            }
            if (isset($values['preloadSeconds'])) {
                $element = $element->preloadSeconds(self::intValue(
                    $values['preloadSeconds'],
                    'Media preloadSeconds',
                ));
            }
            if (isset($values['downloadWhilePlaying'])) {
                $element = $element->downloadWhilePlaying(self::boolValue(
                    $values['downloadWhilePlaying'],
                    'Media downloadWhilePlaying',
                ));
            }
        }

        return $element;
    }

    private static function mediaCachePolicy(mixed $value): MediaCachePolicy
    {
        if ($value === true || $value === '' || $value === null) {
            return MediaCachePolicy::MemoryAndDisk;
        }
        $normalized = strtolower(str_replace(['-', '_'], '', self::stringValue($value, 'Media cache')));

        return match ($normalized) {
            'none' => MediaCachePolicy::None,
            'memory' => MediaCachePolicy::Memory,
            'disk' => MediaCachePolicy::Disk,
            'memoryanddisk' => MediaCachePolicy::MemoryAndDisk,
            'cachefirst' => MediaCachePolicy::CacheFirst,
            'networkfirst' => MediaCachePolicy::NetworkFirst,
            'cacheonly' => MediaCachePolicy::CacheOnly,
            'stalewhilerevalidate' => MediaCachePolicy::StaleWhileRevalidate,
            default => throw new RuntimeException("Unknown media cache policy {$value}."),
        };
    }

    private static function mediaPriority(mixed $value): MediaPriority
    {
        $normalized = strtolower(self::stringValue($value, 'Media priority'));

        return match ($normalized) {
            'background' => MediaPriority::Background,
            'prefetch' => MediaPriority::Prefetch,
            'normal' => MediaPriority::Normal,
            'visible' => MediaPriority::Visible,
            'immediate' => MediaPriority::Immediate,
            default => throw new RuntimeException("Unknown media priority {$value}."),
        };
    }

    private static function durationMs(mixed $value, string $context): int
    {
        if (is_int($value)) {
            return max(0, $value);
        }
        $raw = strtolower(trim(self::stringValue($value, $context)));
        if (preg_match('/^(\d+)(ms|s|m|h|d)$/D', $raw, $match) !== 1) {
            throw new RuntimeException("{$context} must use ms, s, m, h or d.");
        }
        $factor = match ($match[2]) {
            'ms' => 1,
            's' => 1_000,
            'm' => 60_000,
            'h' => 3_600_000,
            'd' => 86_400_000,
        };

        return min(31_536_000_000, (int) $match[1] * $factor);
    }

    private static function byteSize(mixed $value, string $context): int
    {
        if (is_int($value)) {
            return max(0, $value);
        }
        $raw = strtolower(trim(self::stringValue($value, $context)));
        if (preg_match('/^(\d+)(b|kb|mb|gb)$/D', $raw, $match) !== 1) {
            throw new RuntimeException("{$context} must use b, kb, mb or gb.");
        }
        $factor = match ($match[2]) {
            'b' => 1,
            'kb' => 1_024,
            'mb' => 1_048_576,
            'gb' => 1_073_741_824,
        };

        return min(10_737_418_240, (int) $match[1] * $factor);
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

        if (
            !isset($attributes['accessibilityLabel'])
            && isset($attributes['alt'])
            && in_array(
                $element->kind(),
                [NodeKind::Image, NodeKind::ImageBackground, NodeKind::DrawingCanvas],
                true,
            )
        ) {
            $element = $element
                ->accessibilityLabel(self::stringValue(
                    $attributes['alt'],
                    'Image alt text',
                ))
                ->accessible();
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

        if ($element instanceof Input) {
            if (isset($attributes['readOnly'])) {
                $element = $element->editable(!self::boolValue(
                    $attributes['readOnly'],
                    'Input readOnly',
                ));
            }
            if (isset($attributes['scrollEnabled'])) {
                $element = $element->scrollEnabled(self::boolValue(
                    $attributes['scrollEnabled'],
                    'Input scrollEnabled',
                ));
            }
        }

        if (
            $element instanceof Pressable
            && is_numeric($attributes['pressRetentionOffset'] ?? null)
        ) {
            $element = $element->pressRetentionOffset(
                max(0.0, (float) $attributes['pressRetentionOffset']),
            );
        }

        if (
            $element instanceof Modal
            && array_key_exists('statusBarTranslucent', $attributes)
        ) {
            $element = $element->statusBarTranslucent(self::boolValue(
                $attributes['statusBarTranslucent'],
                'Modal statusBarTranslucent',
            ));
        }

        if (array_key_exists('horizontal', $attributes)) {
            $horizontal = self::boolValue(
                $attributes['horizontal'],
                'Horizontal',
            );
            $key = match ($element->kind()) {
                NodeKind::Scroll => PropKey::ScrollHorizontal,
                NodeKind::List, NodeKind::SectionList, NodeKind::VirtualList =>
                    PropKey::ListHorizontal,
                default => null,
            };
            if ($key !== null) {
                $element = $element->property($key, $horizontal);
            }
        }

        if (
            $element->kind() === NodeKind::Scroll
            && is_numeric($attributes['contentOffset'] ?? null)
        ) {
            $offsetKey = self::boolValue(
                $attributes['horizontal'] ?? false,
                'Horizontal',
            )
                ? PropKey::ScrollContentOffsetX
                : PropKey::ScrollContentOffsetY;
            $element = $element->property(
                $offsetKey,
                max(0.0, (float) $attributes['contentOffset']),
            );
        }

        foreach (self::PROPERTIES as $name => $key) {
            if (!array_key_exists($name, $attributes)) {
                continue;
            }
            if (
                ($element instanceof Image || $element instanceof MediaPlayer)
                && in_array($name, [
                    'cache',
                    'cacheKey',
                    'cacheMaxAge',
                    'cacheTags',
                    'pinOffline',
                    'priority',
                    'cacheMaxBytes',
                    'checksum',
                    'thumbnail',
                    'resizeWidth',
                    'resizeHeight',
                    'streamingCache',
                    'preloadSeconds',
                    'downloadWhilePlaying',
                ], true)
            ) {
                continue;
            }
            if (
                $element instanceof BottomSheet
                && in_array(
                    $name,
                    [
                        'snapPoints',
                        'index',
                        'dismissible',
                        'backdropDismiss',
                        'handleVisible',
                        'dragEnabled',
                        'cornerRadius',
                        'keyboardBehavior',
                    ],
                    true,
                )
            ) {
                continue;
            }
            if (!self::propertyAppliesToKind($key, $element->kind())) {
                continue;
            }

            $value = self::propertyValue($key, $attributes[$name]);

            if ($value !== null) {
                $element = $element->property($key, $value);
            }
        }

        return $element;
    }

    private static function propertyAppliesToKind(
        PropKey $key,
        NodeKind $kind,
    ): bool {
        return match ($key) {
            PropKey::ScrollContentOffsetX,
            PropKey::ScrollContentOffsetY,
            PropKey::ScrollAnchorToEnd,
            PropKey::ScrollMaintainVisibleContentPosition,
            PropKey::ScrollAutoScrollToEndThreshold,
            PropKey::ScrollTargetTestId,
            PropKey::ScrollRequest,
            PropKey::ScrollFillViewport,
            PropKey::ScrollOverScrollMode,
            PropKey::ScrollNestedEnabled,
            PropKey::ScrollFadingEdgeLength,
            PropKey::ScrollPersistentScrollbar,
            PropKey::ScrollPagingEnabled,
            PropKey::ScrollSnapInterval,
            PropKey::ScrollDecelerationRate,
            PropKey::ScrollKeyboardDismissMode,
            => $kind === NodeKind::Scroll,
            PropKey::ScrollEnabled =>
                in_array(
                    $kind,
                    [
                        NodeKind::Scroll,
                        NodeKind::List,
                        NodeKind::SectionList,
                        NodeKind::VirtualList,
                    ],
                    true,
                ),
            PropKey::GridColumns => $kind !== NodeKind::VirtualList,
            PropKey::ActivityAnimating,
            PropKey::ActivityHidesWhenStopped,
            PropKey::ActivitySize,
            => $kind === NodeKind::ActivityIndicator,
            PropKey::SwitchTrackColorFalse,
            PropKey::SwitchTrackColorTrue,
            PropKey::SwitchThumbColor,
            => $kind === NodeKind::Switch,
            PropKey::StatusBarColor,
            PropKey::StatusBarStyle,
            PropKey::StatusBarHidden,
            PropKey::StatusBarAnimated,
            PropKey::StatusBarTranslucent,
            => $kind === NodeKind::StatusBar,
            PropKey::ModalPresentation,
            PropKey::ModalAnimationType,
            PropKey::ModalBackdropColor,
            PropKey::ModalTransparent,
            PropKey::ModalHardwareAccelerated,
            PropKey::ModalNavigationBarTranslucent,
            PropKey::ModalStatusBarTranslucent,
            PropKey::ModalAllowSwipeDismissal,
            => $kind === NodeKind::Modal,
            PropKey::ImageDefaultSource,
            PropKey::ImageLoadingIndicatorSource,
            PropKey::ImageFadeDurationMs,
            PropKey::ImageResizeMethod,
            PropKey::ImageResizeMultiplier,
            PropKey::ImageProgressiveRenderingEnabled,
            PropKey::ImageCachePolicy,
            PropKey::ImageOverlayColor,
            PropKey::ImageSourceSet,
            PropKey::ImageRequestHeaders,
            => in_array(
                $kind,
                [NodeKind::Image, NodeKind::ImageBackground, NodeKind::DrawingCanvas],
                true,
            ),
            PropKey::InputEditable,
            PropKey::InputAutoCorrect,
            PropKey::InputAutoCapitalize,
            PropKey::InputCaretHidden,
            PropKey::InputContextMenuHidden,
            PropKey::InputCursorColor,
            PropKey::InputDisableFullscreenUi,
            PropKey::InputAutofillImportance,
            PropKey::InputMode,
            PropKey::InputMinLines,
            PropKey::InputSelectTextOnFocus,
            PropKey::InputSelectionStart,
            PropKey::InputSelectionEnd,
            PropKey::InputShowSoftInputOnFocus,
            PropKey::InputSubmitBehavior,
            PropKey::InputTextAlignVertical,
            PropKey::InputReturnKeyLabel,
            PropKey::InputScrollEnabled,
            PropKey::InputUnderlineColor,
            => $kind === NodeKind::Input,
            default => true,
        };
    }

    private static function propertyValue(PropKey $key, mixed $value): string|int|float|bool|null
    {
        return match ($key) {
            PropKey::BackgroundColor,
            PropKey::TextColor,
            PropKey::BorderColor,
            PropKey::ProgressColor,
            PropKey::TintColor,
            PropKey::StatusBarColor,
            PropKey::RippleColor,
            PropKey::PlaceholderColor,
            PropKey::SelectionColor,
            PropKey::RefreshProgressBackgroundColor,
            PropKey::SwitchTrackColorFalse,
            PropKey::SwitchTrackColorTrue,
            PropKey::SwitchThumbColor,
            PropKey::ImageOverlayColor,
            PropKey::InputCursorColor,
            PropKey::InputUnderlineColor,
            PropKey::ModalBackdropColor,
            PropKey::DrawerOverlayColor,
            PropKey::DrawingColor,
            => self::colorValue($value, "Template {$key->name}"),
            PropKey::AlignItems, PropKey::AlignSelf => self::named($value, [
                'start' => 1, 'flex-start' => 1, 'center' => 2,
                'end' => 3, 'flex-end' => 3, 'stretch' => 4,
            ]),
            PropKey::JustifyContent => self::named($value, [
                'start' => 1, 'flex-start' => 1, 'center' => 2,
                'end' => 3, 'flex-end' => 3, 'space-between' => 4,
                'space-around' => 5, 'space-evenly' => 6,
            ]),
            PropKey::TextAlign => self::named($value, ['start' => 1, 'center' => 2, 'end' => 3]),
            PropKey::DrawingMode => self::named($value, [
                'brush' => DrawingMode::Brush->value,
                'eraser' => DrawingMode::Eraser->value,
            ]),
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
            PropKey::InputAutoCapitalize => self::named($value, [
                'none' => InputAutoCapitalize::None->value,
                'sentences' => InputAutoCapitalize::Sentences->value,
                'words' => InputAutoCapitalize::Words->value,
                'characters' => InputAutoCapitalize::Characters->value,
            ]),
            PropKey::InputAutofillImportance => self::named($value, [
                'auto' => InputAutofillImportance::Auto->value,
                'no' => InputAutofillImportance::No->value,
                'noExcludeDescendants' =>
                    InputAutofillImportance::NoExcludeDescendants->value,
                'yes' => InputAutofillImportance::Yes->value,
                'yesExcludeDescendants' =>
                    InputAutofillImportance::YesExcludeDescendants->value,
            ]),
            PropKey::InputMode => self::named($value, [
                'text' => InputMode::Text->value,
                'none' => InputMode::None->value,
                'decimal' => InputMode::Decimal->value,
                'numeric' => InputMode::Numeric->value,
                'tel' => InputMode::Tel->value,
                'search' => InputMode::Search->value,
                'email' => InputMode::Email->value,
                'url' => InputMode::Url->value,
            ]),
            PropKey::InputSubmitBehavior => self::named($value, [
                'submit' => InputSubmitBehavior::Submit->value,
                'blurAndSubmit' => InputSubmitBehavior::BlurAndSubmit->value,
                'newline' => InputSubmitBehavior::Newline->value,
            ]),
            PropKey::InputTextAlignVertical => self::named($value, [
                'auto' => InputTextAlignVertical::Auto->value,
                'top' => InputTextAlignVertical::Top->value,
                'center' => InputTextAlignVertical::Center->value,
                'bottom' => InputTextAlignVertical::Bottom->value,
            ]),
            PropKey::ImageFit => self::named($value, [
                'cover' => ImageFit::Cover->value,
                'contain' => ImageFit::Contain->value,
                'fill' => ImageFit::Fill->value,
                'stretch' => ImageFit::Fill->value,
                'center' => ImageFit::Center->value,
                'repeat' => ImageFit::Repeat->value,
            ]),
            PropKey::ImageResizeMethod => self::named($value, [
                'auto' => ImageResizeMethod::Auto->value,
                'resize' => ImageResizeMethod::Resize->value,
                'scale' => ImageResizeMethod::Scale->value,
                'none' => ImageResizeMethod::None->value,
            ]),
            PropKey::ImageCachePolicy => self::named($value, [
                'default' => ImageCachePolicy::Default->value,
                'reload' => ImageCachePolicy::Reload->value,
                'force-cache' => ImageCachePolicy::ForceCache->value,
                'forceCache' => ImageCachePolicy::ForceCache->value,
                'only-if-cached' => ImageCachePolicy::OnlyIfCached->value,
                'onlyIfCached' => ImageCachePolicy::OnlyIfCached->value,
            ]),
            PropKey::ModalPresentation => self::named($value, [
                'fullScreen' => 1, 'dialog' => 2, 'sheet' => 3,
            ]),
            PropKey::ModalAnimationType => self::named($value, [
                'none' => ModalAnimationType::None->value,
                'slide' => ModalAnimationType::Slide->value,
                'fade' => ModalAnimationType::Fade->value,
            ]),
            PropKey::StatusBarStyle => self::named($value, ['dark' => 1, 'light' => 2]),
            PropKey::KeyboardBehavior => self::named($value, [
                'resize' => 1, 'pan' => 2, 'padding' => 3,
            ]),
            PropKey::Overflow => self::named($value, ['visible' => 1, 'hidden' => 2]),
            PropKey::FlexDirection => self::named($value, [
                'column' => 1,
                'row' => 2,
                'column-reverse' => 3,
                'row-reverse' => 4,
            ]),
            PropKey::FlexWrap => self::named($value, [
                'nowrap' => FlexWrap::NoWrap->value,
                'no-wrap' => FlexWrap::NoWrap->value,
                'wrap' => FlexWrap::Wrap->value,
            ]),
            PropKey::LayoutDirection => self::named($value, [
                'ltr' => LayoutDirection::LeftToRight->value,
                'rtl' => LayoutDirection::RightToLeft->value,
            ]),
            PropKey::GestureType => self::named($value, [
                'tap' => GestureType::Tap->value,
                'pan' => GestureType::Pan->value,
                'pinch' => GestureType::Pinch->value,
                'rotation' => GestureType::Rotation->value,
                'swipe' => GestureType::Swipe->value,
                'longPress' => GestureType::LongPress->value,
            ]),
            PropKey::GestureDirection => self::named($value, [
                'any' => GestureDirection::Any->value,
                'left' => GestureDirection::Left->value,
                'right' => GestureDirection::Right->value,
                'up' => GestureDirection::Up->value,
                'down' => GestureDirection::Down->value,
                'horizontal' => GestureDirection::Horizontal->value,
                'vertical' => GestureDirection::Vertical->value,
            ]),
            PropKey::GestureComposition => self::named($value, [
                'exclusive' => GestureComposition::Exclusive->value,
                'simultaneous' => GestureComposition::Simultaneous->value,
                'race' => GestureComposition::Race->value,
            ]),
            PropKey::PositionType => self::named($value, [
                'relative' => 1,
                'absolute' => 2,
            ]),
            PropKey::TextDecoration => self::named($value, [
                'none' => 1,
                'underline' => 2,
                'line-through' => 3,
                'underline-line-through' => 4,
            ]),
            PropKey::TextTransform => self::named($value, [
                'none' => 1,
                'uppercase' => 2,
                'lowercase' => 3,
                'capitalize' => 4,
            ]),
            PropKey::FontStyle => self::named($value, [
                'normal' => 1,
                'italic' => 2,
            ]),
            PropKey::PointerEvents => self::named($value, [
                'auto' => 1,
                'none' => 2,
                'box-none' => 3,
                'box-only' => 4,
            ]),
            PropKey::AccessibilityRole => self::named($value, [
                'generic' => AccessibilityRole::Generic->value,
                'button' => AccessibilityRole::Button->value,
                'input' => AccessibilityRole::Input->value,
                'image' => AccessibilityRole::Image->value,
                'img' => AccessibilityRole::Image->value,
                'switch' => AccessibilityRole::Switch->value,
                'adjustable' => AccessibilityRole::Adjustable->value,
                'slider' => AccessibilityRole::Adjustable->value,
                'alert' => AccessibilityRole::Alert->value,
                'checkbox' => AccessibilityRole::Checkbox->value,
                'combobox' => AccessibilityRole::ComboBox->value,
                'header' => AccessibilityRole::Header->value,
                'heading' => AccessibilityRole::Header->value,
                'imagebutton' => AccessibilityRole::ImageButton->value,
                'keyboardkey' => AccessibilityRole::KeyboardKey->value,
                'link' => AccessibilityRole::Link->value,
                'menu' => AccessibilityRole::Menu->value,
                'menubar' => AccessibilityRole::MenuBar->value,
                'menuitem' => AccessibilityRole::MenuItem->value,
                'none' => AccessibilityRole::None->value,
                'presentation' => AccessibilityRole::Presentation->value,
                'progressbar' => AccessibilityRole::ProgressBar->value,
                'radio' => AccessibilityRole::Radio->value,
                'radiogroup' => AccessibilityRole::RadioGroup->value,
                'scrollbar' => AccessibilityRole::ScrollBar->value,
                'search' => AccessibilityRole::Search->value,
                'searchbox' => AccessibilityRole::Search->value,
                'spinbutton' => AccessibilityRole::SpinButton->value,
                'summary' => AccessibilityRole::Summary->value,
                'tab' => AccessibilityRole::Tab->value,
                'tablist' => AccessibilityRole::TabList->value,
                'text' => AccessibilityRole::Text->value,
                'timer' => AccessibilityRole::Timer->value,
                'togglebutton' => AccessibilityRole::ToggleButton->value,
                'toolbar' => AccessibilityRole::Toolbar->value,
                'grid' => AccessibilityRole::Grid->value,
                'list' => AccessibilityRole::List->value,
                'listitem' => AccessibilityRole::ListItem->value,
            ]),
            PropKey::AccessibilityLiveRegion => self::named($value, [
                'none' => AccessibilityLiveRegion::None->value,
                'off' => AccessibilityLiveRegion::None->value,
                'polite' => AccessibilityLiveRegion::Polite->value,
                'assertive' => AccessibilityLiveRegion::Assertive->value,
            ]),
            PropKey::AccessibilityImportance => self::named($value, [
                'auto' => AccessibilityImportance::Auto->value,
                'yes' => AccessibilityImportance::Yes->value,
                'no' => AccessibilityImportance::No->value,
                'no-hide-descendants' => AccessibilityImportance::NoHideDescendants->value,
            ]),
            PropKey::AccessibilityCheckedState => is_bool($value)
                ? (
                    $value
                        ? AccessibilityCheckedState::Checked->value
                        : AccessibilityCheckedState::Unchecked->value
                )
                : self::named($value, [
                    'false' => AccessibilityCheckedState::Unchecked->value,
                    'unchecked' => AccessibilityCheckedState::Unchecked->value,
                    'true' => AccessibilityCheckedState::Checked->value,
                    'checked' => AccessibilityCheckedState::Checked->value,
                    'mixed' => AccessibilityCheckedState::Mixed->value,
                ]),
            PropKey::ReturnKeyType => self::named($value, [
                'default' => ReturnKeyType::Default->value,
                'done' => ReturnKeyType::Done->value,
                'go' => ReturnKeyType::Go->value,
                'next' => ReturnKeyType::Next->value,
                'search' => ReturnKeyType::Search->value,
                'send' => ReturnKeyType::Send->value,
                'none' => ReturnKeyType::None->value,
                'previous' => ReturnKeyType::Previous->value,
            ]),
            PropKey::SafeAreaMode => self::named($value, [
                'padding' => SafeAreaMode::Padding->value,
                'margin' => SafeAreaMode::Margin->value,
            ]),
            PropKey::ScrollOverScrollMode => self::named($value, [
                'auto' => ScrollOverScrollMode::Auto->value,
                'always' => ScrollOverScrollMode::Always->value,
                'never' => ScrollOverScrollMode::Never->value,
            ]),
            PropKey::ScrollKeyboardDismissMode => self::named($value, [
                'none' => ScrollKeyboardDismissMode::None->value,
                'on-drag' => ScrollKeyboardDismissMode::OnDrag->value,
                'interactive' => ScrollKeyboardDismissMode::Interactive->value,
            ]),
            PropKey::ActivitySize => match ($value) {
                'small' => ActivityIndicatorSize::Small
                    ->densityIndependentPixels(),
                'large' => ActivityIndicatorSize::Large
                    ->densityIndependentPixels(),
                default => self::floatValue($value, 'ActivityIndicator size'),
            },
            PropKey::RefreshIndicatorSize => self::named($value, [
                'default' => RefreshIndicatorSize::Default->value,
                'large' => RefreshIndicatorSize::Large->value,
            ]),
            PropKey::TextEllipsizeMode => self::named($value, [
                'tail' => TextEllipsizeMode::Tail->value,
                'head' => TextEllipsizeMode::Head->value,
                'middle' => TextEllipsizeMode::Middle->value,
                'clip' => TextEllipsizeMode::Clip->value,
            ]),
            PropKey::TextBreakStrategy => self::named($value, [
                'highQuality' => TextBreakStrategy::HighQuality->value,
                'high-quality' => TextBreakStrategy::HighQuality->value,
                'simple' => TextBreakStrategy::Simple->value,
                'balanced' => TextBreakStrategy::Balanced->value,
            ]),
            PropKey::TextHyphenationFrequency => self::named($value, [
                'none' => TextHyphenationFrequency::None->value,
                'normal' => TextHyphenationFrequency::Normal->value,
                'full' => TextHyphenationFrequency::Full->value,
            ]),
            PropKey::TextDataDetectorType => self::named($value, [
                'none' => TextDataDetectorType::None->value,
                'phoneNumber' => TextDataDetectorType::PhoneNumber->value,
                'phone-number' => TextDataDetectorType::PhoneNumber->value,
                'link' => TextDataDetectorType::Link->value,
                'email' => TextDataDetectorType::Email->value,
                'all' => TextDataDetectorType::All->value,
            ]),
            PropKey::AnimationKind => self::named($value, [
                'none' => AnimationKind::None->value,
                'pulse' => AnimationKind::Pulse->value,
            ]),
            PropKey::GridColumns,
            PropKey::GridSpan,
            PropKey::GridSpanSm,
            PropKey::GridSpanMd,
            PropKey::GridSpanLg,
            PropKey::GridSpanXl,
            => max(1, min(64, (int) self::floatValue($value, "Grid {$key->name}"))),
            PropKey::GridOffset,
            PropKey::GridOffsetSm,
            PropKey::GridOffsetMd,
            PropKey::GridOffsetLg,
            PropKey::GridOffsetXl,
            PropKey::GridOrder,
            PropKey::GridOrderSm,
            PropKey::GridOrderMd,
            PropKey::GridOrderLg,
            PropKey::GridOrderXl,
            => max(0, (int) self::floatValue($value, "Grid {$key->name}")),
            PropKey::GridColumnGap,
            PropKey::GridRowGap,
            => max(0.0, self::floatValue($value, "Grid {$key->name}")),
            PropKey::PressAndroidDisableSound,
            PropKey::RippleBorderless,
            PropKey::RippleForeground,
            PropKey::ScrollAnchorToEnd,
            PropKey::ScrollMaintainVisibleContentPosition,
            => self::boolValue($value, "Pressable {$key->name}"),
            PropKey::PressDelayLongMs,
            PropKey::PressDelayInMs,
            PropKey::PressDelayOutMs,
            PropKey::ScrollRequest,
            PropKey::DrawingClearRequest,
            PropKey::DrawingUndoRequest,
            => min(
                60_000,
                max(0, (int) self::floatValue($value, "Pressable {$key->name}")),
            ),
            PropKey::HitSlop,
            PropKey::HitSlopLeft,
            PropKey::HitSlopTop,
            PropKey::HitSlopRight,
            PropKey::HitSlopBottom,
            PropKey::PressRetentionLeft,
            PropKey::PressRetentionTop,
            PropKey::PressRetentionRight,
            PropKey::PressRetentionBottom,
            PropKey::RippleRadius,
            PropKey::ScrollAutoScrollToEndThreshold,
            PropKey::DrawingWidth,
            => max(0.0, self::floatValue($value, "Pressable {$key->name}")),
            PropKey::RippleAlpha => min(
                1.0,
                max(0.0, self::floatValue($value, 'Pressable ripple alpha')),
            ),
            default => is_string($value) || is_int($value) || is_float($value) || is_bool($value)
                ? $value
                : null,
        };
    }

    private static function colorValue(mixed $value, string $context): int
    {
        if (is_int($value)) {
            return $value;
        }
        $raw = self::stringValue($value, $context);
        if (preg_match('/^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$/D', $raw, $color) !== 1) {
            throw new InvalidArgumentException(
                "{$context} must be an integer or #RRGGBB/#AARRGGBB color.",
            );
        }
        $hex = strlen($color[1]) === 6 ? 'FF'.$color[1] : $color[1];

        return (int) hexdec($hex);
    }

    private static function activityIndicatorSize(
        mixed $value,
    ): ActivityIndicatorSize|float {
        return match ($value) {
            'small' => ActivityIndicatorSize::Small,
            'large' => ActivityIndicatorSize::Large,
            default => max(
                1.0,
                self::floatValue($value, 'ActivityIndicator size'),
            ),
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

    private static function gestureType(mixed $value): GestureType
    {
        return GestureType::from(self::named($value, [
            'tap' => GestureType::Tap->value,
            'pan' => GestureType::Pan->value,
            'pinch' => GestureType::Pinch->value,
            'rotation' => GestureType::Rotation->value,
            'swipe' => GestureType::Swipe->value,
            'longPress' => GestureType::LongPress->value,
        ]));
    }

    /** @param array<string, mixed> $data */
    private static function classes(
        Element $element,
        string $classes,
        array $data,
    ): Element
    {
        $localClasses = self::styleSheetClasses($data);
        foreach (preg_split('/\\s+/', trim($classes)) ?: [] as $class) {
            if ($class === '') {
                continue;
            }
            if (isset($localClasses[$class])) {
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

    /**
     * @return array{
     *     classes: array<string, array<string, string|bool>>,
     *     tags: array<string, array<string, string|bool>>,
     *     fonts: array<string, list<array{source: string, weight: string, style: string}>>
     * }
     */
    private static function styleSheet(CompiledTemplateNode $tree): array
    {
        $encoded = $tree->attributes['__pamStyles'] ?? null;
        if (!is_string($encoded) || $encoded === '') {
            return ['classes' => [], 'tags' => [], 'fonts' => []];
        }
        try {
            $decoded = json_decode($encoded, true, 64, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new RuntimeException(
                "Invalid compiled styles in {$tree->source}.",
                previous: $error,
            );
        }
        if (!is_array($decoded)) {
            throw new RuntimeException("Invalid compiled styles in {$tree->source}.");
        }
        $classes = self::validatedStyleRules($decoded['classes'] ?? null, $tree->source);
        $tags = self::validatedStyleRules($decoded['tags'] ?? null, $tree->source);
        $fonts = self::validatedFontFaces($decoded['fonts'] ?? [], $tree->source);

        return ['classes' => $classes, 'tags' => $tags, 'fonts' => $fonts];
    }

    /**
     * @param array<string, mixed> $data
     * @return array<string, array<string, string|bool>>
     */
    private static function styleSheetClasses(array $data): array
    {
        $sheet = $data['__pamStyles'] ?? null;
        $classes = is_array($sheet) ? ($sheet['classes'] ?? null) : null;

        return is_array($classes) ? $classes : [];
    }

    /**
     * @param array<string, mixed> $data
     * @return array<string, string|bool>
     */
    private static function scopedStyleAttributes(
        string $tag,
        ?string $classes,
        array $data,
    ): array {
        $sheet = $data['__pamStyles'] ?? null;
        if (!is_array($sheet)) {
            return [];
        }
        $tags = is_array($sheet['tags'] ?? null) ? $sheet['tags'] : [];
        $classRules = is_array($sheet['classes'] ?? null) ? $sheet['classes'] : [];
        $attributes = is_array($tags[$tag] ?? null) ? $tags[$tag] : [];
        foreach (preg_split('/\s+/', trim($classes ?? '')) ?: [] as $class) {
            if ($class !== '' && is_array($classRules[$class] ?? null)) {
                $attributes = [...$attributes, ...$classRules[$class]];
            }
        }

        $fonts = is_array($sheet['fonts'] ?? null) ? $sheet['fonts'] : [];

        return self::resolveScopedFont($attributes, $fonts);
    }

    /**
     * @param array<string, string|bool> $attributes
     * @param array<string, list<array{source: string, weight: string, style: string}>> $fonts
     * @return array<string, string|bool>
     */
    private static function resolveScopedFont(array $attributes, array $fonts): array
    {
        $family = $attributes['fontFamily'] ?? null;
        if (!is_string($family) || !isset($fonts[$family])) {
            return $attributes;
        }
        $wantedWeight = (int) ($attributes['fontWeight'] ?? '400');
        $wantedStyle = (string) ($attributes['fontStyle'] ?? 'normal');
        $best = null;
        $bestScore = PHP_INT_MAX;
        $exact = false;
        foreach ($fonts[$family] as $face) {
            $weight = (int) $face['weight'];
            $stylePenalty = $face['style'] === $wantedStyle ? 0 : 10_000;
            $score = $stylePenalty + abs($weight - $wantedWeight);
            if ($score < $bestScore) {
                $best = $face;
                $bestScore = $score;
            }
            if ($score === 0) {
                $exact = true;
                break;
            }
        }
        if ($best === null) {
            return $attributes;
        }
        $attributes['fontFamily'] = $best['source'];
        if ($exact) {
            unset($attributes['fontWeight'], $attributes['fontStyle']);
        }

        return $attributes;
    }

    /**
     * @return array<string, array<string, string|bool>>
     */
    private static function validatedStyleRules(mixed $rules, string $source): array
    {
        if (!is_array($rules)) {
            throw new RuntimeException("Invalid compiled styles in {$source}.");
        }
        $validated = [];
        foreach ($rules as $selector => $attributes) {
            if (!is_string($selector) || !is_array($attributes)) {
                throw new RuntimeException("Invalid compiled style rule in {$source}.");
            }
            foreach ($attributes as $name => $value) {
                if (
                    !is_string($name)
                    || (!is_string($value) && !is_bool($value))
                ) {
                    throw new RuntimeException("Invalid compiled style value in {$source}.");
                }
            }
            /** @var array<string, string|bool> $attributes */
            $validated[$selector] = $attributes;
        }

        return $validated;
    }

    /**
     * @return array<string, list<array{source: string, weight: string, style: string}>>
     */
    private static function validatedFontFaces(mixed $fonts, string $source): array
    {
        if (!is_array($fonts)) {
            throw new RuntimeException("Invalid compiled fonts in {$source}.");
        }
        $validated = [];
        foreach ($fonts as $family => $faces) {
            if (!is_string($family) || $family === '' || !is_array($faces)) {
                throw new RuntimeException("Invalid compiled font family in {$source}.");
            }
            foreach ($faces as $face) {
                if (
                    !is_array($face)
                    || !is_string($face['source'] ?? null)
                    || !is_string($face['weight'] ?? null)
                    || !is_string($face['style'] ?? null)
                ) {
                    throw new RuntimeException("Invalid compiled font face in {$source}.");
                }
                $validated[$family][] = [
                    'source' => $face['source'],
                    'weight' => $face['weight'],
                    'style' => $face['style'],
                ];
            }
        }

        return $validated;
    }

    /** @return array{PropKey, int|float}|null */
    private static function utility(string $class): ?array
    {
        $fixed = [
            'flex-1' => [PropKey::FlexGrow, 1.0],
            'w-full' => [PropKey::WidthPercent, 100.0],
            'h-full' => [PropKey::HeightPercent, 100.0],
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

        if (preg_match('/^grid-(\\d{1,2})$/', $class, $match) === 1) {
            return [PropKey::GridColumns, max(1, min(64, (int) $match[1]))];
        }

        if (preg_match('/^col(?:(-sm|-md|-lg|-xl))?-(\\d{1,2})$/', $class, $match) === 1) {
            $key = match ($match[1] ?? '') {
                '-sm' => PropKey::GridSpanSm,
                '-md' => PropKey::GridSpanMd,
                '-lg' => PropKey::GridSpanLg,
                '-xl' => PropKey::GridSpanXl,
                default => PropKey::GridSpan,
            };

            return [$key, max(1, min(64, (int) $match[2]))];
        }

        if (preg_match('/^(offset|order)(?:(-sm|-md|-lg|-xl))?-(\\d+)$/', $class, $match) === 1) {
            $keys = $match[1] === 'offset'
                ? [
                    '' => PropKey::GridOffset,
                    '-sm' => PropKey::GridOffsetSm,
                    '-md' => PropKey::GridOffsetMd,
                    '-lg' => PropKey::GridOffsetLg,
                    '-xl' => PropKey::GridOffsetXl,
                ]
                : [
                    '' => PropKey::GridOrder,
                    '-sm' => PropKey::GridOrderSm,
                    '-md' => PropKey::GridOrderMd,
                    '-lg' => PropKey::GridOrderLg,
                    '-xl' => PropKey::GridOrderXl,
                ];

            return [$keys[$match[2] ?? ''], (int) $match[3]];
        }

        if (preg_match('/^gutter-(x|y)-(\\d+(?:\\.\\d+)?)$/', $class, $match) === 1) {
            return [
                $match[1] === 'x' ? PropKey::GridColumnGap : PropKey::GridRowGap,
                (float) $match[2] * 4.0,
            ];
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
     * @param array<string, string|bool> $attributes
     * @return array<string, string|bool>
     */
    private static function nativeEventAliases(array $attributes): array
    {
        foreach (self::EVENTS as $native => $_kind) {
            $alias = '@'.substr($native, 3);
            if (array_key_exists($alias, $attributes)) {
                $attributes[$native] ??= $attributes[$alias];
                unset($attributes[$alias]);
            }
        }

        return $attributes;
    }

    /**
     * @param list<CompiledTemplateNode> $nodes
     * @return array{
     *     list<CompiledTemplateNode>,
     *     array<string, list<CompiledTemplateNode>>
     * }
     */
    private static function componentSlotNodes(array $nodes): array
    {
        $default = [];
        $slots = [];

        foreach ($nodes as $node) {
            if (
                $node->kind !== 1
                || strtolower($node->name) !== 'template'
            ) {
                $default[] = $node;
                continue;
            }

            $slotName = null;
            foreach ($node->attributes as $name => $value) {
                if (str_starts_with($name, '#')) {
                    $slotName = substr($name, 1);
                    break;
                }
                if ($name === 'slot' && is_string($value)) {
                    $slotName = $value;
                    break;
                }
            }
            if (
                $slotName === null
                || preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $slotName)
                    !== 1
            ) {
                throw new RuntimeException(
                    'Named component templates require #slot or slot="name".',
                );
            }
            if (isset($slots[$slotName])) {
                throw new RuntimeException("Duplicate named slot {$slotName}.");
            }
            $slots[$slotName] = $node->children;
        }

        return [$default, $slots];
    }

    /**
     * @param array<string, string|bool> $attributes
     * @param array<string, mixed> $data
     */
    private static function classValue(
        array $attributes,
        ?object $scope,
        array $data,
    ): ?string {
        $classes = [];
        $static = $attributes['class'] ?? null;

        if (is_string($static)) {
            $classes[] = self::interpolate($static, $scope, $data);
        }
        if (array_key_exists(':class', $attributes)) {
            $dynamic = self::dynamicValue(
                $attributes[':class'],
                $scope,
                $data,
            );
            if (is_string($dynamic)) {
                $classes[] = $dynamic;
            } elseif (is_array($dynamic)) {
                foreach ($dynamic as $class => $enabled) {
                    if (is_int($class)) {
                        if (!is_string($enabled)) {
                            throw new RuntimeException(
                                'Numeric dynamic class entries must contain class names.',
                            );
                        }
                        $classes[] = $enabled;
                    } elseif ((bool) $enabled) {
                        $classes[] = $class;
                    }
                }
            } elseif ($dynamic !== null && $dynamic !== false) {
                throw new RuntimeException(
                    ':class must resolve to a string, array, false or null.',
                );
            }
        }

        $value = trim(implode(' ', array_filter(
            $classes,
            static fn (string $class): bool => trim($class) !== '',
        )));

        return $value === '' ? null : $value;
    }

    /**
     * @param array<string, mixed> $data
     */
    private static function componentHandler(
        string|bool $raw,
        ?object $scope,
        array $data,
    ): Closure {
        if ($scope === null || !is_string($raw)) {
            throw new RuntimeException(
                'Component events require a method or expression on the parent component.',
            );
        }
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/D', $raw) === 1) {
            if (!method_exists($scope, $raw)) {
                throw new RuntimeException(
                    "Component event handler {$raw} does not exist.",
                );
            }
            $method = new ReflectionMethod($scope, $raw);
            if (!$method->isPublic()) {
                throw new RuntimeException(
                    "Component event handler {$raw} must be public.",
                );
            }

            return static function (mixed $payload = null) use (
                $method,
                $scope,
            ): void {
                if ($method->getNumberOfParameters() === 0) {
                    $method->invoke($scope);
                } else {
                    $method->invoke($scope, $payload);
                }
            };
        }

        return static function (mixed $payload = null) use (
            $raw,
            $scope,
            $data,
        ): void {
            TemplateExpression::evaluate(
                $raw,
                $scope,
                [...$data, 'event' => $payload],
            );
        };
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
        if (
            is_string($raw)
            && preg_match('/^[A-Za-z_][A-Za-z0-9_]*\s*\(/D', $raw) === 1
        ) {
            if ($scope === null) {
                throw new RuntimeException(
                    'Template event expressions require a component scope.',
                );
            }

            return static function (mixed $payload = '') use (
                $raw,
                $scope,
                $data,
            ): void {
                TemplateExpression::evaluate(
                    $raw,
                    $scope,
                    [...$data, 'event' => $payload],
                );
            };
        }

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

        return static function (mixed $payload = '') use ($kind, $method, $scope): void {
            if ($method->getNumberOfParameters() === 0) {
                $method->invoke($scope);
                return;
            }

            if (
                is_string($payload)
                && in_array($kind, [
                    EventKind::GestureBegin,
                    EventKind::GestureUpdate,
                    EventKind::GestureEnd,
                    EventKind::GestureCancel,
                ], true)
            ) {
                $value = GestureEvent::fromPayload($payload);
            } elseif ($kind === EventKind::Toggle) {
                $value = $payload === true || $payload === '1';
            } elseif (is_array($payload)) {
                $value = $payload;
            } elseif (
                is_string($payload)
                || is_int($payload)
                || is_float($payload)
                || is_bool($payload)
                || $payload === null
                || $payload instanceof Stringable
            ) {
                $value = (string) $payload;
            } elseif (is_object($payload)) {
                $value = $payload;
            } else {
                throw new RuntimeException(
                    'Template event payload must be scalar, stringable, an object, or an array.',
                );
            }
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

        return static function (string $value) use ($property, $reflection, $scope): void {
            $previous = $reflection->getValue($scope);
            if ($previous === $value) {
                return;
            }
            if ($scope instanceof Component) {
                $scope->__pamNotifyUpdating($property, $value, $previous);
            }
            $reflection->setValue($scope, $value);
            if ($scope instanceof Component) {
                $scope->__pamNotifyUpdated($property);
                $scope->__pamFlushChanges();
            }
        };
    }

    private static function checkedModelHandler(
        string $model,
        ?object $scope,
    ): Closure {
        $property = ltrim($model, '$');
        if (
            $scope === null
            || preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/D', $property) !== 1
            || !property_exists($scope, $property)
        ) {
            throw new RuntimeException(
                "Template checked binding {$model} does not resolve to component state.",
            );
        }
        $reflection = self::reflectionProperty($scope, $property);
        if ($reflection->isReadOnly()) {
            throw new RuntimeException(
                "Template checked binding {$model} cannot write a readonly property.",
            );
        }

        return static function (mixed $payload) use ($property, $reflection, $scope): void {
            $value = $payload === true || $payload === 1 || $payload === '1';
            $previous = $reflection->getValue($scope);
            if ($previous === $value) {
                return;
            }
            if ($scope instanceof Component) {
                $scope->__pamNotifyUpdating($property, $value, $previous);
            }
            $reflection->setValue($scope, $value);
            if ($scope instanceof Component) {
                $scope->__pamNotifyUpdated($property);
                $scope->__pamFlushChanges();
            }
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
     * @param array<string, string|bool> $attributes
     * @return array<string, string|bool>
     */
    private static function directiveAliases(array $attributes): array
    {
        foreach ([
            'v-if' => 'p-if',
            'v-else-if' => 'p-else-if',
            'v-else' => 'p-else',
            'v-for' => 'p-for',
        ] as $legacy => $native) {
            if (!array_key_exists($legacy, $attributes)) {
                continue;
            }
            if (
                array_key_exists($native, $attributes)
                && $attributes[$native] !== $attributes[$legacy]
            ) {
                throw new RuntimeException(
                    "Template directives {$native} and {$legacy} cannot disagree.",
                );
            }
            $attributes[$native] ??= $attributes[$legacy];
            unset($attributes[$legacy]);
        }

        return $attributes;
    }

    /**
     * @param array<string, string|bool> $attributes
     */
    private static function withAttributes(
        CompiledTemplateNode $node,
        array $attributes,
    ): CompiledTemplateNode {
        $copy = new CompiledTemplateNode(
            kind: $node->kind,
            name: $node->name,
            attributes: $attributes,
            source: $node->source,
            line: $node->line,
            column: $node->column,
            value: $node->value,
        );
        $copy->children = $node->children;

        return $copy;
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
            default => (
                preg_match(
                    '/^[A-Za-z_][A-Za-z0-9_]*\s*\(/D',
                    $raw,
                ) === 1
                    ? TemplateExpression::evaluate($raw, $scope, $data)
                    : self::literal($raw, $scope, $data)
            ),
        };
    }

    /** @param array<string, mixed> $data */
    private static function dynamicValue(
        mixed $raw,
        ?object $scope,
        array $data,
    ): mixed {
        if (!is_string($raw)) {
            return $raw;
        }

        return TemplateExpression::evaluate($raw, $scope, $data);
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
        return TemplateExpression::interpolate($value, $scope, $data);
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

    private static function isDeclarativeContextValue(
        mixed $value,
        int $depth = 0,
    ): bool {
        if (is_scalar($value)) {
            return true;
        }
        if ($value === null) {
            return $depth > 0;
        }
        if (
            !is_array($value)
            || $depth >= self::MAX_CONTEXT_DEPTH
            || count($value) > self::MAX_CONTEXT_ITEMS
        ) {
            return false;
        }

        foreach ($value as $key => $item) {
            if (
                !is_int($key)
                && preg_match('/^[A-Za-z][A-Za-z0-9_-]{0,127}$/D', $key) !== 1
            ) {
                return false;
            }
            if (!self::isDeclarativeContextValue($item, $depth + 1)) {
                return false;
            }
        }

        return true;
    }

    private const int MAX_CONTEXT_DEPTH = 4;
    private const int MAX_CONTEXT_ITEMS = 1_024;

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
    private static function scrollViewContent(
        array $children,
        bool $horizontal,
    ): Element {
        if ($children === []) {
            throw new RuntimeException(
                'ScrollView requires at least one child.',
            );
        }

        return $horizontal
            ? Row::make(...$children)
            : Column::make(...$children);
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

    /** @return list<float> */
    private static function bottomSheetSnapPoints(mixed $value): array
    {
        if (!is_array($value)) {
            throw new InvalidArgumentException('BottomSheet snapPoints must be an array.');
        }

        return array_values(array_map(
            static function (mixed $point): float {
                if (!is_int($point) && !is_float($point)) {
                    throw new InvalidArgumentException(
                        'BottomSheet snapPoints must contain only numbers.',
                    );
                }

                return (float) $point;
            },
            $value,
        ));
    }

    private static function bottomSheetKeyboardBehavior(
        mixed $value,
    ): BottomSheetKeyboardBehavior {
        return match ($value) {
            2, 'extend' => BottomSheetKeyboardBehavior::Extend,
            3, 'fillParent' => BottomSheetKeyboardBehavior::FillParent,
            default => BottomSheetKeyboardBehavior::Interactive,
        };
    }

    /** @return list<NativeMenuItem> */
    private static function nativeMenuItems(mixed $value): array
    {
        if (!is_array($value)) {
            throw new InvalidArgumentException('InteractionRegion menuItems must be an array.');
        }

        return array_values(array_map(
            static function (mixed $item): NativeMenuItem {
                if (!is_array($item)) {
                    throw new InvalidArgumentException('Each native menu item must be an array.');
                }

                return new NativeMenuItem(
                    id: self::stringValue($item['id'] ?? '', 'Native menu item id'),
                    title: self::stringValue($item['title'] ?? '', 'Native menu item title'),
                    destructive: self::boolValue(
                        $item['destructive'] ?? false,
                        'Native menu item destructive',
                    ),
                    disabled: self::boolValue(
                        $item['disabled'] ?? false,
                        'Native menu item disabled',
                    ),
                );
            },
            $value,
        ));
    }

    /** @return list<AnimationKeyframe> */
    private static function animationKeyframes(mixed $value): array
    {
        if (!is_array($value)) {
            throw new InvalidArgumentException('Animated keyframes must be an array.');
        }

        return array_values(array_map(
            static function (mixed $frame): AnimationKeyframe {
                if (!is_array($frame)) {
                    throw new InvalidArgumentException('Each animation keyframe must be an array.');
                }
                $number = static fn (string $key): ?float =>
                    array_key_exists($key, $frame)
                        ? self::floatValue($frame[$key], "Animation {$key}")
                        : null;

                return new AnimationKeyframe(
                    offset: self::floatValue($frame['offset'] ?? -1, 'Animation offset'),
                    opacity: $number('opacity'),
                    translationX: $number('translationX'),
                    translationY: $number('translationY'),
                    scaleX: $number('scaleX'),
                    scaleY: $number('scaleY'),
                    rotation: $number('rotation'),
                );
            },
            $value,
        ));
    }

    private static function animationEasing(mixed $value): AnimationEasing
    {
        return match ($value) {
            1, 'linear' => AnimationEasing::Linear,
            2, 'easeIn' => AnimationEasing::EaseIn,
            3, 'easeOut' => AnimationEasing::EaseOut,
            5, 'spring' => AnimationEasing::Spring,
            default => AnimationEasing::EaseInOut,
        };
    }

    private static function animationFillMode(mixed $value): AnimationFillMode
    {
        return match ($value) {
            1, 'none' => AnimationFillMode::None,
            3, 'backwards' => AnimationFillMode::Backwards,
            4, 'both' => AnimationFillMode::Both,
            default => AnimationFillMode::Forwards,
        };
    }

    private static function animationPlayState(mixed $value): AnimationPlayState
    {
        return match ($value) {
            2, 'paused' => AnimationPlayState::Paused,
            3, 'stopped' => AnimationPlayState::Stopped,
            default => AnimationPlayState::Running,
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
