<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Navigation\{Navigator, NavigationTransition};
use Pam\Native\UI\Text;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
$nav = new Navigator(initialRoute: 'home', routes: [
    'home' => static fn () => Text::make('Home'),
    'chat' => static fn () => Text::make('Chat'),
    'details' => static fn () => Text::make('Details'),
], restorePersistedState: true);
$end = static function () use ($nav): void {
    ($nav->render()->toElement()->events()[EventKind::AnimationComplete->value])();
};
$assert(!$nav->isTransitioning(), 'Initial route has no native transition to await.');
$nav->push('chat');
$assert($nav->isTransitioning(), 'Push awaits completion.');
$state = $nav->saveState();
$end();
$assert(!$nav->isTransitioning(), 'Native completion settles the push.');
$nav->replace('details');
$assert($nav->isTransitioning(), 'Replace with outgoing route awaits completion.');
$end();
$nav->pop();
$assert($nav->isTransitioning(), 'Pop awaits completion of outgoing route.');
$end();
$assert(!$nav->isTransitioning(), 'Pop settles after completion.');
$nav->restoreState($state);
$assert(count($nav->getState()['routes']) === 2 && !$nav->isTransitioning(), 'Restored multi-route stack needs no completion event.');
$nav->reset('chat');
$assert(!$nav->isTransitioning(), 'Reset has no outgoing animation.');
$nav->push('details', transition: NavigationTransition::None, durationMs: 0);
$assert($nav->isTransitioning(), 'Zero-duration pushes still await the host commit/completion.');
$end();
$assert(!$nav->isTransitioning(), 'Reduced-motion/zero-duration completion also settles.');
echo "NAVIGATION_TRANSITION_PENDING_OK initial=idle push=await pop=await replace=await restore=idle reset=idle zero_duration=complete\n";
