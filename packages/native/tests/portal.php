<?php

declare(strict_types=1);

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\Runtime;
use Pam\Native\UI\Portal;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

Runtime::shutdown();

$portalTexts = static function (Element $element) use (&$portalTexts): array {
    $texts = [];
    foreach ($element->properties() as $value) {
        if (is_string($value) && str_starts_with($value, 'portal-text:')) {
            $texts[] = substr($value, 12);
        }
    }
    foreach ($element->children() as $child) {
        array_push($texts, ...$portalTexts($child));
    }

    return $texts;
};
$hostOf = static function (Element $root) use (&$hostOf): ?Element {
    if ($root->elementKey() === 'portal-host:screen') return $root;
    foreach ($root->children() as $child) {
        if (($found = $hostOf($child)) !== null) return $found;
    }

    return null;
};

final class PortalMenuOwner extends Component
{
    public bool $open = false;
    public string $label = 'Responder';
    public int $renders = 0;

    public function render(): Element
    {
        $this->renders++;

        return View::make(
            Text::make('portal-text:row'),
            $this->open ? Portal::make('menu', Text::make('portal-text:'.$this->label)) : null,
        );
    }
}

final class PortalScreen extends Component
{
    public function __construct(public PortalMenuOwner $owner, public ?PortalMenuOwner $late = null)
    {
    }

    public function render(): Element
    {
        return View::make($this->owner, Portal::host(), $this->late);
    }
}

$owner = new PortalMenuOwner();
$screen = new PortalScreen($owner);
$tree = $screen->toElement();
$host = $hostOf($tree);
$assert($host instanceof Element && $portalTexts($host) === [], 'A host without portals is an empty, touch-transparent layer.');
$assert($portalTexts($tree) === ['row'], 'Nothing renders in place of a closed portal.');

$owner->open = true;
DependencyTracker::markDirty($owner);
$tree = $screen->toElement();
$assert($portalTexts($hostOf($tree)) === ['Responder'], 'Portal content is drawn by the host, not in place.');
$ownerTexts = $portalTexts($tree->children()[0]);
$assert($ownerTexts === ['row'], 'The owner subtree only keeps a zero-size placeholder.');

// A memoized owner keeps its portal.
$renders = $owner->renders;
DependencyTracker::markDirty($screen);
$tree = $screen->toElement();
$assert($owner->renders === $renders && $portalTexts($hostOf($tree)) === ['Responder'], 'A reused owner keeps its portal content.');

$owner->label = 'Copiar';
DependencyTracker::markDirty($owner);
$tree = $screen->toElement();
$assert($portalTexts($hostOf($tree)) === ['Copiar'], 'Re-rendered portal content replaces the previous content.');

$owner->open = false;
DependencyTracker::markDirty($owner);
$tree = $screen->toElement();
$assert($portalTexts($hostOf($tree)) === [], 'An owner that stops rendering the portal removes it.');

$owner->open = true;
DependencyTracker::markDirty($owner);
$screen->toElement();
DependencyTracker::forget($owner);
$tree = $screen->toElement();
$assert($portalTexts($hostOf($tree)) === [], 'Unmount removes the owner portals.');

foreach ([static fn () => Portal::make('bad key'), static fn () => Portal::to('', 'x'), static fn () => Portal::host('a/b')] as $bad) {
    try {
        $bad();
        throw new RuntimeException('An invalid portal name was accepted.');
    } catch (InvalidArgumentException) {
    }
}

// Booted runtime: an owner rendered after the host (here a later sibling)
// still shows its portal in the same render() call.
Runtime::shutdown();
$early = new PortalMenuOwner();
$late = new PortalMenuOwner();
$late->open = true;
$late->label = 'Tardio';
$lateScreen = new PortalScreen($early, $late);
$frames = 0;
Runtime::boot(static function () use ($lateScreen, &$frames): Element {
    $frames++;

    return $lateScreen->toElement();
});
$assert($frames <= 3, 'A late portal costs at most one extra pass.');
$assert($portalTexts($hostOf($lateScreen->toElement())) === ['Tardio'], 'A portal opened after its host is drawn in the same render call.');
Runtime::shutdown();

// Template tags: <Portal name host> and <PortalHost name>.
final class PortalTemplateOwner extends Component
{
    public bool $open = true;

    public function render(): Element
    {
        return \Pam\Native\Internal\TemplateRenderer::render(
            \Pam\Native\Internal\TemplateCompiler::compile(
                '<View><Text>portal-text:row</Text><Portal p-if="$open" name="sheet"><Text>portal-text:Encaminhar</Text></Portal><PortalHost /></View>',
            ),
            $this,
            ['open' => $this->open],
        );
    }
}
$templateOwner = new PortalTemplateOwner();
$tree = $templateOwner->toElement();
$assert($portalTexts($hostOf($tree)) === ['Encaminhar'], 'Template <Portal> content is drawn by <PortalHost>: '.json_encode($portalTexts($tree)));
$assert(count(array_filter($portalTexts($tree), static fn (string $text): bool => $text === 'Encaminhar')) === 1, 'Template portal content renders once, in the host.');
Runtime::shutdown();
echo "PORTAL_OK host=screen owner-lifecycle=render+unmount late=one-extra-pass\n";
