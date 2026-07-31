<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\AccessibilityRole;
use Pam\Native\Align;
use Pam\Native\Renderable;
use Pam\Native\Style;
use Pam\Native\ModalPresentation;
use Pam\Native\ModalAnimationType;
use Pam\Native\UI\BottomSheet;
use Pam\Native\UI\Column;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\UI\Modal;
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
        $body = $this->options->headerShown
            ? $this->withHeader()
            : $this->content;

        return match ($this->options->presentation) {
            NavigationPresentation::Card => $body->toElement(),
            NavigationPresentation::FormSheet => BottomSheet::make(
                $body,
                $this->options->sheetAllowedDetents ?? [1.0],
                $this->options->sheetInitialDetentIndex - 1,
            )
                ->handleVisible($this->options->sheetGrabberVisible)
                ->cornerRadius($this->options->sheetCornerRadius ?? 20.0)
                ->onDismiss($this->goBack)
                ->toElement(),
            NavigationPresentation::Modal,
            NavigationPresentation::ContainedModal => Modal::make(
                $body,
                presentation: ModalPresentation::Dialog,
            )
                ->animationType(ModalAnimationType::Slide)
                ->allowSwipeDismissal($this->options->gestureEnabled)
                ->onRequestClose($this->goBack)
                ->toElement(),
            NavigationPresentation::FullScreenModal => Modal::make(
                $body,
                presentation: ModalPresentation::FullScreen,
            )
                ->animationType(ModalAnimationType::Slide)
                ->onRequestClose($this->goBack)
                ->toElement(),
            NavigationPresentation::TransparentModal,
            NavigationPresentation::ContainedTransparentModal => Modal::make(
                $body,
                presentation: ModalPresentation::FullScreen,
            )
                ->transparent()
                ->animationType(ModalAnimationType::Fade)
                ->allowSwipeDismissal($this->options->gestureEnabled)
                ->onRequestClose($this->goBack)
                ->toElement(),
        };
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
