<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\Animation\Drag;
use Pam\Native\Internal\DevWarnings;
use Pam\Native\Internal\Runtime;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;
use Pam\Native\ViewMeasurement;

/**
 * Imperative handle to a mounted view named by its `nativeRef` attribute,
 * like a React Native ref: focus, blur, clear and select text in an `Input`,
 * and measure any view.
 *
 * ```php
 * // <Input nativeRef="composer" :value="$draft" on:change="draftChanged" />
 * ViewRef::of('composer')->focus();
 * ViewRef::of('composer')->setSelection(3);          // caret after 3 UTF-16 units
 * ViewRef::of('composer')->clear();                  // on:change receives ""
 * ViewRef::of('message-42')->measureInWindow(function (?ViewMeasurement $frame): void {
 *     if ($frame !== null) $this->anchor = $frame;  // place a menu next to it
 * });
 * ```
 *
 * Each call is one asynchronous native request on the UI thread, sent after
 * the UI update of the current handler is committed (so `setSelection()`
 * right after changing the value applies to the new text, and `measure()`
 * sees the new layout); nothing is observed or polled afterwards. A ref that
 * is not mounted completes with an error (the optional callback receives it;
 * development builds also log it).
 */
final class ViewRef
{
    private const MODULE = 'view-ref';

    private function __construct(public readonly string $ref)
    {
    }

    /** @throws InvalidArgumentException for refs that are not 1-64 letters, digits, "_", ".", ":" or "-" */
    public static function of(string $ref): self
    {
        return new self(Drag::ref($ref));
    }

    /**
     * Focuses the input and opens the soft keyboard (React Native
     * `ref.focus()`); waits for a sheet or dialog that is still presenting.
     *
     * @param (Closure(bool, string): void)|null $done success and error message
     */
    public function focus(?Closure $done = null): void
    {
        $this->command('focus', [], $done);
    }

    /** Removes focus and hides the keyboard if this input has it (`ref.blur()`). */
    public function blur(?Closure $done = null): void
    {
        $this->command('blur', [], $done);
    }

    /**
     * Empties the input like the user would: its `on:change` receives "" so a
     * controlled value follows (`ref.clear()`).
     */
    public function clear(?Closure $done = null): void
    {
        $this->command('clear', [], $done);
    }

    /**
     * Places the caret (`end` null) or selects `[start, end)`, in UTF-16 code
     * units like React Native `setSelection`; values are clamped to the text.
     * Unlike controlled `selectionStart`/`selectionEnd`, it applies once and
     * never fights the user's next caret move.
     */
    public function setSelection(int $start, ?int $end = null, ?Closure $done = null): void
    {
        if ($start < 0 || ($end !== null && $end < $start)) {
            throw new InvalidArgumentException('ViewRef::setSelection expects 0 <= start <= end.');
        }

        $this->command('setSelection', ['start' => $start, 'end' => $end ?? $start], $done);
    }

    /** @param Closure(bool): void $callback whether the input currently has focus (`ref.isFocused()`) */
    public function isFocused(Closure $callback): void
    {
        $this->call(
            'isFocused',
            ['ref' => $this->ref],
            static function (NativeModuleResult $result) use ($callback): void {
                $callback($result->succeeded() && ($result->values()['focused'] ?? false) === true);
            },
        );
    }

    /**
     * React Native `ref.measure()`: frame in the parent plus page and window
     * positions; null and the error when the view is not mounted or laid out.
     *
     * @param Closure(?ViewMeasurement, string): void $callback
     */
    public function measure(Closure $callback): void
    {
        $this->call(
            'measure',
            ['ref' => $this->ref],
            static function (NativeModuleResult $result) use ($callback): void {
                $result->succeeded()
                    ? $callback(ViewMeasurement::fromValues($result->values()), '')
                    : $callback(null, $result->message());
            },
        );
    }

    /**
     * React Native `ref.measureInWindow()`: like measure(), with `x`/`y` set
     * to the window position (`windowX`/`windowY`), ready to anchor an overlay
     * or a Portal.
     *
     * @param Closure(?ViewMeasurement, string): void $callback
     */
    public function measureInWindow(Closure $callback): void
    {
        $this->measure(static function (?ViewMeasurement $frame, string $error) use ($callback): void {
            $callback(
                $frame === null ? null : new ViewMeasurement(
                    $frame->windowX,
                    $frame->windowY,
                    $frame->width,
                    $frame->height,
                    $frame->pageX,
                    $frame->pageY,
                    $frame->windowX,
                    $frame->windowY,
                ),
                $error,
            );
        });
    }

    /** @param array<string, int> $payload */
    private function command(string $method, array $payload, ?Closure $done): void
    {
        $ref = $this->ref;
        $this->call(
            $method,
            ['ref' => $ref] + $payload,
            static function (NativeModuleResult $result) use ($done, $method, $ref): void {
                if (!$result->succeeded()) {
                    DevWarnings::warn("ViewRef {$method} failed: {$result->message()}", "nativeRef {$ref}");
                }
                $done?->__invoke($result->succeeded(), $result->succeeded() ? '' : $result->message());
            },
        );
    }

    /**
     * @param array<string, string|int> $payload
     * @param Closure(NativeModuleResult): void $callback
     */
    private function call(string $method, array $payload, Closure $callback): void
    {
        Runtime::afterCommit(static function () use ($method, $payload, $callback): void {
            NativeModules::call(self::MODULE, $method, $payload, $callback);
        });
    }
}
