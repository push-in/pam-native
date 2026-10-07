<?php

declare(strict_types=1);

/*
 * Differential check of the tree encoder: every patch, applied to the tree
 * the previous frames described, must produce exactly the tree a fresh
 * encoder writes as a full frame. Subtrees are reused across frames (kept,
 * moved, dropped and brought back, nested in other reused subtrees) so the
 * retained-subtree paths of the encoder are exercised.
 */

use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Protocol;
use Pam\Native\UI\Column;
use Pam\Native\UI\Row;
use Pam\Native\UI\Text;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (str_starts_with($class, $prefix)) {
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

final class EncodedTreeModel
{
    /** @var array<int, array{0: int, 1: int, 2: int, 3: array<int, string>}> */
    public array $nodes = [];
    public int $root = 0;
    private int $offset = 0;

    public function __construct(private string $bytes = '')
    {
    }

    public static function full(string $frame): self
    {
        $model = new self($frame);
        $model->expect(Protocol::TREE_MAGIC);
        $model->u16();
        $model->root = $model->u64();
        $count = $model->u32();
        for ($index = 0; $index < $count; $index++) {
            $model->node();
        }
        $model->end();

        return $model;
    }

    public function apply(string $patch): void
    {
        $this->bytes = $patch;
        $this->offset = 0;
        $this->expect(Protocol::PATCH_MAGIC);
        $this->u16();
        $count = $this->u32();
        for ($index = 0; $index < $count; $index++) {
            $operation = ord($this->take(1));
            match ($operation) {
                1 => $this->node(),
                2 => $this->remove($this->u64()),
                3 => $this->update(),
                4 => $this->move(),
                5 => $this->root = $this->u64(),
                default => throw new RuntimeException("Unknown patch operation {$operation}."),
            };
        }
        $this->end();
    }

    private function node(): void
    {
        $id = $this->u64();
        $parent = $this->u64();
        $index = $this->u32();
        $kind = ord($this->take(1));
        $properties = [];
        $count = $this->u16();
        for ($property = 0; $property < $count; $property++) {
            $key = $this->u16();
            $properties[$key] = $this->value();
        }
        if (isset($this->nodes[$id])) {
            throw new RuntimeException("Node {$id} created twice.");
        }
        $this->nodes[$id] = [$parent, $index, $kind, $properties];
    }

    private function remove(int $id): void
    {
        if (!isset($this->nodes[$id])) {
            throw new RuntimeException("Removed node {$id} does not exist.");
        }
        unset($this->nodes[$id]);
    }

    private function update(): void
    {
        $id = $this->u64();
        $key = $this->u16();
        $mode = ord($this->take(1));
        if (!isset($this->nodes[$id])) {
            throw new RuntimeException("Updated node {$id} does not exist.");
        }
        if ($mode === 1) {
            $this->nodes[$id][3][$key] = $this->value();
            ksort($this->nodes[$id][3]);
        } else {
            unset($this->nodes[$id][3][$key]);
        }
    }

    private function move(): void
    {
        $id = $this->u64();
        $parent = $this->u64();
        $index = $this->u32();
        if (!isset($this->nodes[$id])) {
            throw new RuntimeException("Moved node {$id} does not exist.");
        }
        $this->nodes[$id][0] = $parent;
        $this->nodes[$id][1] = $index;
    }

    private function value(): string
    {
        $type = $this->take(1);

        return $type.match (ord($type)) {
            1, 5 => $this->take($this->u32()),
            2, 3 => $this->take(8),
            4 => $this->take(1),
            default => throw new RuntimeException('Unknown value type.'),
        };
    }

    private function expect(string $magic): void
    {
        if ($this->take(strlen($magic)) !== $magic) {
            throw new RuntimeException('Unexpected frame magic.');
        }
    }

    private function end(): void
    {
        if ($this->offset !== strlen($this->bytes)) {
            throw new RuntimeException('Trailing frame bytes.');
        }
    }

    private function take(int $length): string
    {
        if ($this->offset + $length > strlen($this->bytes)) {
            throw new RuntimeException('Truncated frame.');
        }
        $bytes = substr($this->bytes, $this->offset, $length);
        $this->offset += $length;

        return $bytes;
    }

    private function u16(): int
    {
        return unpack('v', $this->take(2))[1];
    }

    private function u32(): int
    {
        return unpack('V', $this->take(4))[1];
    }

    private function u64(): int
    {
        return unpack('P', $this->take(8))[1];
    }
}

mt_srand(0x454E43);
$row = static fn (int $id, int $version): Column => Column::make(
    Text::make("row {$id}"),
    Row::make(Text::make("v{$version}"), Text::make($version % 3 === 0 ? 'even' : 'odd')),
)->key("row-{$id}");
$pool = [];
for ($id = 0; $id < 40; $id++) {
    $pool[$id] = $row($id, 0);
}
// Groups reuse pooled rows inside another reused subtree.
$groups = [];
$encoder = new TreeEncoder();
$model = null;
$patches = 0;
$skipped = 0;
for ($frame = 0; $frame < 400; $frame++) {
    foreach ((array) array_rand($pool, mt_rand(1, 4)) as $changed) {
        $pool[$changed] = $row($changed, $frame);
    }
    if ($frame % 7 === 0) {
        $members = (array) array_rand($pool, mt_rand(2, 5));
        $groups[$frame % 3] = Column::make(...array_map(static fn (int $id): Column => $pool[$id], $members))
            ->key('group-'.($frame % 3));
    }
    $ids = array_keys($pool);
    shuffle($ids);
    $children = [Text::make("frame {$frame}")];
    foreach (array_slice($ids, 0, mt_rand(0, 25)) as $id) {
        $children[] = $pool[$id];
    }
    foreach ($groups as $index => $group) {
        if (mt_rand(0, 3) !== 0) {
            $children[] = $group;
        }
    }
    $tree = Column::make(...$children)->key('root');
    if ($frame % 97 === 50) {
        $encoder->forceFullFrame();
    }
    try {
        $encoded = $encoder->encode($tree);
    } catch (LogicException) {
        // A reused row also present in a group at the same time collides by
        // design (same key under one parent is fine, same subtree twice is
        // not); skip frames the encoder rejects.
        $encoder->forceFullFrame();
        $model = null;
        $skipped++;
        continue;
    }
    $reference = (new TreeEncoder())->encode($tree)['frame'];
    if (!is_string($reference)) {
        throw new RuntimeException('A fresh encoder must write a full frame.');
    }
    $expected = EncodedTreeModel::full($reference);
    if ($encoded['full'] || $model === null) {
        if ($encoded['frame'] !== $reference) {
            throw new RuntimeException("Full frame {$frame} differs from a fresh encoder's frame.");
        }
        $model = EncodedTreeModel::full($encoded['frame']);
    } elseif ($encoded['frame'] !== null) {
        $model->apply($encoded['frame']);
        $patches++;
    }
    if ($model->root !== $expected->root || $model->nodes != $expected->nodes) {
        throw new RuntimeException("Frame {$frame}: patched tree differs from the full frame.");
    }
    $nodes = $encoder->frameNodes();
    if (count($nodes) !== count($expected->nodes)) {
        throw new RuntimeException("Frame {$frame}: committed nodes differ from the frame.");
    }
}
if ($patches < 300) {
    throw new RuntimeException("Only {$patches} patch frames were checked.");
}

fwrite(STDOUT, "ENCODER_PATCHES_OK frames=400 patches={$patches} collisions={$skipped}\n");
