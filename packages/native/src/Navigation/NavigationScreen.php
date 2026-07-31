<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\AccessibilityRole;
use Pam\Native\Align;
use Pam\Native\Renderable;
use Pam\Native\Style;
use Pam\Native\UI\Column;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\UI\Input;
use Pam\Native\ReturnKeyType;

final readonly class NavigationScreen implements Renderable
{
    public function __construct(
        private Renderable $content,
        private ScreenOptions $options,
        private NavigationTheme $theme,
        private bool $canGoBack,
        private \Closure $goBack,
    ) {
    }

    public function toElement(): \Pam\Native\Element
    {
        // Standard chrome is owned by UINavigationController/Fragment. PAM
        // content remains the fallback for custom renderable header slots.
        $hasCustomHeader = $this->options->headerTitle !== null
            || $this->options->headerLeft !== null
            || $this->options->headerRight !== null;
        $body = $this->options->headerShown && $hasCustomHeader
            ? $this->withHeader()
            : $this->content;

        // Presentation belongs to the retained native route controller. This
        // keeps modal/sheet lifecycle, gestures and accessibility inside the
        // platform rather than nesting a second PAM modal host in the route.
        return $body->toElement();
    }

    private function withHeader(): Renderable
    {

        $title = $this->options->headerTitle ?? Text::make($this->options->title ?? '')->numberOfLines(1)->style(new Style(
            flexGrow: 1.0,
            fontSize: $this->options->headerLargeTitleEnabled ? 28.0 : 17.0,
            fontWeight: $this->options->headerLargeTitleEnabled ? 700 : 600,
            textColor: $this->options->headerTintColor ?? $this->theme->text,
        ));
        $children = [];
        if ($this->options->headerLeft !== null) {
            $children[] = $this->options->headerLeft;
        } elseif ($this->canGoBack) {
            $children[] = Pressable::make(
                Text::make('‹')->style(new Style(
                    width: 44.0,
                    fontSize: 34.0,
                    lineHeight: 40.0,
                    textColor: $this->options->headerTintColor ?? $this->theme->primary,
                )),
            )
                ->onPress($this->goBack)
                ->accessibilityRole(AccessibilityRole::Button)
                ->accessibilityLabel('Back')
                ->hitSlop(8.0)
                ->style(new Style(width: 44.0, minHeight: 44.0));
        }
        $children[] = $title;
        if ($this->options->headerRight !== null) $children[] = $this->options->headerRight;

        $headerContents = [Row::make(...$children)->style(new Style(
                minHeight: $this->options->headerLargeTitleEnabled ? 76.0 : 56.0,
                paddingHorizontal: 12.0,
                alignItems: Align::Center,
                backgroundColor: $this->options->headerTransparent
                    ? 0x00000000
                    : ($this->options->headerBackgroundColor ?? $this->theme->card),
                borderBottomWidth: $this->options->headerShadowVisible ? 1.0 : 0.0,
                borderColor: $this->theme->border,
            ))];
        if ($this->options->headerSearchEnabled) {
            $search = Input::make()
                ->placeholder($this->options->headerSearchPlaceholder)
                ->returnKey(ReturnKeyType::Search)
                ->style(new Style(
                    minHeight: 44.0,
                    marginHorizontal: 12.0,
                    marginBottom: 8.0,
                    paddingHorizontal: 12.0,
                    borderRadius: 12.0,
                    backgroundColor: $this->theme->background,
                    textColor: $this->theme->text,
                ));
            if ($this->options->onHeaderSearchChange !== null) {
                $search = $search->onChange($this->options->onHeaderSearchChange);
            }
            $headerContents[] = $search;
        }
        $header = SafeAreaView::make(
            Column::make(...$headerContents)->style(new Style(
                backgroundColor: $this->options->headerTransparent
                    ? 0x00000000
                    : ($this->options->headerBackgroundColor ?? $this->theme->card),
            )),
        )->edges(top: true, right: true, bottom: false, left: true);

        return Column::make(
            $header,
            View::make($this->content)->style(new Style(flexGrow: 1.0)),
        )->style(new Style(flexGrow: 1.0));
    }
}
