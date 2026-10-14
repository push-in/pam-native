<?php

declare(strict_types=1);

namespace Pam\Native\Support;

/** Wording of Format::relative(). */
enum RelativeTimeStyle: int
{
    /** pt-BR "há 5 min", "há 2 sem", "12 de out."; en "5m ago", "Oct 12". */
    case Short = 1;
    /** pt-BR "há 5 minutos", "12 de outubro"; en "5 minutes ago", "October 12". */
    case Long = 2;
    /** pt-BR "5 min", "2 h", "12 out."; en "5m", "2h", "Oct 12". */
    case Narrow = 3;
}
