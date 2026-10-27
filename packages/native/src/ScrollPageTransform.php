<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * Per-page transform of a horizontal paging ScrollView, applied on the UI
 * thread from the scroll offset (no PHP round-trip, nothing runs while the
 * pager is still). The direct children of the content row are the pages.
 */
enum ScrollPageTransform: int
{
    /**
     * Instagram-style cube between pages: each page rotates up to 90° around
     * the edge it shares with its neighbour (perspective 2.4× the page width)
     * and darkens up to 55% while it turns away.
     */
    case Cube = 1;
}
