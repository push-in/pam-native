<?php

declare(strict_types=1);

use Pam\Native\Animation\Drag;
use Pam\Native\Animation\DragTextFormat;

$plainDrag = Drag::horizontal();
$textDrag = $plainDrag->target('thumb')->atTouch(14)->snapOnRelease(false)
    ->driveText('clock', [0, 360], [0, 125], DragTextFormat::ClockWithTotal)
    ->bounds(0, 360);
$encodedTextDrag = $textDrag->encode();
$assert(str_contains($encodedTextDrag, 'text=clock|3|0|0,360|0,125'), 'Drag clock labels must encode typed format and interpolation endpoints.');
$assert(str_contains($encodedTextDrag, 'touch=14') && str_contains($encodedTextDrag, 'snapOnRelease=0'), 'Continuous drag must retain touch origin and release policy through builder cloning.');
$assert(!str_contains($plainDrag->encode(), 'text=') && !str_contains($plainDrag->encode(), 'touch='), 'Text/continuous options must not change an existing drag.');
$assert(array_column(DragTextFormat::cases(), 'value') === [1, 2, 3], 'Drag label formats must stay sequential integer enums.');
foreach ([
    fn () => Drag::horizontal()->driveText('clock', [0], [10]),
    fn () => Drag::horizontal()->driveText('clock', [0, 1], [0, INF]),
    fn () => Drag::horizontal()->driveText('clock', [0, 1], [0, 1], decimals: 4),
    fn () => Drag::horizontal()->driveText('invalid|ref', [0, 1], [0, 1]),
    fn () => Drag::horizontal()->atTouch(NAN),
] as $invalidDragText) {
    $rejected = false;
    try { $invalidDragText(); } catch (InvalidArgumentException) { $rejected = true; }
    $assert($rejected, 'Invalid native drag text must be rejected before crossing the bridge.');
}
