<?php

declare(strict_types=1);

namespace App\Screens;

use App\Catalog;
use Pam\Native\Component;
use Pam\Native\KeyEvent;

/**
 * PAM TV home: a side menu, a hero that follows the focused poster, rails of
 * posters and a player driven by the remote's arrows and media keys.
 */
final class Home extends Component
{
    /** @var list<array<string, mixed>> */
    public array $rails = [];

    /** @var list<array{id: string, label: string}> */
    public array $menu = [
        ['id' => 'home', 'label' => 'Home'],
        ['id' => 'series', 'label' => 'Series'],
        ['id' => 'films', 'label' => 'Films'],
        ['id' => 'search', 'label' => 'Search'],
    ];

    /** @var array<string, string> */
    public array $featured = [];

    public string $section = 'home';

    /** @var array<string, string>|null */
    public ?array $playing = null;

    public bool $paused = false;

    public int $position = 0;

    public int $remaining = 100;

    public string $kicker = 'FEATURED';

    /** The poster that takes the focus first, then the one a player closed on. */
    public string $resume = 'trending-1';

    public function setup(): void
    {
        $this->rails = Catalog::rails();
        $this->featured = Catalog::find($this->resume);
    }

    public function preview(string $id): void
    {
        $this->featured = Catalog::find($id);
    }

    public function open(string $section): void
    {
        $this->section = $section;
        $this->kicker = $section === 'home' ? 'FEATURED' : strtoupper($section);
    }

    public function play(string $id): void
    {
        $this->playing = Catalog::find($id);
        $this->paused = false;
        $this->seek(-100);
        $this->resume = '';
    }

    public function playerKey(KeyEvent $event): void
    {
        if ($this->playing === null) {
            return;
        }
        match ($event->key) {
            'Enter', ' ', 'MediaPlayPause' => $this->paused = !$this->paused,
            'MediaPlay' => $this->paused = false,
            'MediaPause' => $this->paused = true,
            'ArrowRight', 'MediaFastForward' => $this->seek(10),
            'ArrowLeft', 'MediaRewind' => $this->seek(-10),
            'GoBack', 'Escape', 'MediaStop' => $this->close(),
            default => null,
        };
    }

    private function close(): void
    {
        $this->resume = $this->playing['id'] ?? 'trending-1';
        $this->playing = null;
    }

    private function seek(int $by): void
    {
        $this->position = max(0, min(100, $this->position + $by));
        $this->remaining = 100 - $this->position;
    }
}
?>

<template>
    <Screen class="screen">
        <SafeAreaView class="flex-1">
            <Row class="flex-1" gap="28">
                <Column p-if="!$form.phone" testId="menu" class="menu" focusGroup="true">
                    <Text class="brand">PAM TV</Text>
                    <Pressable
                        p-for="$item in $menu"
                        :key="$item['id']"
                        :testId="$item['id']"
                        class="menu-item"
                        :class="['menu-active' => $section === $item['id']]"
                        @press="open($item['id'])"
                    >
                        <Text class="menu-label">{{ $item['label'] }}</Text>
                    </Pressable>
                </Column>

                <Column class="flex-1" gap="12">
                    <Column class="hero" gap="8">
                        <Text class="hero-kicker">{{ $kicker }}</Text>
                        <Text testId="hero-title" class="hero-title">{{ $featured['title'] }}</Text>
                        <Text class="hero-meta">{{ $featured['meta'] }}</Text>
                        <Text class="hero-summary">{{ $featured['summary'] }}</Text>
                    </Column>

                    <ScrollView class="flex-1" showsVerticalScrollIndicator="false">
                        <Column gap="8" paddingBottom="24">
                            <Column p-for="$rail in $rails" :key="$rail['id']" gap="0">
                                <Text class="rail-title">{{ $rail['title'] }}</Text>
                                <ScrollView horizontal="true" showsHorizontalScrollIndicator="false" focusGroup="true">
                                    <Row class="rail">
                                    <Pressable
                                        p-for="$show in $rail['shows']"
                                        :key="$show['id']"
                                        :testId="$show['id']"
                                        class="tile"
                                        :backgroundColor="$show['color']"
                                        :autoFocus="$show['id'] === $resume"
                                        accessibilityLabel="Play"
                                        @focus="preview($show['id'])"
                                        @press="play($show['id'])"
                                    >
                                        <Text class="tile-title">{{ $show['title'] }}</Text>
                                    </Pressable>
                                    </Row>
                                </ScrollView>
                            </Column>
                        </Column>
                    </ScrollView>
                </Column>
            </Row>
        </SafeAreaView>

        <Column
            p-if="$playing !== null"
            testId="player"
            class="player"
            focusable="true"
            autoFocus="true"
            focusRing="none"
            captureKeys="ArrowLeft,ArrowRight,ArrowUp,ArrowDown,Enter,GoBack,Escape"
            @keyDown="playerKey"
        >
            <Text class="player-title">{{ $playing['title'] ?? '' }}</Text>
            <Text testId="player-state" class="player-state">{{ $paused ? 'Paused' : 'Playing' }} · {{ $position }}%</Text>
            <Row class="progress">
                <Column class="progress-done" :flexGrow="$position" />
                <Column class="progress-left" :flexGrow="$remaining" />
            </Row>
            <Text class="player-hint">OK pause · ◀ ▶ seek · Back closes</Text>
        </Column>
    </Screen>
</template>

<style scoped>
    .screen { flex: 1; background-color: #0B0F14; }
    .flex-1 { flex: 1; }
    .menu { width: 180px; gap: 6px; padding-top: 4px; }
    .brand { color: #E50914; font-size: 26px; font-weight: 800; margin-bottom: 18px; }
    .menu-item { padding: 10px 14px; border-radius: 10px; }
    .menu-active { background-color: #1F2937; }
    .menu-label { color: #E5E7EB; font-size: 18px; font-weight: 600; }
    .hero { padding: 20px 24px; border-radius: 16px; background-color: #111827; }
    .hero-kicker { color: #9CA3AF; font-size: 13px; font-weight: 700; letter-spacing: 1px; }
    .hero-title { color: #FFFFFF; font-size: 34px; font-weight: 800; }
    .hero-meta { color: #D1D5DB; font-size: 16px; }
    .hero-summary { color: #9CA3AF; font-size: 15px; max-width: 620px; }
    .rail-title { color: #F3F4F6; font-size: 20px; font-weight: 700; margin-left: 12px; }
    .rail { padding: 12px 12px 14px 12px; }
    .tile { width: 200px; height: 112px; margin-right: 14px; border-radius: 12px; padding: 12px; justify-content: flex-end; border-width: 3px; border-color: #00000000; }
    .tile:focus { transform: scale(1.08); border-color: #FFFFFF; }
    .tile-title { color: #FFFFFF; font-size: 17px; font-weight: 700; }
    .player { position: absolute; top: 0; left: 0; right: 0; bottom: 0; padding: 64px; gap: 14px; justify-content: flex-end; background-color: #000000; }
    .player-title { color: #FFFFFF; font-size: 40px; font-weight: 800; }
    .player-state { color: #D1D5DB; font-size: 20px; }
    .progress { height: 6px; border-radius: 3px; background-color: #374151; }
    .progress-done { background-color: #E50914; }
    .progress-left { background-color: #374151; }
    .player-hint { color: #6B7280; font-size: 15px; }
</style>
