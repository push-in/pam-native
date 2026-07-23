<?php

declare(strict_types=1);

namespace Pam\Native;

enum FlexDirection: int
{
    case Column = 1;
    case Row = 2;
    case ColumnReverse = 3;
    case RowReverse = 4;
}
