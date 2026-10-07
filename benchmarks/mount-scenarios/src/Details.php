<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

/**
 * Chat-details-style tabs over one VirtualizedList: a header, a sticky tab
 * rail and 40 rows with a photo per tab. `keep: false` rebuilds the active
 * tab's rows on every switch (one section of rows in the tree); `keep: true`
 * keeps every visited tab as a keyed section (`listSection`) and switches
 * with `activeSection`, so returning to a tab is a native visibility swap.
 */
final class Details extends Component
{
    private const ROWS = 40;

    private const TABS = ['Mídias', 'Links', 'Arquivos'];

    private int $tab = 0;

    /** @var array<int, true> */
    private array $visited = [0 => true];

    /** @var array<int, list<array<string, string>>> */
    private array $rowsByTab = [];

    private bool $keep;

    public function __construct(bool $keep)
    {
        $this->keep = $keep;
    }

    public function render(): View
    {
        $rows = [];
        foreach ($this->keep ? array_keys($this->visited) : [$this->tab] as $tab) {
            array_push($rows, ...$this->rowsOf($tab));
        }

        return View::make('screens.details', [
            'rows' => $rows,
            'tab' => $this->tab,
            'keep' => $this->keep,
            'active' => $this->keep ? (string) $this->tab : '',
        ]);
    }

    /** @return list<array<string, string>> */
    private function rowsOf(int $tab): array
    {
        if (isset($this->rowsByTab[$tab])) {
            return $this->rowsByTab[$tab];
        }
        $rows = [];
        for ($i = 1; $i <= self::ROWS; $i++) {
            $rows[] = [
                'key' => $tab.'-'.$i,
                'section' => $this->keep ? (string) $tab : '',
                'src' => sprintf('asset://assets/photos/p%02d.jpg', (($i + $tab * 7) % 30) + 1),
                'title' => self::TABS[$tab].' item '.$i,
                'subtitle' => 'Compartilhado por Contato '.$i.' · '.(10 + $i % 20).' de out.',
                'meta' => (string) (2 * $i).' KB',
            ];
        }

        return $this->rowsByTab[$tab] = $rows;
    }

    public function first(): void
    {
        $this->select(0);
    }

    public function second(): void
    {
        $this->select(1);
    }

    public function third(): void
    {
        $this->select(2);
    }

    private function select(int $tab): void
    {
        $this->tab = $tab;
        $this->visited[$tab] = true;
    }

    public function back(): void
    {
        $this->popRoute();
    }
}
