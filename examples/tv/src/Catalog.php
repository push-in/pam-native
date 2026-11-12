<?php

declare(strict_types=1);

namespace App;

/** The demo catalog: three rails of shows, each a solid poster colour. */
final class Catalog
{
    private const RAILS = [
        'trending' => ['Trending now', [
            ['Night Shift', 'Thriller · 2026 · 2 seasons', '#FF7F1D1D'],
            ['Deep Orbit', 'Sci-fi · 2025 · Film', '#FF1E3A8A'],
            ['Salt & Pepper', 'Comedy · 2026 · 4 seasons', '#FF92400E'],
            ['The Long Field', 'Drama · 2024 · Film', '#FF14532D'],
            ['Signal Lost', 'Mystery · 2026 · 1 season', '#FF4C1D95'],
            ['Paper Towns', 'Romance · 2023 · Film', '#FF9D174D'],
        ]],
        'series' => ['Series to binge', [
            ['Harbour Lights', 'Drama · 3 seasons', '#FF155E75'],
            ['Ten Kitchens', 'Reality · 6 seasons', '#FFB45309'],
            ['Cold Case 9', 'Crime · 2 seasons', '#FF334155'],
            ['Wild North', 'Documentary · 1 season', '#FF3F6212'],
            ['Byte Club', 'Comedy · 2 seasons', '#FF6D28D9'],
            ['Riverside', 'Drama · 5 seasons', '#FF0F766E'],
        ]],
        'films' => ['Films for tonight', [
            ['Blue Hour', 'Drama · 1h 52m', '#FF1D4ED8'],
            ['Iron Valley', 'Action · 2h 08m', '#FF7C2D12'],
            ['Small Hours', 'Indie · 1h 34m', '#FF57534E'],
            ['Glass Garden', 'Fantasy · 2h 01m', '#FF047857'],
            ['Last Train', 'Thriller · 1h 47m', '#FF991B1B'],
            ['Sunday Club', 'Comedy · 1h 39m', '#FFA16207'],
        ]],
    ];

    /** @return list<array{id: string, title: string, shows: list<array<string, string>>}> */
    public static function rails(): array
    {
        $rails = [];
        foreach (self::RAILS as $railId => [$title, $shows]) {
            $rails[] = [
                'id' => $railId,
                'title' => $title,
                'shows' => array_map(
                    static fn (int $index, array $show): array => self::show($railId, $index, $show),
                    array_keys($shows),
                    $shows,
                ),
            ];
        }

        return $rails;
    }

    /** @return array<string, string> */
    public static function find(string $id): array
    {
        foreach (self::rails() as $rail) {
            foreach ($rail['shows'] as $show) {
                if ($show['id'] === $id) {
                    return $show;
                }
            }
        }

        return self::rails()[0]['shows'][0];
    }

    /**
     * @param array{0: string, 1: string, 2: string} $show
     * @return array<string, string>
     */
    private static function show(string $railId, int $index, array $show): array
    {
        return [
            'id' => $railId.'-'.($index + 1),
            'title' => $show[0],
            'meta' => $show[1],
            'color' => $show[2],
            'summary' => $show[0].' is one of the PAM TV demo titles: every poster, row and button here is a native view driven by PHP.',
        ];
    }
}
