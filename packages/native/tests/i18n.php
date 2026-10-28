<?php

declare(strict_types=1);

use Pam\Native\I18n\I18n;
use Pam\Native\Internal\TemplateExpression;

// Message catalogs: source fallback, plurals (_zero/_one/_other, French n<2),
// interpolation with locale number grouping, lazy loaders, locale resolution
// and the template t() built-in (generated and reference closures alike).
I18n::reset();
I18n::source('pt');
I18n::catalog('pt', [
    'common' => [
        'action.cancel' => 'Cancelar',
        'greeting' => 'Olá, {name}',
        'likes_zero' => 'Nenhuma curtida',
        'likes_one' => '{count} curtida',
        'likes_other' => '{count} curtidas',
        'only' => 'Só em português',
        'months' => 'jan|fev|mar',
    ],
]);
$i18nLoads = 0;
I18n::catalog('fr', static function () use (&$i18nLoads): array {
    ++$i18nLoads;

    return ['common' => ['action.cancel' => 'Annuler', 'likes_one' => '{count} j’aime', 'likes_other' => '{count} j’aime(s)']];
});
I18n::catalog('en-US', ['common' => ['action.cancel' => 'Cancel', 'likes_one' => '{count} like', 'likes_other' => '{count} likes']]);
$assert(I18n::locale() === 'pt' && I18n::t('common.action.cancel') === 'Cancelar', 'The source locale is the default.');
$assert($i18nLoads === 0, 'A catalog loader runs only when its locale is used.');
$assert(I18n::t('common.greeting', ['name' => 'Ana']) === 'Olá, Ana' && I18n::t('common.greeting') === 'Olá, {name}', 'Placeholders interpolate; missing ones stay.');
$assert(I18n::t('common.likes', ['count' => 0]) === 'Nenhuma curtida' && I18n::t('common.likes', ['count' => 1]) === '1 curtida' && I18n::t('common.likes', ['count' => 1234]) === '1.234 curtidas', 'pt plural with _zero and grouping.');
$assert(I18n::t('common.missing') === 'common.missing' && I18n::t('nodot') === 'nodot', 'A missing key returns the key.');
$assert(I18n::list('common.months') === ['jan', 'fev', 'mar'], 'Pipe lists.');
$assert(I18n::resolve('pt_BR') === 'pt' && I18n::resolve('fr-CA') === 'fr' && I18n::resolve('en-US') === 'en' && I18n::resolve('de-DE') === 'pt' && I18n::resolve(null, 'fr') === 'fr', 'Locale resolution by language subtag, source fallback.');
I18n::use('fr-FR');
$assert(I18n::locale() === 'fr' && I18n::t('common.action.cancel') === 'Annuler' && $i18nLoads === 1, 'Switching loads the catalog once.');
$assert(I18n::t('common.likes', ['count' => 0]) === '0 j’aime' && I18n::t('common.likes', ['count' => 1.5]) === '1.5 j’aime', 'French: 0 and 1.5 are singular; _zero is per catalog.');
$assert(I18n::t('common.likes', ['count' => 12345]) === "12\u{202F}345 j’aime(s)", 'French grouping.');
$assert(I18n::t('common.only') === 'Só em português', 'Missing keys fall back to the source locale.');
$assert(I18n::translateIn('en', 'common.likes', ['count' => 1234]) === '1,234 likes' && I18n::formatNumber(1234, 'es') === '1234' && I18n::formatNumber(12345, 'es') === '12.345', 'Explicit locale and Spanish minimum grouping.');
I18n::pluralRule('en', static fn (float|int $count): string => 'other');
$assert(I18n::translateIn('en', 'common.likes', ['count' => 1]) === '1 likes', 'Plural rules are pluggable.');
I18n::use('pt');
foreach ([true, false] as $generated) {
    TemplateExpression::useGeneratedCode($generated);
    $assert(TemplateExpression::evaluate("t('common.likes', ['count' => \$n])", new class { public int $n = 2; }, []) === '2 curtidas', 'Template t() built-in ('.($generated ? 'generated' : 'reference').').');
    $assert(TemplateExpression::evaluate("t('x')", new class { public function t(string $key): string { return 'own:'.$key; } }, []) === 'own:x', 'A component t() wins over the built-in.');
}
TemplateExpression::useGeneratedCode(true);
I18n::reset();
