<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use Pam\Native\Internal\TemplateCompiler;
use PhpParser\Node;
use PhpParser\Node\Stmt;
use PhpParser\Node\Expr;
use PhpParser\NodeFinder;
use PhpParser\NodeTraverser;
use PhpParser\NodeVisitor\CloningVisitor;
use PhpParser\NodeVisitor\NameResolver;
use PhpParser\Parser;
use PhpParser\ParserFactory;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use RuntimeException;

/**
 * Builds a PAM Native app as a static web app: `index.html`, `app.js` and
 * `app.css`, plus `config.xml` (Samsung Tizen) or `appinfo.json` (LG webOS).
 * Nothing runs on a server; the TV opens the files from the package.
 */
final class Builder
{
    public const TARGETS = ['tizen', 'webos', 'web'];

    private Parser $parser;
    /** @var array<string, ClassInfo> */
    private array $classes = [];
    /** @var array<string, array{node: Stmt\Function_, file: string, js: string}> */
    private array $functions = [];
    /** @var list<string> */
    public array $warnings = [];
    /** @var array<string, array{node: Stmt\Trait_, file: string, ready: bool}> */
    private array $traits = [];
    private ComposerAutoload $composer;
    /** @var array<string, true> */
    private array $loaded = [];
    /** @var array<string, array{node: Stmt\Function_, file: string}>|null */
    private ?array $vendorFunctions = null;
    /** @var list<Stmt> */
    private array $entryAst = [];

    public function __construct(
        private readonly string $root,
        private readonly string $target,
        private readonly string $out,
        private readonly bool $tv = true,
    ) {
        if (!in_array($target, self::TARGETS, true)) {
            throw new RuntimeException("Unknown web target {$target}; use one of: ".implode(', ', self::TARGETS).'.');
        }
        $this->parser = (new ParserFactory())->createForNewestSupportedVersion();
        $this->composer = new ComposerAutoload($root);
    }

    /** @return array{files: array<string, int>, warnings: list<string>} */
    public function build(): array
    {
        $manifest = $this->manifest();
        $entry = $this->root.'/'.($manifest['entry'] ?? 'index.php');
        $tv = $this->target !== 'web' || $this->tv;
        $overscan = (bool) ($manifest['web']['tv']['overscan'] ?? $manifest['android']['tv']['overscan'] ?? true);

        $this->entryAst = $this->parse($entry);
        $this->collect();
        $this->link();
        $features = ['device-type' => $tv ? 'tv' : 'tablet', 'input-mode' => $tv ? 'remote' : 'pointer'];

        $js = [];
        $css = [file_get_contents(__DIR__.'/../resources/runtime.css')];
        $appStyle = $this->root.'/src/app.css';
        if (is_file($appStyle)) {
            $css[] = StyleToCss::compile((string) file_get_contents($appStyle), null, $features, 'src/app.css');
        }
        $functionNames = [];
        foreach ($this->functions as $lower => $function) {
            $functionNames[$lower] = $function['js'];
        }
        $translator = new PhpToJs($this->classes, $functionNames);
        foreach ($this->functions as $function) {
            $js[] = $translator->functionDeclaration($function['node'], $function['file'], $function['js']);
        }
        $components = [];
        foreach ($this->classes as $info) {
            if ($info->isComponent && $info->template !== null) {
                $components[$info->shortName()] = Js::name($info->name);
            }
        }
        $scope = 0;
        foreach ($this->ordered() as $info) {
            $js[] = $translator->classDeclaration($info);
            if (!$info->isComponent || $info->template === null) {
                continue;
            }
            $scope++;
            $tree = TemplateCompiler::compile(str_repeat("\n", max(0, $info->templateLine - 1)).$info->template, $info->templateFile);
            [$properties, $methods] = $this->members($info);
            $template = new TemplateToJs($properties, $methods, $components, $info->styleScoped ? 's'.$scope : '', $this->relative($info->templateFile));
            $js[] = Js::name($info->name).'.__render = '.$template->compile($tree).";\n";
            array_push($this->warnings, ...$template->warnings);
            if (trim($info->style) !== '') {
                $css[] = StyleToCss::compile($info->style, $info->styleScoped ? 's'.$scope : null, $features, $this->relative($info->templateFile));
            }
        }
        $js[] = $translator->script($this->entryAst, $this->relative($entry));

        $config = [
            'target' => $this->target,
            'tv' => $tv,
            'overscan' => $overscan,
            'width' => 960,
        ];
        $app = "/* {$this->name($manifest)}: built by pam-native-web. @preact/signals-core is MIT licensed. */\n"
            .file_get_contents(__DIR__.'/../resources/signals-core.min.js')."\n"
            .file_get_contents(__DIR__.'/../resources/runtime.js')."\n"
            .'Object.assign(PAM.config, '.json_encode($config, JSON_THROW_ON_ERROR).");\n"
            ."(function () {\n'use strict';\n".implode("\n", $js)."})();\n";

        if (!is_dir($this->out) && !mkdir($this->out, 0o755, true) && !is_dir($this->out)) {
            throw new RuntimeException("Cannot create {$this->out}.");
        }
        $files = [
            'index.html' => $this->html($manifest),
            'app.js' => $app,
            'app.css' => implode("\n", $css)."\n",
        ];
        $files += $this->packageFiles($manifest);
        $sizes = [];
        foreach ($files as $name => $contents) {
            if (file_put_contents($this->out.'/'.$name, $contents) === false) {
                throw new RuntimeException("Cannot write {$this->out}/{$name}.");
            }
            $sizes[$name] = strlen($contents);
        }

        return ['files' => $sizes, 'warnings' => $this->warnings];
    }

    /** @return array<string, mixed> */
    private function manifest(): array
    {
        $path = $this->root.'/pam-native.json';
        if (!is_file($path)) {
            throw new RuntimeException("{$this->root} has no pam-native.json.");
        }
        $manifest = json_decode((string) file_get_contents($path), true, 64, JSON_THROW_ON_ERROR);

        return is_array($manifest) ? $manifest : [];
    }

    private function collect(): void
    {
        $source = $this->root.'/src';
        if (!is_dir($source)) {
            return;
        }
        $files = [];
        foreach (new RecursiveIteratorIterator(new RecursiveDirectoryIterator($source, RecursiveDirectoryIterator::SKIP_DOTS)) as $file) {
            if ($file->isFile() && str_ends_with($file->getFilename(), '.php')) {
                $files[] = $file->getPathname();
            }
        }
        sort($files);
        $parsed = [];
        foreach ($files as $file) {
            if (!str_ends_with($file, '.pam.php')) {
                $parsed[$file] = $this->parse($file);
                $this->registerTraits($parsed[$file], $file);
            }
        }
        $variants = [];
        foreach ($files as $file) {
            if (preg_match('/^(.*)\.(tv|phone|tablet)\.pam\.php$/D', $file, $match) === 1) {
                $variants[$match[1].'.pam.php'][$match[2]] = $file;
            }
        }
        foreach ($files as $file) {
            if (preg_match('/\.(tv|phone|tablet)\.pam\.php$/D', $file) === 1) {
                continue;
            }
            if (str_ends_with($file, '.pam.php')) {
                $this->component($file, $variants[$file] ?? []);
                continue;
            }
            $this->declarations($parsed[$file], $file);
        }
        // A class is a component when it extends Component, directly or not.
        foreach ($this->classes as $info) {
            $info->isComponent = $info->isComponent || $this->extendsComponent($info);
        }
    }

    /** @param array<string, string> $variants form => file */
    private function component(string $file, array $variants): void
    {
        [$php, $template, $line, $style, $scoped] = $this->split((string) file_get_contents($file), $file);
        $declared = $this->declarations($this->parseSource($php, $file), $file);
        $class = $declared[0] ?? throw new RuntimeException($this->relative($file).': a .pam.php file declares one component class.');
        $class->isComponent = true;
        $class->template = $template;
        $class->templateLine = $line;
        $class->templateFile = $file;
        $class->style = $style;
        $class->styleScoped = $scoped;
        $form = $this->target === 'web' && !$this->tv ? null : 'tv';
        if ($form !== null && isset($variants[$form])) {
            [, $variantTemplate, $variantLine, $variantStyle, $variantScoped] = $this->split((string) file_get_contents($variants[$form]), $variants[$form]);
            if ($variantTemplate !== null) {
                $class->template = $variantTemplate;
                $class->templateLine = $variantLine;
                $class->templateFile = $variants[$form];
                if (trim($variantStyle) !== '') {
                    $class->style = $variantStyle;
                    $class->styleScoped = $variantScoped;
                }
            }
        }
    }

    /**
     * PHP, template, template line, style and whether the style is scoped.
     *
     * @return array{0: string, 1: ?string, 2: int, 3: string, 4: bool}
     */
    private function split(string $contents, string $file): array
    {
        $templateStart = strpos($contents, '<template>');
        $php = $templateStart === false ? $contents : substr($contents, 0, $templateStart);
        $close = strrpos($php, '?>');
        if ($close !== false) {
            $php = substr($php, 0, $close);
        }
        if (!str_starts_with(ltrim($php), '<?php')) {
            $php = '';
        }
        $template = null;
        $line = 1;
        if ($templateStart !== false) {
            $end = strrpos($contents, '</template>');
            if ($end === false) {
                throw new RuntimeException($this->relative($file).': <template> is not closed.');
            }
            $begin = $templateStart + strlen('<template>');
            $template = substr($contents, $begin, $end - $begin);
            $line = substr_count($contents, "\n", 0, $begin) + 1;
        }
        $style = '';
        $scoped = true;
        if (preg_match('#<style([^>]*)>(.*?)</style>#s', $contents, $match) === 1) {
            $style = $match[2];
            $scoped = !str_contains($match[1], 'global');
        }

        return [$php, $template, $line, $style, $scoped];
    }

    /** @return list<Stmt> */
    private function parse(string $file): array
    {
        return $this->parseSource((string) file_get_contents($file), $file);
    }

    /** @return list<Stmt> */
    private function parseSource(string $source, string $file): array
    {
        if (trim($source) === '') {
            return [];
        }
        try {
            $ast = $this->parser->parse($source) ?? [];
        } catch (\PhpParser\Error $error) {
            throw new RuntimeException($this->relative($file).': '.$error->getMessage());
        }
        $traverser = new NodeTraverser(new NameResolver());

        /** @var list<Stmt> */
        return $traverser->traverse($ast);
    }

    /**
     * @param list<Node> $statements
     * @return list<ClassInfo>
     */
    private function declarations(array $statements, string $file): array
    {
        $found = [];
        foreach ($statements as $statement) {
            if ($statement instanceof Stmt\Namespace_) {
                array_push($found, ...$this->declarations($statement->stmts, $file));
                continue;
            }
            if ($statement instanceof Stmt\Function_) {
                $name = $statement->namespacedName?->toString() ?? $statement->name->toString();
                $this->functions[strtolower($name)] = ['node' => $statement, 'file' => $this->relative($file), 'js' => 'fn_'.Js::name($name)];
                continue;
            }
            if ($statement instanceof Stmt\Trait_) {
                continue;
            }
            if (!($statement instanceof Stmt\Class_ || $statement instanceof Stmt\Enum_ || $statement instanceof Stmt\Interface_)) {
                continue;
            }
            $this->useTraits($statement, $file);
            $name = $statement->namespacedName?->toString() ?? (string) $statement->name;
            $methods = [];
            $properties = [];
            foreach ($statement->stmts as $member) {
                if ($member instanceof Stmt\ClassMethod) {
                    $methods[$member->name->toLowerString()] = $member->isStatic();
                    if ($member->name->toLowerString() === '__construct') {
                        foreach ($member->params as $param) {
                            if ($param->flags !== 0 && $param->var instanceof Node\Expr\Variable && is_string($param->var->name)) {
                                $properties[$param->var->name] = true;
                            }
                        }
                    }
                }
                if ($member instanceof Stmt\Property && !$member->isStatic()) {
                    foreach ($member->props as $property) {
                        $properties[$property->name->toString()] = true;
                    }
                }
            }
            $parent = $statement instanceof Stmt\Class_ && $statement->extends !== null ? $statement->extends->toString() : null;
            $info = new ClassInfo($name, $this->relative($file), $statement, $parent, $statement instanceof Stmt\Enum_, $methods, $properties);
            $info->isInterface = $statement instanceof Stmt\Interface_;
            $interfaces = $statement instanceof Stmt\Interface_ ? $statement->extends
                : ($statement instanceof Stmt\Class_ || $statement instanceof Stmt\Enum_ ? $statement->implements : []);
            $info->interfaces = array_map(static fn (Node\Name $interface): string => $interface->toString(), $interfaces);
            $this->classes[strtolower($name)] = $info;
            $found[] = $info;
        }

        return $found;
    }

    /** @param list<Node> $statements */
    private function registerTraits(array $statements, string $file): void
    {
        foreach ($statements as $statement) {
            if ($statement instanceof Stmt\Namespace_) {
                $this->registerTraits($statement->stmts, $file);
            } elseif ($statement instanceof Stmt\Trait_) {
                $name = $statement->namespacedName?->toString() ?? (string) $statement->name;
                $this->traits[strtolower($name)] = ['node' => $statement, 'file' => $file, 'ready' => false];
            }
        }
    }

    /** Copies the members of the traits a class uses into the class, as PHP does. */
    private function useTraits(Stmt\ClassLike $node, string $file): void
    {
        $own = [];
        foreach ($node->stmts as $member) {
            foreach (self::memberKeys($member) as $key) {
                $own[$key] = true;
            }
        }
        $statements = [];
        foreach ($node->stmts as $member) {
            if (!$member instanceof Stmt\TraitUse) {
                $statements[] = $member;
                continue;
            }
            if ($member->adaptations !== []) {
                throw new RuntimeException($this->relative($file).':'.$member->getStartLine().': trait adaptations (insteadof, as) are not supported in web builds yet.');
            }
            foreach ($member->traits as $traitName) {
                $trait = $this->trait($traitName->toString(), $file, $member->getStartLine());
                foreach ($trait->stmts as $traitMember) {
                    $keys = self::memberKeys($traitMember);
                    if ($keys !== [] && array_intersect_key(array_flip($keys), $own) !== []) {
                        continue;
                    }
                    foreach ($keys as $key) {
                        $own[$key] = true;
                    }
                    [$statements[]] = (new NodeTraverser(new CloningVisitor()))->traverse([$traitMember]);
                }
            }
        }
        $node->stmts = $statements;
    }

    /** @return list<string> */
    private static function memberKeys(Node $member): array
    {
        return match (true) {
            $member instanceof Stmt\ClassMethod => ['m:'.$member->name->toLowerString()],
            $member instanceof Stmt\Property => array_map(static fn ($property): string => 'p:'.$property->name->toString(), $member->props),
            $member instanceof Stmt\ClassConst => array_map(static fn ($constant): string => 'c:'.$constant->name->toString(), $member->consts),
            default => [],
        };
    }

    private function trait(string $name, string $file, int $line): Stmt\Trait_
    {
        $lower = strtolower(ltrim($name, '\\'));
        if (!isset($this->traits[$lower])) {
            $this->loadClassFile($name);
        }
        if (!isset($this->traits[$lower])) {
            throw new RuntimeException($this->relative($file).':'.$line.": trait {$name} is not in src/ or vendor/.");
        }
        if (!$this->traits[$lower]['ready']) {
            $this->traits[$lower]['ready'] = true;
            $this->useTraits($this->traits[$lower]['node'], $this->traits[$lower]['file']);
        }

        return $this->traits[$lower]['node'];
    }

    /**
     * Compiles the Composer classes and functions the app reaches, following
     * references from src/ and the entry script until nothing new turns up.
     */
    private function link(): void
    {
        $queue = [$this->entryAst];
        foreach ($this->classes as $info) {
            $queue[] = [$info->node];
        }
        foreach ($this->functions as $function) {
            $queue[] = [$function['node']];
        }
        $finder = new NodeFinder();
        while ($queue !== []) {
            $nodes = array_pop($queue);
            $before = count($this->classes);
            $functions = count($this->functions);
            foreach ($finder->find($nodes, static fn (Node $node): bool => $node instanceof Expr\New_
                || $node instanceof Expr\StaticCall || $node instanceof Expr\StaticPropertyFetch
                || $node instanceof Expr\ClassConstFetch || $node instanceof Expr\Instanceof_
                || $node instanceof Stmt\Catch_ || $node instanceof Stmt\Class_ || $node instanceof Stmt\Interface_
                || $node instanceof Stmt\Enum_ || $node instanceof Expr\FuncCall) as $node) {
                $names = match (true) {
                    $node instanceof Stmt\Catch_ => $node->types,
                    $node instanceof Stmt\Class_ => [$node->extends, ...$node->implements],
                    $node instanceof Stmt\Interface_ => $node->extends,
                    $node instanceof Stmt\Enum_ => $node->implements,
                    $node instanceof Expr\FuncCall => [],
                    $node instanceof Expr\ClassConstFetch && $node->name instanceof Node\Identifier
                        && $node->name->toLowerString() === 'class' => [],
                    default => [$node->class],
                };
                foreach ($names as $name) {
                    if ($name instanceof Node\Name && !$name->isSpecialClassName()) {
                        $this->loadClassFile($name->toString());
                    }
                }
                if ($node instanceof Expr\FuncCall && $node->name instanceof Node\Name) {
                    $namespaced = $node->name->getAttribute('namespacedName');
                    $this->loadFunction([$namespaced instanceof Node\Name ? $namespaced->toString() : null, $node->name->toString()]);
                }
            }
            foreach (array_slice($this->classes, $before) as $info) {
                $queue[] = [$info->node];
            }
            foreach (array_slice($this->functions, $functions) as $function) {
                $queue[] = [$function['node']];
            }
        }
    }

    private function loadClassFile(string $name): void
    {
        $name = ltrim($name, '\\');
        $lower = strtolower($name);
        if (isset($this->classes[$lower]) || isset($this->traits[$lower]) || str_starts_with($lower, 'pam\\native\\')
            || in_array($name, Stdlib::EXCEPTIONS, true)) {
            return;
        }
        $file = $this->composer->find($name);
        if ($file === null) {
            return;
        }
        $real = realpath($file) ?: $file;
        if (isset($this->loaded[$real])) {
            return;
        }
        $this->loaded[$real] = true;
        $statements = $this->parse($file);
        $this->registerTraits($statements, $file);
        $this->declarations($statements, $file);
    }

    /** @param list<?string> $candidates */
    private function loadFunction(array $candidates): void
    {
        if ($this->vendorFunctions === null) {
            $this->vendorFunctions = [];
            foreach ($this->composer->files as $file) {
                // Polyfills declare functions inside `if (!function_exists())`.
                foreach ((new NodeFinder())->findInstanceOf($this->parse($file), Stmt\Function_::class) as $function) {
                    $name = $function->namespacedName?->toString() ?? $function->name->toString();
                    // PAM Native's own functions (signal(), useFormFactor()...) are the runtime's.
                    if (!in_array(strtolower($name), Stdlib::FUNCTIONS, true) && !str_starts_with(strtolower($name), 'pam\\native\\')) {
                        $this->vendorFunctions[strtolower($name)] ??= ['node' => $function, 'file' => $file];
                    }
                }
            }
        }
        foreach (array_filter($candidates) as $candidate) {
            $lower = strtolower(ltrim($candidate, '\\'));
            if (isset($this->functions[$lower])) {
                return;
            }
            if (isset($this->vendorFunctions[$lower])) {
                $function = $this->vendorFunctions[$lower];
                $this->functions[$lower] = ['node' => $function['node'], 'file' => $this->relative($function['file']), 'js' => 'fn_'.Js::name($function['node']->namespacedName?->toString() ?? $function['node']->name->toString())];

                return;
            }
        }
    }

    private function extendsComponent(ClassInfo $info): bool
    {
        $seen = [];
        $parent = $info->parent;
        while ($parent !== null && !isset($seen[$parent])) {
            $seen[$parent] = true;
            if (strtolower(ltrim($parent, '\\')) === 'pam\\native\\component') {
                return true;
            }
            $parent = $this->classes[strtolower(ltrim($parent, '\\'))]->parent ?? null;
        }

        return false;
    }

    /** @return list<ClassInfo> parents before children */
    private function ordered(): array
    {
        $done = [];
        $out = [];
        $visit = function (ClassInfo $info) use (&$visit, &$done, &$out): void {
            if (isset($done[$info->name])) {
                return;
            }
            $done[$info->name] = true;
            foreach ([$info->parent, ...$info->interfaces] as $dependency) {
                if ($dependency !== null && isset($this->classes[strtolower($dependency)])) {
                    $visit($this->classes[strtolower($dependency)]);
                }
            }
            $out[] = $info;
        };
        foreach ($this->classes as $info) {
            $visit($info);
        }

        return $out;
    }

    /**
     * Properties and methods a template can read, inherited ones included.
     *
     * @return array{0: array<string, true>, 1: array<string, true>}
     */
    private function members(ClassInfo $info): array
    {
        $properties = [];
        $methods = [];
        $current = $info;
        while ($current !== null) {
            $properties += $current->properties;
            foreach ($current->node->stmts as $member) {
                if ($member instanceof Stmt\ClassMethod) {
                    $methods[$member->name->toString()] = true;
                }
            }
            $current = $current->parent === null ? null : ($this->classes[strtolower($current->parent)] ?? null);
        }

        return [$properties, $methods];
    }

    /** @param array<string, mixed> $manifest */
    private function html(array $manifest): string
    {
        $title = htmlspecialchars($this->name($manifest), ENT_QUOTES);

        return "<!doctype html>\n<html><head><meta charset=\"utf-8\">"
            ."<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            ."<title>{$title}</title><link rel=\"stylesheet\" href=\"app.css\"></head>"
            ."<body><div id=\"pam\"></div><script src=\"app.js\"></script></body></html>\n";
    }

    /**
     * config.xml for Tizen, appinfo.json for webOS, and the icons.
     *
     * @param array<string, mixed> $manifest
     * @return array<string, string>
     */
    private function packageFiles(array $manifest): array
    {
        $name = $this->name($manifest);
        $id = (string) ($manifest['applicationId'] ?? 'dev.pam.app');
        $version = (string) ($manifest['versionName'] ?? '1.0.0');
        $version = preg_match('/^\d+\.\d+\.\d+$/D', $version) === 1 ? $version : '1.0.0';
        $icon = $this->icon($manifest);
        $files = ['icon.png' => $icon];
        if ($this->target === 'tizen') {
            // Tizen ids: 10 alphanumeric package characters plus the app name.
            $package = substr(preg_replace('/[^A-Za-z0-9]/', '', ucfirst(str_replace('.', '', $id))) ?? 'PamApp', 0, 10);
            $package = str_pad($package, 10, '0');
            $appName = preg_replace('/[^A-Za-z0-9]/', '', $name) ?: 'App';
            $files['config.xml'] = '<?xml version="1.0" encoding="UTF-8"?>'."\n"
                .'<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="https://'.htmlspecialchars($id).'" version="'.$version.'" viewmodes="maximized">'."\n"
                .'    <tizen:application id="'.$package.'.'.$appName.'" package="'.$package.'" required_version="5.0"/>'."\n"
                .'    <content src="index.html"/>'."\n"
                .'    <feature name="http://tizen.org/feature/screen.size.normal.1080.1920"/>'."\n"
                .'    <icon src="icon.png"/>'."\n"
                .'    <name>'.htmlspecialchars($name).'</name>'."\n"
                .'    <tizen:privilege name="http://tizen.org/privilege/tv.inputdevice"/>'."\n"
                .'    <tizen:profile name="tv-samsung"/>'."\n"
                .'    <tizen:setting screen-orientation="landscape" context-menu="disable" background-support="disable" encryption="disable" install-location="auto" hwkey-event="enable"/>'."\n"
                .'</widget>'."\n";
        }
        if ($this->target === 'webos') {
            $files['largeIcon.png'] = $icon;
            $files['appinfo.json'] = json_encode([
                'id' => strtolower($id),
                'version' => $version,
                'vendor' => (string) ($manifest['vendor'] ?? $name),
                'type' => 'web',
                'main' => 'index.html',
                'title' => $name,
                'icon' => 'icon.png',
                'largeIcon' => 'largeIcon.png',
                'resolution' => '1920x1080',
                'disableBackHistoryAPI' => true,
            ], JSON_THROW_ON_ERROR | JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)."\n";
        }

        return $files;
    }

    /** @param array<string, mixed> $manifest */
    private function icon(array $manifest): string
    {
        foreach ([$manifest['web']['icon'] ?? null, $manifest['android']['icon'] ?? null] as $candidate) {
            if (is_string($candidate) && str_ends_with(strtolower($candidate), '.png') && is_file($this->root.'/'.$candidate)) {
                return (string) file_get_contents($this->root.'/'.$candidate);
            }
        }

        return Png::solid(130, 130, 0x11, 0x18, 0x27);
    }

    /** @param array<string, mixed> $manifest */
    private function name(array $manifest): string
    {
        return is_string($manifest['name'] ?? null) ? $manifest['name'] : 'PAM App';
    }

    private function relative(string $file): string
    {
        $root = rtrim($this->root, '/').'/';

        return str_starts_with($file, $root) ? substr($file, strlen($root)) : $file;
    }
}
