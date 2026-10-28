<?php

declare(strict_types=1);

namespace TestingApp\Screens;

use Pam\Native\Component;
use Pam\Native\Composables\ColorScheme;
use Pam\Native\Composables\KeyboardState;
use Pam\Native\Composables\LocaleState;
use Pam\Native\Composables\SafeAreaInsets;
use Pam\Native\Renderable;
use Pam\Native\Signals\Signal;
use Pam\Native\UI\Text;

use function Pam\Native\onBlur;
use function Pam\Native\onFocus;
use function Pam\Native\onMounted;
use function Pam\Native\onUnmounted;
use function Pam\Native\signal;
use function Pam\Native\useColorScheme;
use function Pam\Native\useKeyboard;
use function Pam\Native\useLocale;
use function Pam\Native\useSafeAreaInsets;

final class ChatScreen extends Component
{
    public string $id = '';
    public ?Signal $draft = null;
    public ?ColorScheme $scheme = null;
    public ?SafeAreaInsets $insets = null;
    public ?KeyboardState $keyboard = null;
    public ?LocaleState $locale = null;

    public function __construct(public string $title = 'Chat')
    {
    }

    public function setup(string $id = '0'): void
    {
        $this->id = $id;
        $this->draft = signal('');
        $this->scheme = useColorScheme();
        $this->insets = useSafeAreaInsets();
        $this->keyboard = useKeyboard();
        $this->locale = useLocale();
        onMounted(fn () => Log::$entries[] = "chat{$id}:mounted");
        onUnmounted(fn () => Log::$entries[] = "chat{$id}:unmounted");
        onFocus(fn () => Log::$entries[] = "chat{$id}:focus");
        onBlur(fn () => Log::$entries[] = "chat{$id}:blur");
        Log::$entries[] = "chat{$id}:setup";
    }

    public function render(): Renderable
    {
        return Text::make(sprintf(
            '%s %s draft=%s scheme=%s bottom=%s keyboard=%s locale=%s',
            $this->title,
            $this->id,
            $this->draft?->value,
            $this->scheme?->value,
            $this->insets?->bottom,
            $this->keyboard?->height,
            $this->locale?->value,
        ));
    }
}
