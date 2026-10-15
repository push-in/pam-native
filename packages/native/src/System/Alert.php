<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

use function count;

final class Alert
{
    /** `Alert::choose()` index when the alert closed without a button (back, outside tap, host closed). */
    public const DISMISSED = -1;

    public const MAX_BUTTONS = 3;

    private function __construct()
    {
    }

    public static function show(string $title, string $message, ?Closure $dismissed = null): int
    {
        return Runtime::callNative(
            NativeOperation::Alert,
            Wire::map(['title' => $title, 'message' => $message]),
            static function (ModuleResultStatus $status, string $payload) use ($dismissed): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $dismissed?->__invoke();
            },
        );
    }

    /**
     * Native alert with 1-3 buttons (at most one Cancel). `$pressed` receives
     * the pressed button's index in `$buttons`; on Android back/outside tap
     * answers the Cancel button's index (or `DISMISSED` without one).
     *
     * @param list<AlertButton> $buttons
     * @param Closure(int): void $pressed
     * @param null|Closure(string): void $failure
     */
    public static function choose(
        string $title,
        string $message,
        array $buttons,
        Closure $pressed,
        ?Closure $failure = null,
    ): int {
        if ($buttons === [] || count($buttons) > self::MAX_BUTTONS || !array_is_list($buttons)) {
            throw new InvalidArgumentException('Alert::choose() needs between 1 and 3 buttons.');
        }
        $values = ['title' => $title, 'message' => $message, 'buttonCount' => count($buttons)];
        $cancels = 0;
        foreach ($buttons as $index => $button) {
            if (!$button instanceof AlertButton) {
                throw new InvalidArgumentException('Alert::choose() buttons must be AlertButton values.');
            }
            $cancels += $button->style === AlertButtonStyle::Cancel ? 1 : 0;
            $values["button{$index}Label"] = $button->label;
            $values["button{$index}Style"] = $button->style->value;
        }
        if ($cancels > 1) {
            throw new InvalidArgumentException('Alert::choose() accepts one cancel button.');
        }
        $count = count($buttons);

        return Runtime::callNative(
            NativeOperation::Alert,
            Wire::map($values),
            static function (ModuleResultStatus $status, string $payload) use ($pressed, $failure, $count): void {
                if ($status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($payload);

                        return;
                    }
                    throw new RuntimeException($payload);
                }
                $values = $payload === '' ? [] : Wire::decodeMap($payload);
                $index = (int) ($values['index'] ?? self::DISMISSED);
                $pressed($index >= 0 && $index < $count ? $index : self::DISMISSED);
            },
        );
    }
}
