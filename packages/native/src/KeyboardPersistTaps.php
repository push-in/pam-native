<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * React Native `keyboardShouldPersistTaps` for ScrollView and lists: what a
 * tap inside the scroll view does while the soft keyboard is visible.
 */
enum KeyboardPersistTaps: int
{
    /** The tap dismisses the keyboard and does not reach the children. */
    case Never = 1;
    /** The keyboard stays; the tap reaches the children. */
    case Always = 2;
    /** The keyboard stays when a child handles the tap; otherwise it is dismissed. */
    case Handled = 3;

    /** RN accepts booleans: true is `always`, false is `never`. */
    public static function fromBool(bool $persist): self
    {
        return $persist ? self::Always : self::Never;
    }
}
