<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\TemplateExpression;
use Pam\Native\Route;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use ReflectionFunction;
use ReflectionNamedType;
use RuntimeException;
use Throwable;

use function count;
use function in_array;
use function is_array;
use function is_string;

/**
 * Build-time compiler for `routes.php` (`pam-native-routes`, run by the PAM
 * CLI before every build): loads the routes, reads each screen's setup()
 * signature, then
 *  - writes the typed navigation (`RouteName` enum + `Routes` helpers that
 *    return a TypedRoute), replacing routes.json + Navigation/Generated;
 *  - writes a JSON manifest the CLI uses to derive Android/iOS deep links;
 *  - checks every literal `navigate()`, `redirect()`, `Route::navigate()` and
 *    `<Link route=...>` in the app sources, so a wrong route name, parameter
 *    or literal type fails the build.
 */
final class RouteCompiler
{
    private const SCALARS = ['string', 'int', 'float', 'bool'];

    private function __construct()
    {
    }

    /**
     * Requires routes.php in isolation and returns what it registered.
     */
    public static function load(string $file): RouteCollection
    {
        if (!is_file($file)) throw new InvalidArgumentException("Route file {$file} does not exist.");
        RouteCollection::reset();
        $result = (static fn (string $path): mixed => require $path)($file);
        $routes = $result instanceof RouteCollection ? $result : RouteCollection::current();
        if ($routes->isEmpty()) throw new RuntimeException("{$file} registered no routes.");

        return $routes;
    }

    /**
     * @param (Closure(string): bool)|null $discover called with a class name the autoloader
     *     cannot find (single-file components); returns whether it is now loadable.
     * @return array{version: int, routes: list<array<string, mixed>>}
     */
    public static function manifest(RouteCollection $routes, ?Closure $discover = null): array
    {
        $entries = [];
        $errors = [];
        foreach ($routes->all() as $route) {
            $name = $route->routeName();
            $params = [];
            try {
                if (is_string($route->screen)) {
                    if (!class_exists($route->screen) && ($discover === null || !$discover($route->screen))) {
                        throw new RuntimeException("Route {$name} screen {$route->screen} does not exist.");
                    }
                    $params = RouteArguments::signature($route->screen);
                } elseif ($route->screen instanceof Closure) {
                    $params = self::closureSignature($route->screen);
                }
                foreach ($params as $param) {
                    if (!in_array($param['type'], [...self::SCALARS, 'mixed'], true)) {
                        throw new RuntimeException("Route {$name} parameter {$param['name']} must be string, int, float, bool or a backed enum (got {$param['type']}).");
                    }
                }
                $declared = array_column($params, 'name');
                foreach ($route->pathParameters() as $pathParam) {
                    if ($params !== [] && !in_array($pathParam, $declared, true)) {
                        throw new RuntimeException("Route {$name} path {{$pathParam}} is not a parameter of the screen's setup().");
                    }
                    if ($params === []) {
                        $params[] = ['name' => $pathParam, 'type' => 'string', 'nullable' => false, 'required' => true, 'default' => null, 'enum' => null];
                    }
                }
                if ($route->kind === RouteKind::Tab) {
                    foreach ($params as $param) {
                        if ($param['required']) throw new RuntimeException("Tab {$name} cannot require parameter {$param['name']}.");
                    }
                }
            } catch (Throwable $error) {
                $errors[] = $error->getMessage();
                continue;
            }
            $entries[] = [
                'name' => $name,
                'kind' => $route->kind->value,
                'path' => $route->path,
                'linkable' => $route->linkable(),
                'screen' => is_string($route->screen) ? $route->screen : null,
                'tabs' => $route->tabs,
                'middleware' => array_values(array_filter($route->middleware(), is_string(...))),
                'params' => $params,
                'pathParams' => $route->pathParameters(),
            ];
        }
        if ($errors !== []) throw new RuntimeException(implode("\n", $errors));

        return ['version' => 1, 'routes' => $entries];
    }

    /**
     * @param array{version: int, routes: list<array<string, mixed>>} $manifest
     * @return array<string, string> filename => PHP source
     */
    public static function generate(array $manifest, string $namespace = 'App\\Navigation\\Generated'): array
    {
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]*(?:\\\\[A-Za-z_][A-Za-z0-9_]*)*$/D', $namespace) !== 1) {
            throw new InvalidArgumentException("Invalid namespace {$namespace}.");
        }
        $cases = [];
        $methods = [];
        $symbols = [];
        foreach ($manifest['routes'] as $route) {
            $case = self::studly($route['name']);
            $method = lcfirst($case);
            if (isset($symbols[strtolower($case)])) {
                throw new RuntimeException("Routes {$route['name']} and {$symbols[strtolower($case)]} generate the same helper {$method}().");
            }
            $symbols[strtolower($case)] = $route['name'];
            $cases[] = '    case '.$case.' = '.var_export($route['name'], true).';';
            $signature = [];
            $values = [];
            $optional = false;
            $params = $route['params'];
            usort($params, static fn (array $a, array $b): int => (int) !$a['required'] <=> (int) !$b['required']);
            foreach ($params as $param) {
                $type = $param['enum'] !== null ? '\\'.$param['enum'] : $param['type'];
                $declaration = ($param['nullable'] && $type !== 'mixed' ? '?' : '').$type.' $'.$param['name'];
                if (!$param['required']) {
                    $optional = true;
                    $default = $param['default'];
                    $declaration .= ' = '.($param['enum'] !== null && $default !== null
                        ? '\\'.$param['enum'].'::from('.var_export($default, true).')'
                        : self::export($default));
                } elseif ($optional) {
                    throw new RuntimeException("Route {$route['name']} has a required parameter after an optional one.");
                }
                $signature[] = $declaration;
                $values[] = var_export($param['name'], true).' => $'.$param['name'].($param['enum'] !== null ? ($param['nullable'] ? '?->value' : '->value') : '');
            }
            $doc = '    /** '.($route['path'] ?? 'tabs').($route['screen'] !== null ? ' -> '.$route['screen'] : '').' */';
            $methods[] = $doc."\n    public static function {$method}(".implode(', ', $signature)."): TypedRoute\n"
                ."    {\n        return new TypedRoute(".var_export($route['name'], true).', ['.implode(', ', $values)."]);\n    }";
        }
        $header = "<?php\n\n// Generated by pam-native-routes from routes.php. Do not edit.\n\ndeclare(strict_types=1);\n\nnamespace {$namespace};\n";

        return [
            'RouteName.php' => $header."\nenum RouteName: string\n{\n".implode("\n", $cases)."\n\n"
                ."    public function route(): \\Pam\\Native\\Route\n    {\n        return \\Pam\\Native\\Route::get(\$this->value);\n    }\n}\n",
            'Routes.php' => $header."\nuse Pam\\Native\\Routing\\TypedRoute;\n\nfinal class Routes\n{\n    private function __construct()\n    {\n    }\n\n"
                .implode("\n\n", $methods)."\n}\n",
        ];
    }

    /**
     * Literal navigation checks over the app sources.
     *
     * @param array{version: int, routes: list<array<string, mixed>>} $manifest
     * @param list<string> $exclude path prefixes to skip (generated code)
     * @return list<string> errors as "file:line: message"
     */
    public static function check(array $manifest, string $directory, array $exclude = []): array
    {
        if (!is_dir($directory)) return [];
        $routes = [];
        $screens = [];
        foreach ($manifest['routes'] as $route) {
            $routes[$route['name']] = $route;
            if ($route['screen'] !== null) $screens[strtolower($route['screen'])] ??= $route;
        }
        $errors = [];
        $files = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($directory, RecursiveDirectoryIterator::SKIP_DOTS));
        foreach ($files as $file) {
            $path = $file->getPathname();
            if (!str_ends_with($path, '.php')) continue;
            foreach ($exclude as $prefix) if (str_starts_with($path, $prefix)) continue 2;
            $source = (string) file_get_contents($path);
            if (!str_contains($source, 'navigate') && !str_contains($source, 'redirect') && !str_contains($source, '<Link')) continue;
            $relative = ltrim(substr($path, strlen($directory)), '/');
            foreach (self::phpCalls($source) as $call) {
                $route = $call['class'] !== null ? ($screens[strtolower($call['class'])] ?? null) : ($routes[$call['route']] ?? null);
                if ($route === null) {
                    $errors[] = "{$relative}:{$call['line']}: unknown route ".($call['class'] ?? $call['route']).'.';
                    continue;
                }
                foreach (self::paramErrors($route, $call['params'], $call['complete']) as $message) {
                    $errors[] = "{$relative}:{$call['line']}: {$message}";
                }
            }
            if (str_ends_with($path, '.pam.php')) {
                foreach (self::templateLinks($source) as $link) {
                    $route = $routes[$link['route']] ?? null;
                    if ($route === null) {
                        $errors[] = "{$relative}:{$link['line']}: <Link> to unknown route {$link['route']}.";
                        continue;
                    }
                    foreach (self::paramErrors($route, $link['params'], $link['complete']) as $message) {
                        $errors[] = "{$relative}:{$link['line']}: <Link> {$message}";
                    }
                }
            }
        }

        return $errors;
    }

    /** Lazily discovers single-file components under `$source` the first time a class is missing. */
    public static function componentDiscovery(string $source, string $cache): Closure
    {
        $discovered = false;

        return static function (string $class) use (&$discovered, $source, $cache): bool {
            if (!$discovered && is_dir($source)) {
                $discovered = true;
                PamPhpRegistry::discover($source, $cache);
            }

            return class_exists($class);
        };
    }

    /**
     * @param array<string, mixed> $route
     * @param array<string, string|null> $params name => literal type (null when dynamic)
     * @return list<string>
     */
    private static function paramErrors(array $route, array $params, bool $complete): array
    {
        $errors = [];
        $declared = [];
        foreach ($route['params'] as $param) $declared[$param['name']] = $param;
        foreach ($params as $name => $literal) {
            $param = $declared[$name] ?? null;
            if ($param === null) {
                $errors[] = "route {$route['name']} has no parameter {$name}.";
                continue;
            }
            if ($literal === null || $param['type'] === 'mixed') continue;
            $accepts = match ($param['type']) {
                'float' => in_array($literal, ['float', 'int'], true),
                default => $literal === $param['type'],
            } || ($literal === 'null' && $param['nullable']);
            // Link templates and deep links pass strings; numeric strings coerce at runtime.
            if (!$accepts && !($literal === 'numeric-string' && in_array($param['type'], ['int', 'float', 'string'], true))) {
                $errors[] = "route {$route['name']} parameter {$name} expects {$param['type']}, {$literal} given.";
            }
        }
        if ($complete) {
            foreach ($declared as $name => $param) {
                if ($param['required'] && !array_key_exists($name, $params)) {
                    $errors[] = "route {$route['name']} requires parameter {$name}.";
                }
            }
        }

        return $errors;
    }

    /**
     * Literal `navigate(...)`, `redirect(...)`, `Route::navigate(...)` calls.
     *
     * @return list<array{line: int, route: ?string, class: ?string, params: array<string, ?string>, complete: bool}>
     */
    private static function phpCalls(string $source): array
    {
        try {
            $tokens = token_get_all($source);
        } catch (Throwable) {
            return [];
        }
        $tokens = array_values(array_filter($tokens, static fn ($token): bool => !is_array($token) || !in_array($token[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true)));
        $namespace = '';
        $uses = [];
        $calls = [];
        $count = count($tokens);
        for ($index = 0; $index < $count; $index++) {
            $token = $tokens[$index];
            if (is_array($token) && $token[0] === T_NAMESPACE) {
                $namespace = is_array($tokens[$index + 1] ?? null) ? ltrim((string) $tokens[$index + 1][1], '\\') : '';
                continue;
            }
            if (is_array($token) && $token[0] === T_USE && ($tokens[$index - 1] ?? ';') !== ')') {
                $next = $tokens[$index + 1] ?? null;
                if (is_array($next) && in_array($next[0], [T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED, T_STRING], true)) {
                    $full = ltrim($next[1], '\\');
                    $alias = substr($full, (int) strrpos('\\'.$full, '\\'));
                    if (is_array($tokens[$index + 2] ?? null) && $tokens[$index + 2][0] === T_AS) $alias = $tokens[$index + 3][1];
                    $uses[strtolower($alias)] = $full;
                }
                continue;
            }
            if (!is_array($token) || !in_array($token[0], [T_STRING, T_NAME_FULLY_QUALIFIED, T_NAME_QUALIFIED], true)) continue;
            $function = strtolower(substr($token[1], (int) strrpos('\\'.$token[1], '\\')));
            if (!in_array($function, ['navigate', 'redirect'], true) || ($tokens[$index + 1] ?? null) !== '(') continue;
            $previous = $tokens[$index - 1] ?? null;
            $isStatic = is_array($previous) && $previous[0] === T_DOUBLE_COLON;
            if (is_array($previous) && in_array($previous[0], [T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR, T_FUNCTION], true)) continue;
            if ($isStatic) {
                $owner = $tokens[$index - 2] ?? null;
                $ownerName = is_array($owner) ? ltrim($owner[1], '\\') : '';
                if (strcasecmp($ownerName, 'Route') !== 0 && strcasecmp($ownerName, 'Pam\\Native\\Route') !== 0) continue;
            }
            $call = self::parseCall($tokens, $index + 2, $namespace, $uses);
            if ($call === null) continue;
            $call['line'] = $token[2];
            $calls[] = $call;
        }

        return $calls;
    }

    /**
     * @param list<mixed> $tokens
     * @param array<string, string> $uses
     * @return array{line: int, route: ?string, class: ?string, params: array<string, ?string>, complete: bool}|null
     */
    private static function parseCall(array $tokens, int $start, string $namespace, array $uses): ?array
    {
        $first = $tokens[$start] ?? null;
        $route = null;
        $class = null;
        $cursor = $start + 1;
        if (is_array($first) && $first[0] === T_CONSTANT_ENCAPSED_STRING) {
            $route = stripcslashes(substr($first[1], 1, -1));
        } elseif (is_array($first) && in_array($first[0], [T_STRING, T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED], true)
            && is_array($tokens[$start + 1] ?? null) && $tokens[$start + 1][0] === T_DOUBLE_COLON
            && is_array($tokens[$start + 2] ?? null) && strtolower($tokens[$start + 2][1]) === 'class') {
            $class = self::resolveClass($first, $namespace, $uses);
            $cursor = $start + 3;
        } else {
            return null;
        }
        $params = [];
        $complete = true;
        $depth = 0;
        $count = count($tokens);
        for ($index = $cursor; $index < $count; $index++) {
            $token = $tokens[$index];
            if ($token === '(' || $token === '[' || $token === '{') $depth++;
            if ($token === ')' || $token === ']' || $token === '}') {
                if ($depth === 0) break;
                $depth--;
                continue;
            }
            if ($depth !== 0 || $token !== ',') continue;
            $next = $tokens[$index + 1] ?? null;
            if ($next === '[') {
                [$literal, $index, $fully] = self::arrayLiteral($tokens, $index + 2);
                $params += $literal;
                $complete = $complete && $fully;
                continue;
            }
            if (is_array($next) && $next[0] === T_STRING && ($tokens[$index + 2] ?? null) === ':') {
                if ($next[1] === 'params' || $next[1] === 'mode') continue;
                $params[$next[1]] = self::literalType($tokens, $index + 3);
                continue;
            }
            if (is_array($next) && $next[0] === T_ELLIPSIS || is_array($next) && $next[0] === T_VARIABLE) $complete = false;
        }

        return ['line' => 0, 'route' => $route, 'class' => $class, 'params' => $params, 'complete' => $complete];
    }

    /**
     * @param list<mixed> $tokens
     * @return array{0: array<string, ?string>, 1: int, 2: bool}
     */
    private static function arrayLiteral(array $tokens, int $index): array
    {
        $params = [];
        $complete = true;
        $depth = 0;
        $count = count($tokens);
        $expectKey = true;
        for (; $index < $count; $index++) {
            $token = $tokens[$index];
            if ($token === '(' || $token === '[' || $token === '{') { $depth++; continue; }
            if ($token === ')' || $token === ']' || $token === '}') {
                if ($depth === 0) return [$params, $index, $complete];
                $depth--;
                continue;
            }
            if ($depth !== 0) continue;
            if ($token === ',') { $expectKey = true; continue; }
            if (!$expectKey) continue;
            $expectKey = false;
            if (is_array($token) && $token[0] === T_ELLIPSIS) { $complete = false; continue; }
            if (is_array($token) && $token[0] === T_CONSTANT_ENCAPSED_STRING
                && is_array($tokens[$index + 1] ?? null) && $tokens[$index + 1][0] === T_DOUBLE_ARROW) {
                $params[stripcslashes(substr($token[1], 1, -1))] = self::literalType($tokens, $index + 2);
                continue;
            }
            $complete = false;
        }

        return [$params, $index, $complete];
    }

    /** @param list<mixed> $tokens */
    private static function literalType(array $tokens, int $index): ?string
    {
        $token = $tokens[$index] ?? null;
        $after = $tokens[$index + 1] ?? null;
        if (!in_array($after, [',', ')', ']'], true)) return null;
        if (!is_array($token)) return null;

        return match (true) {
            $token[0] === T_CONSTANT_ENCAPSED_STRING => 'string',
            $token[0] === T_LNUMBER => 'int',
            $token[0] === T_DNUMBER => 'float',
            $token[0] === T_STRING && in_array(strtolower($token[1]), ['true', 'false'], true) => 'bool',
            $token[0] === T_STRING && strtolower($token[1]) === 'null' => 'null',
            default => null,
        };
    }

    /** @param array<string, string> $uses */
    private static function resolveClass(array $token, string $namespace, array $uses): string
    {
        $name = $token[1];
        if ($token[0] === T_NAME_FULLY_QUALIFIED) return ltrim($name, '\\');
        $first = strtolower(explode('\\', $name)[0]);
        if (isset($uses[$first])) {
            $rest = substr($name, strlen($first));

            return $uses[$first].$rest;
        }

        return ltrim($namespace.'\\'.$name, '\\');
    }

    /** @return list<array{line: int, route: string, params: array<string, ?string>, complete: bool}> */
    private static function templateLinks(string $source): array
    {
        $links = [];
        if (preg_match_all('/<Link\b([^>]*)>/s', $source, $matches, PREG_OFFSET_CAPTURE) === 0) return [];
        foreach ($matches[1] as [$attributes, $offset]) {
            if (preg_match('/(?<![:\w])(?:route|to)="([^"]+)"/', $attributes, $route) !== 1) continue;
            $params = [];
            $complete = true;
            if (preg_match('/:params="([^"]*)"/', $attributes, $literal) === 1) {
                $expression = html_entity_decode($literal[1], ENT_QUOTES);
                try {
                    $expression = TemplateExpression::normalizeObjectLiteral($expression);
                } catch (Throwable) {
                    $complete = false;
                }
                $tokens = @token_get_all('<?php '.$expression.';');
                $tokens = array_values(array_filter($tokens, static fn ($token): bool => !is_array($token) || !in_array($token[0], [T_WHITESPACE, T_OPEN_TAG], true)));
                if (($tokens[0] ?? null) === '[') {
                    [$params, , $complete] = self::arrayLiteral($tokens, 1);
                } else {
                    $complete = false;
                }
            }
            foreach ($params as $name => $type) {
                if ($type === 'string') $params[$name] = 'numeric-string';
            }
            $links[] = [
                'line' => substr_count(substr($source, 0, $offset), "\n") + 1,
                'route' => $route[1],
                'params' => $params,
                'complete' => $complete,
            ];
        }

        return $links;
    }

    /** @return list<array{name: string, type: string, nullable: bool, required: bool, default: mixed, enum: ?string}> */
    private static function closureSignature(Closure $closure): array
    {
        $signature = [];
        foreach ((new ReflectionFunction($closure))->getParameters() as $parameter) {
            $type = $parameter->getType();
            $name = $type instanceof ReflectionNamedType ? $type->getName() : 'mixed';
            if ($name === \Pam\Native\Navigation\RouteContext::class) continue;
            $signature[] = [
                'name' => $parameter->getName(),
                'type' => $name,
                'nullable' => $type === null || $type->allowsNull(),
                'required' => !$parameter->isOptional(),
                'default' => $parameter->isDefaultValueAvailable() ? $parameter->getDefaultValue() : null,
                'enum' => null,
            ];
        }

        return $signature;
    }

    private static function studly(string $value): string
    {
        $studly = str_replace(' ', '', ucwords(str_replace(['.', '-', '_'], ' ', $value)));

        return ctype_digit($studly[0] ?? 'a') ? 'R'.$studly : $studly;
    }

    private static function export(mixed $value): string
    {
        return match (true) {
            $value === null => 'null',
            $value === true => 'true',
            $value === false => 'false',
            default => var_export($value, true),
        };
    }
}
