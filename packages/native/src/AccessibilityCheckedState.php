<?php

declare(strict_types=1);

namespace Pam\Native;

enum AccessibilityCheckedState: int
{
    /**
     * No checked state: the same as leaving `accessibilityCheckedState` out.
     * VoiceOver announces no state and a checkable role (checkbox, radio,
     * switch, togglebutton) follows its `checked` prop on TalkBack.
     */
    case None = 0;
    case Unchecked = 1;
    case Checked = 2;
    case Mixed = 3;
}
