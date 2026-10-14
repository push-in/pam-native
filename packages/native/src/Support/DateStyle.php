<?php

declare(strict_types=1);

namespace Pam\Native\Support;

/** Calendar date wording of Format::date() and the fallback of Format::relative(). */
enum DateStyle: int
{
    /** pt-BR "12 out."; en "Oct 12". */
    case Short = 1;
    /** pt-BR "12 de out."; en "Oct 12". */
    case Medium = 2;
    /** pt-BR "12 de outubro"; en "October 12". */
    case Long = 3;
    /** pt-BR "12/10"; en "10/12". */
    case Numeric = 4;
    /** pt-BR "12/10/2025"; en "10/12/2025" (always with the year). */
    case NumericWithYear = 5;
    /** pt-BR "12/10/25"; en "10/12/25". */
    case NumericWithShortYear = 6;
}
