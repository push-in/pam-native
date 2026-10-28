<?php

declare(strict_types=1);

use Pam\Native\Internal\TemplateExpression;

foreach ([true, false] as $generated) {
    TemplateExpression::useGeneratedCode($generated);
    TemplateExpression::registerFunction('pluginGreeting', static fn (string $name): string => 'Hello '.$name);
    $assert(TemplateExpression::evaluate("pluginGreeting('Ana')", null, []) === 'Hello Ana', 'Plugin helper in both evaluators.');
    $scope = new class { public function pluginGreeting(string $name): string { return 'Own '.$name; } };
    $assert(TemplateExpression::evaluate("pluginGreeting('Ana')", $scope, []) === 'Own Ana', 'Component methods take precedence over plugin helpers.');
    TemplateExpression::registerFunction('pluginGreeting', static fn (string $name): string => 'Updated '.$name);
    $assert(TemplateExpression::evaluate("pluginGreeting('Ana')", null, []) === 'Updated Ana', 'Cached expressions see updated helpers.');
    TemplateExpression::forgetFunction('pluginGreeting');
    $missing = false;
    try { TemplateExpression::evaluate("pluginGreeting('Ana')", null, []); }
    catch (RuntimeException) { $missing = true; }
    $assert($missing, 'Removing a helper removes access from cached expressions.');
}
TemplateExpression::useGeneratedCode(true);
