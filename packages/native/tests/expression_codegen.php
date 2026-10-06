<?php

declare(strict_types=1);

use Pam\Native\Internal\TemplateExpression;
use Pam\Native\Internal\TemplateExpressionCompiler;

// Generated expression closures must behave exactly like the reference
// closure tree: same values, same types, same diagnostics.
$codegenScope = new class {
    public array $row = ['id' => 'c-1', 'selected' => true, 'dark' => false, 'title' => 'Ana', 'badge' => 'count', 'unread' => 3];
    public ?string $missingValue = null;
    public int $count = 4;
    public object $profile;

    public function __construct()
    {
        $this->profile = (object) ['name' => 'Pam', 'links' => ['site' => 'https://pam.dev']];
    }

    public function glyph(string $name): string
    {
        return 'asset://glyphs/'.$name.'.png';
    }

    public function double(int $value): int
    {
        return $value * 2;
    }
};
$codegenExpressions = [
    "['message-row', 'message-row-selected' => \$row['selected'], 'dark' => \$row['selected'] && \$row['dark']]",
    "\$row['dark'] ? '#FFFFFF' : '#F7F6F2'",
    "\$row['badge'] === 'count'",
    "'channel-preview-button-'.\$row['id']",
    "glyph('checkmark')",
    "\$row['missing'] ?? 'fallback'",
    "\$row['missing']['deeper'] ?? \$missingValue ?? 'last'",
    "\$row['missing']",
    "\$undefinedVariable",
    "\$undefinedVariable ?? 7",
    "\$profile->name",
    "\$profile.links['site']",
    "\$profile->unknown ?? 'none'",
    "\$profile->unknown",
    "\$count + 1 * 2 - 3",
    "(\$count + 1) % 3",
    "\$count / 0",
    "\$count % 2.5",
    "-\$count",
    "-\$row",
    "!\$row['selected'] || \$count >= 4",
    "\$count <=> 1",
    "\$count > 3 && \$count < 5 && \$count <= 4",
    "\$count == '4' && \$count != 5",
    "\$row['title'] ?: 'Untitled'",
    "\$missingValue ?: 'empty'",
    "count(\$row) . ' items'",
    "in_array('c-1', \$row, true)",
    "double(\$count)",
    "mb_strtoupper(mb_substr(\$row['title'], 0, 1))",
    "[\$count => 'numeric', 'list', 'nested' => [\$row['unread'], \$row['unread'] + 1]]",
    "[\$row => 'bad key']",
    "\$row . 'x'",
    "\$count + 'x'",
    "1.5 * 2",
    "'a\\'b' . \"c\\n\"",
    "\$row[\$count]",
    "\$row[\$undefinedVariable ?? 'id']",
    "\$row[[]] ?? 'bad index'",
    "\$count ? \$undefinedVariable : 1",
    "true ? 'yes' : 'no'",
    "null ?? false ?? 'x'",
];
foreach ($codegenExpressions as $codegenExpression) {
    $outcomes = [];
    foreach ([true, false] as $generated) {
        TemplateExpression::useGeneratedCode($generated);
        try {
            $value = TemplateExpression::evaluate($codegenExpression, $codegenScope, ['row' => $codegenScope->row, 'local' => 1]);
            $outcomes[] = 'value:'.get_debug_type($value).':'.serialize(is_object($value) ? get_object_vars($value) : $value);
        } catch (Throwable $codegenError) {
            $outcomes[] = 'error:'.$codegenError::class.':'.$codegenError->getMessage();
        }
    }
    $assert(
        $outcomes[0] === $outcomes[1],
        "Generated template expression must match the reference evaluator: {$codegenExpression} ({$outcomes[0]} vs {$outcomes[1]}).",
    );
}
TemplateExpression::useGeneratedCode(true);
$assert(
    str_starts_with(TemplateExpressionCompiler::closureSource("\$row['id'] ?? ''"), 'static function (array $data, ?object $scope): mixed {'),
    'Template expressions compile to plain PHP closures.',
);
try {
    TemplateExpressionCompiler::closureSource('$a +');
    $assert(false, 'Invalid expressions must not compile.');
} catch (RuntimeException) {
    $assert(true, 'Invalid expressions are rejected by the expression compiler.');
}
