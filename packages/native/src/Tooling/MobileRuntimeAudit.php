<?php

declare(strict_types=1);

namespace Pam\Native\Tooling;

use Pam\Native\Polyfill\Mbstring;
use PhpToken;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use ReflectionClass;
use ReflectionFunction;

/**
 * Build-time check for PHP the mobile runtimes cannot run.
 *
 * PAM's Android and iOS PHP builds only compile the extensions listed in the
 * runtime catalog (ctype, filter, opcache, phar, session, tokenizer, uri plus
 * PHP's always-on core). ext-mbstring is covered by the SDK polyfill; any other
 * missing extension (intl, iconv, gd, curl, dom, pdo...) fails at runtime with
 * "Call to undefined function" on the device only. The audit tokenizes the
 * application and its Composer packages, resolves every function call and
 * class use (new, ::, extends, implements) and reports those that come from an
 * extension the runtime lacks and that no scanned file or polyfill declares.
 * Calls guarded by function_exists()/class_exists()/extension_loaded() in the
 * same file are skipped. The pam-native CLI runs it while staging a build and
 * prints the findings as warnings.
 */
final class MobileRuntimeAudit
{
    /** Extensions every PAM mobile runtime has, whatever the catalog lists. */
    public const array CORE_EXTENSIONS = ['core', 'standard', 'date', 'pcre', 'hash', 'json', 'random', 'reflection', 'spl'];

    /** Catalog extensions of the default PHP 8.5 mobile runtime. */
    public const array DEFAULT_EXTENSIONS = ['ctype', 'filter', 'opcache', 'phar', 'session', 'tokenizer', 'uri'];

    /**
     * Extension of well-known symbols, used when the host PHP running the
     * audit does not load that extension itself.
     */
    private const array KNOWN_FUNCTION_PREFIXES = [
        'grapheme_' => 'intl', 'idn_to_' => 'intl', 'normalizer_' => 'intl', 'numfmt_' => 'intl',
        'collator_' => 'intl', 'datefmt_' => 'intl', 'transliterator_' => 'intl', 'intlcal_' => 'intl',
        'intltz_' => 'intl', 'locale_' => 'intl', 'msgfmt_' => 'intl', 'resourcebundle_' => 'intl',
        'iconv' => 'iconv', 'ob_iconv_handler' => 'iconv', 'mb_ereg' => 'mbstring', 'mb_eregi' => 'mbstring',
        'mb_split' => 'mbstring', 'mb_regex_' => 'mbstring', 'mb_convert_kana' => 'mbstring',
        'mb_encode_mimeheader' => 'mbstring', 'mb_decode_mimeheader' => 'mbstring', 'mb_send_mail' => 'mbstring',
        'mb_http_' => 'mbstring', 'mb_parse_str' => 'mbstring', 'mb_output_handler' => 'mbstring',
        'gz' => 'zlib', 'zlib_' => 'zlib', 'curl_' => 'curl', 'openssl_' => 'openssl', 'sodium_' => 'sodium',
        'image' => 'gd', 'bc' => 'bcmath', 'gmp_' => 'gmp', 'simplexml_' => 'simplexml', 'xml_' => 'xml',
        'libxml_' => 'libxml', 'exif_' => 'exif', 'finfo_' => 'fileinfo', 'mime_content_type' => 'fileinfo',
        'posix_' => 'posix', 'pcntl_' => 'pcntl', 'socket_' => 'sockets', 'sqlite_' => 'sqlite3',
    ];

    private const array KNOWN_CLASSES = [
        'intlchar' => 'intl', 'normalizer' => 'intl', 'numberformatter' => 'intl', 'collator' => 'intl',
        'intldateformatter' => 'intl', 'transliterator' => 'intl', 'locale' => 'intl',
        'messageformatter' => 'intl', 'resourcebundle' => 'intl', 'spoofchecker' => 'intl',
        'intlbreakiterator' => 'intl', 'intlcalendar' => 'intl', 'intltimezone' => 'intl',
        'uconverter' => 'intl', 'domdocument' => 'dom', 'simplexmlelement' => 'simplexml',
        'xmlreader' => 'xmlreader', 'xmlwriter' => 'xmlwriter', 'pdo' => 'pdo', 'sqlite3' => 'sqlite3',
        'ziparchive' => 'zip', 'finfo' => 'fileinfo', 'curlfile' => 'curl', 'gdimage' => 'gd',
    ];

    /** Package directories that never run on the device. */
    private const array SKIPPED_DIRECTORIES = [
        'tests', 'test', 'Tests', 'benchmarks', 'examples', 'docs', 'bin', 'scripts', 'fuzz', 'stubs',
    ];

    /** @var array<string, true> */
    private array $available;

    /** @var array<string, true> lowercase fully qualified function names declared in scanned code */
    private array $functions = [];

    /** @var array<string, true> lowercase fully qualified class names declared in scanned code */
    private array $classes = [];

    /** @var list<array{file: string, line: int, kind: string, display: string, candidates: list<string>, guards: string}> */
    private array $uses = [];

    /** @param list<string> $extensions catalog extensions of the target runtime */
    public function __construct(array $extensions = self::DEFAULT_EXTENSIONS)
    {
        $this->available = [];
        foreach ([...self::CORE_EXTENSIONS, ...$extensions] as $extension) {
            $this->available[self::extensionKey($extension)] = true;
        }
    }

    /**
     * CLI entry used by `pam-native` while staging a build: prints one warning
     * per finding to STDERR and always returns 0 (findings never block a build).
     */
    public static function run(string $root, string $extensions = ''): int
    {
        $list = array_values(array_filter(array_map('trim', explode(',', $extensions)), static fn (string $name): bool => $name !== ''));
        $findings = (new self($list === [] ? self::DEFAULT_EXTENSIONS : $list))->auditDirectory($root);
        foreach ($findings as $finding) {
            fwrite(STDERR, 'PAM Native warning: '.$finding."\n");
        }
        if ($findings !== []) {
            fwrite(STDERR, sprintf(
                "PAM Native warning: %d call(s) above use PHP extensions the Android/iOS runtime does not include; they fail on the device. See docs/platform-runtime.md#php-extensions.\n",
                count($findings),
            ));
        }

        return 0;
    }

    /** @return list<string> findings as "path:line: message" */
    public function auditDirectory(string $root): array
    {
        $root = rtrim($root, '/');
        $files = [];
        $iterator = new RecursiveIteratorIterator(
            new \RecursiveCallbackFilterIterator(
                new RecursiveDirectoryIterator($root, RecursiveDirectoryIterator::SKIP_DOTS),
                static function (\SplFileInfo $file) use ($root): bool {
                    $relative = substr($file->getPathname(), strlen($root) + 1);
                    $parts = explode('/', $relative);
                    if (str_starts_with($file->getFilename(), '.') || in_array($parts[0], ['pam-prebuilt', 'node_modules'], true)) {
                        return false;
                    }
                    if ($file->isDir()) {
                        if ($parts[0] === 'vendor') {
                            if (($parts[1] ?? '') === 'composer' || ($parts[1] ?? '') === 'bin') {
                                return false;
                            }
                            if (count($parts) >= 4 && in_array($parts[count($parts) - 1], self::SKIPPED_DIRECTORIES, true)) {
                                return false;
                            }
                            // The SDK guards its own optional extension use and is tested for it.
                            if (($parts[1] ?? '') === 'pushinbr' && ($parts[2] ?? '') === 'pam-native') {
                                return false;
                            }
                        } elseif (in_array($parts[count($parts) - 1], ['tests', 'Tests', 'scripts', 'benchmarks'], true)) {
                            return false;
                        }

                        return true;
                    }

                    return str_ends_with($relative, '.php') || str_ends_with($relative, '.pam');
                },
            ),
        );
        foreach ($iterator as $file) {
            $files[] = $file->getPathname();
        }
        sort($files);
        foreach ($files as $path) {
            $this->scan(substr($path, strlen($root) + 1), (string) file_get_contents($path));
        }

        return $this->findings();
    }

    /** Adds one file's declarations and uses. */
    public function scan(string $path, string $source): void
    {
        try {
            $tokens = PhpToken::tokenize($source);
        } catch (\Throwable) {
            return;
        }
        // A file that probes an extension (extension_loaded('intl'),
        // function_exists('iconv'), class_exists(\Normalizer::class)...) is
        // trusted to guard every use of that extension.
        $literals = [];
        $code = array_values(array_filter(
            $tokens,
            static fn (PhpToken $token): bool => !$token->is([T_WHITESPACE, T_COMMENT, T_DOC_COMMENT, T_INLINE_HTML, T_OPEN_TAG, T_CLOSE_TAG]),
        ));
        foreach ($code as $position => $token) {
            if ($token->is(T_CONSTANT_ENCAPSED_STRING)) {
                $literals[] = strtolower(trim(substr($token->text, 1, -1), '\\'));
            } elseif ($token->is([T_STRING, T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED])
                && ($code[$position + 1] ?? null)?->is(T_DOUBLE_COLON) && ($code[$position + 2] ?? null)?->is(T_CLASS)
                && ($code[$position - 1] ?? null)?->text === '('
                && in_array(strtolower(ltrim(($code[$position - 2] ?? null)?->text ?? '', '\\')), ['class_exists', 'interface_exists', 'enum_exists'], true)) {
                $literals[] = strtolower(ltrim($token->text, '\\'));
            }
        }
        $guardList = [];
        foreach (array_unique($literals) as $literal) {
            if ($literal === '' || preg_match('/^[a-z_][a-z0-9_\\\\]*$/', $literal) !== 1) {
                continue;
            }
            $guardList[] = self::extensionKey($literal);
            $extension = $this->functionExtension($literal) ?? $this->classExtension($literal);
            if ($extension !== null) {
                $guardList[] = self::extensionKey($extension);
            }
        }
        $guards = '|'.implode('|', array_unique($guardList)).'|';
        $namespace = '';
        $classImports = [];
        $functionImports = [];
        $braces = [];
        $pendingClassBody = false;
        $count = count($code);
        for ($index = 0; $index < $count; $index++) {
            $token = $code[$index];
            $previous = $code[$index - 1] ?? null;
            $next = $code[$index + 1] ?? null;
            if ($token->is(T_NAMESPACE) && $next !== null && $next->is([T_STRING, T_NAME_QUALIFIED])) {
                $namespace = $next->text;
                $classImports = [];
                $functionImports = [];
                continue;
            }
            if ($token->is(T_NAMESPACE) && $next?->text === '{') {
                $namespace = '';
                continue;
            }
            // Imports; a closure's `use (` and a trait `use` inside a class are not.
            if ($token->is(T_USE) && $next?->text !== '(' && !in_array('class', $braces, true)) {
                $index = $this->readImports($code, $index, $classImports, $functionImports);
                continue;
            }
            if ($token->text === '{' || $token->is([T_CURLY_OPEN, T_DOLLAR_OPEN_CURLY_BRACES])) {
                $braces[] = $pendingClassBody ? 'class' : 'block';
                $pendingClassBody = false;
                continue;
            }
            if ($token->text === '}') {
                array_pop($braces);
                continue;
            }
            if ($token->is([T_CLASS, T_INTERFACE, T_TRAIT, T_ENUM]) && !$previous?->is(T_DOUBLE_COLON)) {
                $pendingClassBody = true;
                if ($next !== null && $next->is(T_STRING) && !$previous?->is(T_NEW)) {
                    $this->classes[strtolower(ltrim($namespace.'\\'.$next->text, '\\'))] = true;
                }
                continue;
            }
            if ($token->is(T_FUNCTION) && $next !== null && $next->is(T_STRING) && ($code[$index + 2] ?? null)?->text === '(') {
                if (end($braces) !== 'class') {
                    $this->functions[strtolower(ltrim($namespace.'\\'.$next->text, '\\'))] = true;
                }
                $index++;
                continue;
            }
            if ($token->is([T_EXTENDS, T_IMPLEMENTS])) {
                for ($cursor = $index + 1; $cursor < $count && $code[$cursor]->text !== '{'; $cursor++) {
                    if ($code[$cursor]->is([T_STRING, T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED])) {
                        $this->recordClass($path, $code[$cursor], $namespace, $classImports, $guards);
                    }
                }
                continue;
            }
            if (!$token->is([T_STRING, T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED])) {
                continue;
            }
            if ($previous?->is(T_NEW) || ($next?->is(T_DOUBLE_COLON) && !($code[$index + 2] ?? null)?->is(T_CLASS))) {
                $this->recordClass($path, $token, $namespace, $classImports, $guards);
                continue;
            }
            if ($next?->text !== '(' || $previous?->is([T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_CONST])
                || $previous?->text === '#[' || $previous?->is(T_ATTRIBUTE)) {
                continue;
            }
            $name = $token->text;
            if ($token->is(T_NAME_FULLY_QUALIFIED)) {
                $candidates = [strtolower(substr($name, 1))];
            } elseif ($token->is(T_NAME_QUALIFIED)) {
                $first = strtolower(strstr($name, '\\', true));
                $prefix = $classImports[$first] ?? ltrim($namespace.'\\'.strstr($name, '\\', true), '\\');
                $candidates = [strtolower($prefix.strstr($name, '\\'))];
            } elseif (isset($functionImports[strtolower($name)])) {
                $candidates = [$functionImports[strtolower($name)]];
            } else {
                $candidates = array_values(array_unique(array_filter([
                    $namespace === '' ? '' : strtolower($namespace.'\\'.$name),
                    strtolower($name),
                ])));
            }
            $this->uses[] = [
                'file' => $path,
                'line' => $token->line,
                'kind' => 'function',
                'display' => end($candidates).'()',
                'candidates' => $candidates,
                'guards' => $guards,
            ];
        }
    }

    /** @return list<string> */
    public function findings(): array
    {
        $findings = [];
        $seen = [];
        foreach ($this->uses as $use) {
            $declared = $use['kind'] === 'function' ? $this->functions : $this->classes;
            foreach ($use['candidates'] as $candidate) {
                if (isset($declared[$candidate])) {
                    continue 2;
                }
            }
            $global = end($use['candidates']);
            $extension = $use['kind'] === 'function' ? $this->functionExtension($global) : $this->classExtension($global);
            if ($extension === null || isset($this->available[self::extensionKey($extension)])) {
                continue;
            }
            if (str_contains($use['guards'], '|'.self::extensionKey($extension).'|')) {
                continue;
            }
            $key = $use['file'].'|'.$global;
            if (isset($seen[$key])) {
                continue;
            }
            $seen[$key] = true;
            $findings[] = sprintf(
                '%s:%d: %s %s comes from ext-%s, which the PAM mobile PHP runtime (Android/iOS) does not include',
                $use['file'],
                $use['line'],
                $use['kind'],
                $use['display'],
                strtolower($extension),
            );
        }

        return $findings;
    }

    private function functionExtension(string $name): ?string
    {
        if (in_array($name, Mbstring::FUNCTIONS, true)) {
            return null;
        }
        if (function_exists($name)) {
            $function = new ReflectionFunction($name);
            if (!$function->isInternal()) {
                return null;
            }
            $extension = $function->getExtensionName();

            return is_string($extension) ? $extension : null;
        }
        foreach (self::KNOWN_FUNCTION_PREFIXES as $prefix => $extension) {
            if (str_starts_with($name, $prefix)) {
                return $extension;
            }
        }

        return null;
    }

    private function classExtension(string $name): ?string
    {
        if (class_exists($name, false) || interface_exists($name, false) || enum_exists($name, false)) {
            $class = new ReflectionClass($name);
            if (!$class->isInternal()) {
                return null;
            }
            $extension = $class->getExtensionName();

            return is_string($extension) ? $extension : null;
        }

        return self::KNOWN_CLASSES[$name] ?? null;
    }

    /**
     * @param array<string, string> $classImports
     */
    private function recordClass(string $path, PhpToken $token, string $namespace, array $classImports, string $guards): void
    {
        $name = $token->text;
        if (in_array(strtolower($name), ['self', 'static', 'parent'], true)) {
            return;
        }
        if ($token->is(T_NAME_FULLY_QUALIFIED)) {
            $resolved = substr($name, 1);
        } else {
            $first = strtolower(explode('\\', $name)[0]);
            $resolved = isset($classImports[$first])
                ? $classImports[$first].substr($name, strlen($first))
                : ltrim($namespace.'\\'.$name, '\\');
        }
        $this->uses[] = [
            'file' => $path,
            'line' => $token->line,
            'kind' => 'class',
            'display' => $resolved,
            'candidates' => [strtolower($resolved)],
            'guards' => $guards,
        ];
    }

    /**
     * Reads a top-level `use` statement (classes, `use function`, groups).
     *
     * @param list<PhpToken> $code
     * @param array<string, string> $classImports
     * @param array<string, string> $functionImports
     */
    private function readImports(array $code, int $index, array &$classImports, array &$functionImports): int
    {
        $kind = 'class';
        $cursor = $index + 1;
        if (($code[$cursor] ?? null)?->is(T_FUNCTION)) {
            $kind = 'function';
            $cursor++;
        } elseif (($code[$cursor] ?? null)?->is(T_CONST)) {
            $kind = 'const';
            $cursor++;
        }
        $prefix = '';
        $current = '';
        $alias = null;
        $count = count($code);
        $commit = static function () use (&$current, &$alias, &$prefix, $kind, &$classImports, &$functionImports): void {
            if ($current === '') {
                return;
            }
            $full = ltrim($prefix.$current, '\\');
            $short = strtolower($alias ?? substr($full, (int) strrpos('\\'.$full, '\\')));
            if ($kind === 'function') {
                $functionImports[$short] = strtolower($full);
            } elseif ($kind === 'class') {
                $classImports[$short] = $full;
            }
            $current = '';
            $alias = null;
        };
        for (; $cursor < $count; $cursor++) {
            $token = $code[$cursor];
            if ($token->text === ';') {
                $commit();

                return $cursor;
            }
            if ($token->text === '{') {
                $prefix = rtrim($current, '\\').'\\';
                $current = '';
                continue;
            }
            if ($token->text === ',' || $token->text === '}') {
                $commit();
                continue;
            }
            if ($token->is(T_AS)) {
                $alias = $code[++$cursor]->text ?? null;
                continue;
            }
            if ($token->is(T_FUNCTION)) {
                continue;
            }
            $current .= $token->text;
        }

        return $cursor;
    }

    private static function extensionKey(string $extension): string
    {
        $key = strtolower($extension);

        return $key === 'zend opcache' ? 'opcache' : $key;
    }
}
