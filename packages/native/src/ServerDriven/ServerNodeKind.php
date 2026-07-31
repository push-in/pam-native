<?php

declare(strict_types=1);

namespace Pam\Native\ServerDriven;

enum ServerNodeKind: int
{
    case View = 1;
    case Column = 2;
    case Row = 3;
    case Text = 4;
    case Button = 5;
    case Image = 6;
    case Scroll = 7;
    case Spacer = 8;
}
