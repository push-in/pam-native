<?php

declare(strict_types=1);

namespace Pam\Native;

enum BottomSheetKeyboardBehavior: int
{
    case Interactive = 1;
    case Extend = 2;
    case FillParent = 3;
    /**
     * The sheet keeps its detent and position (its top edge never moves);
     * the keyboard inset is published to its content, whose
     * KeyboardAvoidingView / keyboard-inset ScrollView lift the inputs —
     * like @gorhom/bottom-sheet with `adjustResize` and a self-lifting composer.
     */
    case Contain = 4;
}
