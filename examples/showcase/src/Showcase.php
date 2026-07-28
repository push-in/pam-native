<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Element;
use Pam\Native\Http\Http;
use Pam\Native\Navigation\Navigator;
use Pam\Native\StatusBarAppearance;
use Pam\Native\Storage\Storage;
use Pam\Native\Style;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\Image;
use Pam\Native\UI\Input;
use Pam\Native\UI\NativeList;
use Pam\Native\UI\Row;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\StatusBar;
use Pam\Native\UI\Text;

final class Showcase
{
    public Navigator $navigator;
    private int $count = 0;
    private string $name = 'PHP';
    private string $networkStatus = 'HTTP has not run yet.';
    private string $storageStatus = 'Storage has not run yet.';

    public function home(): Element
    {
        return Screen::make(
            StatusBar::make(0xFF0F172A, StatusBarAppearance::Light),
            Scroll::make(
                Column::make(
                    Text::make('PAM NATIVE LAB')
                        ->key('eyebrow')
                        ->style(new Style(
                            height: 28,
                            textColor: 0xFF86EFAC,
                            fontSize: 13,
                            fontWeight: 700,
                            letterSpacing: 1.2,
                        )),
                    Text::make('PHP logic. Native pixels.')
                        ->key('title')
                        ->style(new Style(
                            height: 76,
                            textColor: 0xFFF8FAFC,
                            fontSize: 30,
                            fontWeight: 700,
                            lineHeight: 36,
                        )),
                    Text::make('Real Android views, persistent PHP and incremental Rust diffing.')
                        ->key('subtitle')
                        ->style(new Style(
                            height: 52,
                            textColor: 0xFFCBD5E1,
                            fontSize: 15,
                            lineHeight: 21,
                        )),
                    Row::make(
                        Column::make(
                            Text::make('10K')
                                ->style(new Style(height: 32, textColor: 0xFF86EFAC, fontSize: 22, fontWeight: 700)),
                            Text::make('recycled rows')
                                ->style(new Style(height: 24, textColor: 0xFFCBD5E1, fontSize: 13)),
                        )->style(new Style(
                            flexGrow: 1,
                            padding: 12,
                            gap: 2,
                            backgroundColor: 0xFF1E293B,
                            borderRadius: 12,
                            borderWidth: 1,
                            borderColor: 0xFF475569,
                        )),
                        Column::make(
                            Text::make('UI')
                                ->style(new Style(height: 32, textColor: 0xFF86EFAC, fontSize: 22, fontWeight: 700)),
                            Text::make('native thread')
                                ->style(new Style(height: 24, textColor: 0xFFCBD5E1, fontSize: 13)),
                        )->style(new Style(
                            flexGrow: 1,
                            padding: 12,
                            gap: 2,
                            backgroundColor: 0xFF1E293B,
                            borderRadius: 12,
                            borderWidth: 1,
                            borderColor: 0xFF475569,
                        )),
                    )->style(new Style(height: 84, gap: 10)),
                    Input::make($this->name)
                        ->key('name')
                        ->placeholder('Your name')
                        ->accessibilityLabel('Your name')
                        ->style(new Style(
                            height: 52,
                            paddingHorizontal: 14,
                            backgroundColor: 0xFF1E293B,
                            textColor: 0xFFF8FAFC,
                            borderRadius: 10,
                            borderWidth: 1,
                            borderColor: 0xFF475569,
                        ))
                        ->onChange(function (string $value): void {
                            $this->name = $value;
                        }),
                    Row::make(
                        Button::make("Patch #{$this->count}")
                            ->key('counter')
                            ->accessibilityLabel('benchmark-counter')
                            ->style(new Style(
                                flexGrow: 1,
                                textColor: 0xFF052E16,
                                backgroundColor: 0xFF22C55E,
                                borderRadius: 10,
                                fontWeight: 700,
                            ))
                            ->onPress(function (): void {
                                $this->count++;
                            }),
                        Button::make('10K list')
                            ->key('details')
                            ->accessibilityLabel('benchmark-list-route')
                            ->style(new Style(
                                flexGrow: 1,
                                textColor: 0xFFF8FAFC,
                                backgroundColor: 0xFF334155,
                                borderRadius: 10,
                                fontWeight: 700,
                            ))
                            ->onPress(function (): void {
                                $this->navigator->push('details');
                            }),
                    )->key('actions')->style(new Style(height: 52, gap: 10)),
                    Button::make('Tags, classes, props and slots')
                        ->key('tags')
                        ->style(new Style(
                            height: 50,
                            textColor: 0xFFF8FAFC,
                            backgroundColor: 0xFF1E293B,
                            borderRadius: 10,
                            borderWidth: 1,
                            borderColor: 0xFF475569,
                        ))
                        ->onPress(function (): void {
                            $this->navigator->push('tags');
                        }),
                    Text::make("Hello, {$this->name}.")
                        ->key('greeting')
                        ->style(new Style(height: 32, textColor: 0xFFF8FAFC, fontSize: 17)),
                    Button::make('Test HTTPS')
                        ->key('http')
                        ->style(new Style(height: 48))
                        ->onPress(function (): void {
                            $this->networkStatus = 'Loading…';
                            Http::get(
                                'https://httpbin.org/get',
                                function (\Pam\Native\Http\HttpResponse $response): void {
                                    $this->networkStatus = "HTTP {$response->statusCode}";
                                },
                            );
                        }),
                    Text::make($this->networkStatus)
                        ->key('network-status')
                        ->style(new Style(height: 28, textColor: 0xFFCBD5E1, fontSize: 13)),
                    Button::make('Persist counter')
                        ->key('storage')
                        ->style(new Style(height: 48))
                        ->onPress(function (): void {
                            Storage::set('count', (string) $this->count, function (): void {
                                $this->storageStatus = 'Counter persisted natively.';
                            });
                        }),
                    Text::make($this->storageStatus)
                        ->key('storage-status')
                        ->style(new Style(height: 28, textColor: 0xFFCBD5E1, fontSize: 13)),
                )->style(new Style(
                    padding: 20,
                    gap: 10,
                    backgroundColor: 0xFF0F172A,
                )),
            )->style(new Style(flexGrow: 1, backgroundColor: 0xFF0F172A)),
        );
    }

    public function details(): Element
    {
        $items = array_map(
            static fn (int $index): string => "Virtualized native row #{$index}",
            range(1, 10_000),
        );

        return Screen::make(
            Column::make(
                Row::make(
                    Button::make('Back')
                        ->key('back')
                        ->accessibilityLabel('benchmark-back')
                        ->style(new Style(width: 96))
                        ->onPress(function (): void {
                            $this->navigator->pop();
                        }),
                    Text::make('10,000 native rows')
                        ->key('details-title')
                        ->style(new Style(flexGrow: 1, fontSize: 20)),
                )->style(new Style(height: 52, gap: 12)),
                Image::make('https://picsum.photos/800/400')
                    ->key('hero')
                    ->accessibilityLabel('Remote showcase image')
                    ->style(new Style(height: 160)),
                NativeList::make($items)
                    ->key('large-list')
                    ->accessibilityLabel('benchmark-large-list')
                    ->rowHeight(48)
                    ->prefetch(12)
                    ->style(new Style(flexGrow: 1)),
            )->style(new Style(
                flexGrow: 1,
                padding: 16,
                gap: 8,
                backgroundColor: 0xFFFFFFFF,
            )),
        );
    }
}
