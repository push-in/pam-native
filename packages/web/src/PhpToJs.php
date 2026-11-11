<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use PhpParser\Node;
use PhpParser\Node\Expr;
use PhpParser\Node\Name;
use PhpParser\Node\Scalar;
use PhpParser\Node\Stmt;
use RuntimeException;

/**
 * Translates the PHP of an app (classes, enums, functions, the entry script)
 * to ES2017. PHP arrays become JS arrays (lists) or plain objects (maps)
 * with copy-on-write assignment, so a component property changes only by
 * assignment, which is what the signals of the runtime observe.
 *
 * What has no faithful translation (references, eval, variable variables,
 * traits, I/O) fails the build with the file and line.
 */
final class PhpToJs
{
    private string $file = '';
    private ?ClassInfo $class = null;
    private bool $static = false;
    /** @var array<string, true> */
    private array $params = [];
    private int $temporaries = 0;

    /** @param array<string, ClassInfo> $classes by lower-case FQCN */
    public function __construct(
        private readonly array $classes,
        /** @var array<string, string> lower-case FQ function name => JS name */
        private readonly array $functions,
    ) {
    }

    // ---- Declarations -------------------------------------------------------

    public function classDeclaration(ClassInfo $info): string
    {
        $this->file = $info->file;
        $this->class = $info;
        $node = $info->node;
        $js = Js::name($info->name);
        $interfaces = [];
        foreach ($info->interfaces as $interface) {
            // Built-in interfaces (Countable, JsonSerializable...) have no JS side.
            if (!isset($this->classes[strtolower(ltrim($interface, '\\'))]) && interface_exists($interface, false)
                && (new \ReflectionClass($interface))->isInternal()) {
                continue;
            }
            $interfaces[] = $this->classReference($interface, $node);
        }
        if ($info->isInterface) {
            $constants = [];
            foreach ($node->stmts as $statement) {
                if ($statement instanceof Stmt\ClassConst) {
                    foreach ($statement->consts as $constant) {
                        $constants[] = Js::string($constant->name->toString()).': function () { return '.$this->constantExpression($constant->value).'; }';
                    }
                }
            }
            $out = "var {$js} = PAM.iface(".Js::string($info->name).', ['.implode(', ', $interfaces)."]);\n";
            if ($constants !== []) {
                $out .= "PAM.consts({$js}, {".implode(', ', $constants)."});\n";
            }
            $this->class = null;

            return $out;
        }
        $parent = $info->parent === null ? 'PAM.Base' : $this->classReference($info->parent, $node);
        if ($info->isEnum) {
            $parent = 'PAM.Base';
        }
        $members = [];
        $instanceProperties = [];
        $staticProperties = [];
        $constants = [];
        $componentProperties = [];

        foreach ($node->stmts as $statement) {
            if ($statement instanceof Stmt\TraitUse) {
                throw $this->error($statement, 'traits are not supported in web builds yet');
            }
            if ($statement instanceof Stmt\ClassConst) {
                foreach ($statement->consts as $constant) {
                    $constants[] = Js::string($constant->name->toString()).': function () { return '.$this->constantExpression($constant->value).'; }';
                }
            }
            if ($statement instanceof Stmt\Property) {
                foreach ($statement->props as $property) {
                    $name = $property->name->toString();
                    $default = $property->default === null
                        ? ($statement->type !== null && !$this->nullableType($statement->type) ? 'undefined' : 'null')
                        : $this->constantExpression($property->default);
                    if ($statement->isStatic()) {
                        $staticProperties[$name] = $default;
                    } elseif ($info->isComponent) {
                        $componentProperties[] = Js::string($name).': function () { return '.$default.'; }';
                    } else {
                        $instanceProperties[$name] = $default;
                    }
                }
            }
        }
        foreach ($node->stmts as $statement) {
            if ($statement instanceof Stmt\ClassMethod) {
                $members[] = $this->method($statement, $info, $instanceProperties);
            }
        }
        if ($instanceProperties !== [] && !$this->hasMethod($node, '__construct')) {
            $assignments = [];
            foreach ($instanceProperties as $name => $default) {
                $assignments[] = "this.{$name} = {$default};";
            }
            $members[] = '__init() { super.__init(); '.implode(' ', $assignments).' }';
        }

        $out = "var {$js} = class {$js} extends {$parent} {\n".implode("\n", array_map(
            static fn (string $member): string => '    '.str_replace("\n", "\n    ", $member),
            $members,
        ))."\n};\n";
        $out .= "{$js}.phpName = ".Js::string($info->name).";\n";
        if ($interfaces !== []) {
            $out .= "PAM.implement({$js}, [".implode(', ', $interfaces)."]);\n";
        }
        if ($constants !== []) {
            $out .= "PAM.consts({$js}, {".implode(', ', $constants)."});\n";
        }
        foreach ($staticProperties as $name => $default) {
            $out .= "{$js}.{$name} = {$default};\n";
        }
        if ($componentProperties !== []) {
            $out .= "PAM.props({$js}, {".implode(', ', $componentProperties)."});\n";
        }
        if ($info->isEnum) {
            $cases = [];
            foreach ($node->stmts as $statement) {
                if ($statement instanceof Stmt\EnumCase) {
                    $cases[] = '['.Js::string($statement->name->toString()).', '
                        .($statement->expr === null ? 'null' : $this->expr($statement->expr)).']';
                }
            }
            $out .= "PAM.enumCases({$js}, [".implode(', ', $cases)."]);\n";
        }
        $this->class = null;

        return $out;
    }

    public function functionDeclaration(Stmt\Function_ $function, string $file, string $jsName): string
    {
        $this->file = $file;
        $this->class = null;
        $this->static = true;

        return "function {$jsName}".$this->functionBody($function->params, $function->stmts, [])."\n";
    }

    /** Top-level statements of the entry script (index.php). */
    public function script(array $statements, string $file): string
    {
        $this->file = $file;
        $this->class = null;
        $this->static = true;
        $this->params = [];
        $kept = [];
        foreach ($this->flatten($statements) as $statement) {
            if ($statement instanceof Stmt\Expression
                && ($statement->expr instanceof Expr\Include_)) {
                continue;
            }
            if ($statement instanceof Stmt\Use_ || $statement instanceof Stmt\GroupUse
                || $statement instanceof Stmt\Declare_ || $statement instanceof Stmt\Nop
                || $statement instanceof Stmt\Class_ || $statement instanceof Stmt\Function_
                || $statement instanceof Stmt\Enum_ || $statement instanceof Stmt\Interface_) {
                continue;
            }
            $kept[] = $statement;
        }

        $this->temporaries = 0;
        $body = $this->statements($kept);

        return '(function () {'.$this->locals($kept, []).$this->temporaryDeclarations()."\n".$body."\n})();\n";
    }

    /**
     * @param array<string, string> $instanceProperties
     */
    private function method(Stmt\ClassMethod $method, ClassInfo $info, array $instanceProperties): string
    {
        $name = $method->name->toString();
        if ($method->stmts === null) {
            return '';
        }
        if ($method->byRef) {
            throw $this->error($method, 'methods returning references are not supported');
        }
        $this->static = $method->isStatic();
        $prefix = [];
        if ($name === '__construct') {
            if ($instanceProperties !== []) {
                $defaults = [];
                foreach ($instanceProperties as $property => $default) {
                    $defaults[] = "this.{$property} = {$default};";
                }
                $prefix[] = implode(' ', $defaults);
            }
            foreach ($method->params as $param) {
                if ($param->flags !== 0) {
                    $variable = $this->paramName($param);
                    $prefix[] = "this.{$variable} = \${$variable};";
                }
            }
        }

        return ($this->static ? 'static ' : '').$name.$this->functionBody($method->params, $method->stmts, $prefix);
    }

    /**
     * @param list<Node\Param> $params
     * @param list<Stmt> $statements
     * @param list<string> $prefix
     */
    private function functionBody(array $params, array $statements, array $prefix): string
    {
        $outerParams = $this->params;
        $outerTemporaries = $this->temporaries;
        $this->params = [];
        $this->temporaries = 0;
        $list = $this->paramList($params);
        $body = $this->statements($statements);
        $locals = $this->locals($statements, $this->params);
        $temporaries = $this->temporaryDeclarations();
        $this->params = $outerParams;
        $this->temporaries = $outerTemporaries;

        return "({$list}) {".$locals.$temporaries
            .($prefix === [] ? '' : "\n    ".implode("\n    ", $prefix))
            ."\n".$this->indent($body)."\n}";
    }

    /** @param list<Node\Param> $params */
    private function paramList(array $params): string
    {
        $out = [];
        foreach ($params as $param) {
            if ($param->byRef) {
                throw $this->error($param, 'parameters by reference are not supported in web builds');
            }
            $name = $this->paramName($param);
            $this->params[$name] = true;
            $code = ($param->variadic ? '...' : '').'$'.$name;
            if ($param->default !== null) {
                $code .= ' = '.$this->expr($param->default);
            }
            $out[] = $code;
        }

        return implode(', ', $out);
    }

    private function paramName(Node\Param $param): string
    {
        if (!$param->var instanceof Expr\Variable || !is_string($param->var->name)) {
            throw $this->error($param, 'invalid parameter');
        }

        return $param->var->name;
    }

    /**
     * `let` for every variable a function body assigns (PHP variables are
     * function-scoped), nested closures excluded.
     *
     * @param list<Node> $statements
     * @param array<string, true> $params
     */
    private function locals(array $statements, array $params): string
    {
        $names = [];
        $visit = function (mixed $node) use (&$visit, &$names): void {
            if (is_array($node)) {
                foreach ($node as $item) {
                    $visit($item);
                }

                return;
            }
            if (!$node instanceof Node) {
                return;
            }
            if ($node instanceof Expr\Closure) {
                foreach ($node->uses as $use) {
                    if (is_string($use->var->name)) {
                        $names[$use->var->name] = true;
                    }
                }

                return;
            }
            if ($node instanceof Expr\ArrowFunction || $node instanceof Stmt\Class_ || $node instanceof Stmt\Function_) {
                return;
            }
            if ($node instanceof Expr\Variable && is_string($node->name) && $node->name !== 'this') {
                $names[$node->name] = true;
            }
            foreach ($node->getSubNodeNames() as $sub) {
                $visit($node->{$sub});
            }
        };
        $visit($statements);
        foreach (array_keys($params) as $param) {
            unset($names[$param]);
        }
        if ($names === []) {
            return '';
        }

        return "\n    let ".implode(', ', array_map(static fn (string $name): string => '$'.$name, array_keys($names))).';';
    }

    private function temporaryDeclarations(): string
    {
        if ($this->temporaries === 0) {
            return '';
        }
        $names = [];
        for ($index = 1; $index <= $this->temporaries; $index++) {
            $names[] = '$$'.$index;
        }

        return "\n    let ".implode(', ', $names).';';
    }

    private function temporary(): string
    {
        return '$$'.++$this->temporaries;
    }

    // ---- Statements ------------------------------------------------------------

    /** @param list<Stmt> $statements */
    private function statements(array $statements): string
    {
        $out = [];
        foreach ($statements as $statement) {
            $code = $this->statement($statement);
            if ($code !== '') {
                $out[] = $code;
            }
        }

        return implode("\n", $out);
    }

    private function statement(Stmt $node): string
    {
        return match (true) {
            $node instanceof Stmt\Expression => $this->expressionStatement($node->expr).';',
            $node instanceof Stmt\Return_ => $node->expr === null ? 'return;' : 'return '.$this->expr($node->expr).';',
            $node instanceof Stmt\If_ => $this->ifStatement($node),
            $node instanceof Stmt\Foreach_ => $this->foreachStatement($node),
            $node instanceof Stmt\For_ => 'for ('.$this->exprList($node->init).'; '
                .($node->cond === [] ? '' : $this->condition(end($node->cond))).'; '
                .$this->exprList($node->loop).") {\n".$this->indent($this->statements($node->stmts))."\n}",
            $node instanceof Stmt\While_ => 'while ('.$this->condition($node->cond).") {\n".$this->indent($this->statements($node->stmts))."\n}",
            $node instanceof Stmt\Do_ => "do {\n".$this->indent($this->statements($node->stmts))."\n} while (".$this->condition($node->cond).');',
            $node instanceof Stmt\Switch_ => $this->switchStatement($node),
            $node instanceof Stmt\Break_ => $this->jump($node, 'break'),
            $node instanceof Stmt\Continue_ => $this->jump($node, 'continue'),
            $node instanceof Stmt\Unset_ => implode("\n", array_map(fn (Expr $var): string => $this->unset($var).';', $node->vars)),
            $node instanceof Stmt\TryCatch => $this->tryStatement($node),
            $node instanceof Stmt\Block => "{\n".$this->indent($this->statements($node->stmts))."\n}",
            $node instanceof Stmt\Nop, $node instanceof Stmt\Declare_, $node instanceof Stmt\Use_,
            $node instanceof Stmt\GroupUse, $node instanceof Stmt\InlineHTML && trim($node->value) === '' => '',
            $node instanceof Stmt\Echo_ => throw $this->error($node, 'echo has no screen in a PAM app; render text in the template'),
            $node instanceof Stmt\Static_ => throw $this->error($node, 'static variables are not supported in web builds'),
            $node instanceof Stmt\Global_ => throw $this->error($node, 'global variables are not supported in web builds'),
            default => throw $this->error($node, $node->getType().' is not supported in web builds'),
        };
    }

    private function expressionStatement(Expr $expr): string
    {
        if ($expr instanceof Expr\Throw_) {
            return 'throw '.$this->expr($expr->expr);
        }

        return $this->expr($expr);
    }

    private function jump(Stmt\Break_|Stmt\Continue_ $node, string $keyword): string
    {
        if ($node->num !== null && (!$node->num instanceof Scalar\Int_ || $node->num->value !== 1)) {
            throw $this->error($node, "{$keyword} with a level is not supported in web builds");
        }

        return $keyword.';';
    }

    private function ifStatement(Stmt\If_ $node): string
    {
        $out = 'if ('.$this->condition($node->cond).") {\n".$this->indent($this->statements($node->stmts))."\n}";
        foreach ($node->elseifs as $elseif) {
            $out .= ' else if ('.$this->condition($elseif->cond).") {\n".$this->indent($this->statements($elseif->stmts))."\n}";
        }
        if ($node->else !== null) {
            $out .= " else {\n".$this->indent($this->statements($node->else->stmts))."\n}";
        }

        return $out;
    }

    private function foreachStatement(Stmt\Foreach_ $node): string
    {
        if ($node->byRef) {
            throw $this->error($node, 'foreach by reference is not supported in web builds; assign the new array instead');
        }
        $entry = $this->temporary();
        $assign = [];
        if ($node->keyVar !== null) {
            $assign[] = $this->assignTo($node->keyVar, "{$entry}[0]").';';
        }
        $assign[] = $this->assignTo($node->valueVar, "{$entry}[1]").';';

        return "for ({$entry} of PAM.entries(".$this->expr($node->expr).")) {\n"
            .$this->indent(implode("\n", $assign)."\n".$this->statements($node->stmts))."\n}";
    }

    private function switchStatement(Stmt\Switch_ $node): string
    {
        $subject = $this->temporary();
        $cases = [];
        foreach ($node->cases as $case) {
            $label = $case->cond === null ? 'default:' : 'case PAM.eq('.$subject.', '.$this->expr($case->cond).'):';
            $cases[] = $label."\n".$this->indent($this->statements($case->stmts));
        }

        return "{$subject} = ".$this->expr($node->cond).";\nswitch (true) {\n".$this->indent(implode("\n", $cases))."\n}";
    }

    private function tryStatement(Stmt\TryCatch $node): string
    {
        $out = "try {\n".$this->indent($this->statements($node->stmts))."\n}";
        if ($node->catches !== []) {
            $error = $this->temporary();
            $branches = [];
            foreach ($node->catches as $catch) {
                $builtIn = [];
                $user = [];
                foreach ($catch->types as $type) {
                    $name = $type->toString();
                    $short = ltrim($name, '\\');
                    if (in_array($short, Stdlib::EXCEPTIONS, true)) {
                        $builtIn[] = Js::string($short);
                    } else {
                        $user[] = "{$error} instanceof ".$this->classReference($name, $type);
                    }
                }
                $test = implode(' || ', array_filter([
                    $builtIn === [] ? '' : "PAM.isa({$error}, [".implode(', ', $builtIn).'])',
                    ...$user,
                ]));
                $bind = $catch->var !== null && is_string($catch->var->name) ? "\$".$catch->var->name." = {$error};\n" : '';
                $branches[] = "if ({$test}) {\n".$this->indent($bind.$this->statements($catch->stmts))."\n}";
            }
            $out .= " catch (e) {\n".$this->indent("{$error} = e;\n".implode(' else ', $branches)." else {\n    throw e;\n}")."\n}";
        }
        if ($node->finally !== null) {
            $out .= " finally {\n".$this->indent($this->statements($node->finally->stmts))."\n}";
        }

        return $out;
    }

    private function unset(Expr $var): string
    {
        if ($var instanceof Expr\ArrayDimFetch) {
            [$base, $keys] = $this->dimChain($var);
            $last = array_pop($keys);
            $target = $this->expr($base);
            if ($keys === []) {
                return "{$target} = PAM.unset({$target}, {$last})";
            }

            return "{$target} = PAM.setIn({$target}, [".implode(', ', $keys)."], PAM.unset(PAM.dig({$target}, [".implode(', ', $keys)."]), {$last}))";
        }
        if ($var instanceof Expr\Variable) {
            return $this->expr($var).' = null';
        }
        if ($var instanceof Expr\PropertyFetch) {
            return 'delete '.$this->expr($var);
        }

        throw $this->error($var, 'unsupported unset()');
    }

    // ---- Expressions ------------------------------------------------------------

    /** @param list<Expr> $exprs */
    private function exprList(array $exprs): string
    {
        return implode(', ', array_map($this->expr(...), $exprs));
    }

    public function expr(Expr $node): string
    {
        return match (true) {
            $node instanceof Expr\Variable => $this->variable($node),
            $node instanceof Scalar\String_ => Js::string($node->value),
            $node instanceof Scalar\Int_ => (string) $node->value,
            $node instanceof Scalar\Float_ => Js::number($node->value),
            $node instanceof Scalar\InterpolatedString => $this->interpolated($node),
            $node instanceof Scalar\MagicConst\Class_ => Js::string($this->class?->name ?? ''),
            $node instanceof Scalar\MagicConst\Function_, $node instanceof Scalar\MagicConst\Method => Js::string(''),
            $node instanceof Scalar\MagicConst\Line => (string) $node->getStartLine(),
            $node instanceof Expr\ConstFetch => $this->constant($node),
            $node instanceof Expr\Array_ => $this->arrayLiteral($node),
            $node instanceof Expr\Assign => $this->assign($node),
            $node instanceof Expr\AssignOp => $this->assignOp($node),
            $node instanceof Expr\BinaryOp => $this->binary($node),
            $node instanceof Expr\BooleanNot => '!'.$this->condition($node->expr),
            $node instanceof Expr\UnaryMinus => '(-'.$this->numeric($node->expr).')',
            $node instanceof Expr\UnaryPlus => $this->numeric($node->expr),
            $node instanceof Expr\BitwiseNot => '(~'.$this->expr($node->expr).')',
            $node instanceof Expr\PreInc => $this->increment($node->var, '+', true),
            $node instanceof Expr\PreDec => $this->increment($node->var, '-', true),
            $node instanceof Expr\PostInc => $this->increment($node->var, '+', false),
            $node instanceof Expr\PostDec => $this->increment($node->var, '-', false),
            $node instanceof Expr\Ternary => $this->ternary($node),
            $node instanceof Expr\Isset_ => '('.implode(' && ', array_map(fn (Expr $var): string => '('.$this->safe($var).' != null)', $node->vars)).')',
            $node instanceof Expr\Empty_ => '!PAM.b('.$this->safe($node->expr).')',
            $node instanceof Expr\ArrayDimFetch => $this->dimFetch($node),
            $node instanceof Expr\PropertyFetch => $this->expr($node->var).$this->propertyName($node->name),
            $node instanceof Expr\NullsafePropertyFetch => 'PAM.dig('.$this->expr($node->var).', ['.$this->propertyKey($node->name).'])',
            $node instanceof Expr\StaticPropertyFetch => $this->classTarget($node->class, $node).$this->propertyName($node->name),
            $node instanceof Expr\ClassConstFetch => $this->classConstant($node),
            $node instanceof Expr\FuncCall => $this->functionCall($node),
            $node instanceof Expr\MethodCall => $this->methodCall($node),
            $node instanceof Expr\NullsafeMethodCall => $this->nullsafeMethodCall($node),
            $node instanceof Expr\StaticCall => $this->staticCall($node),
            $node instanceof Expr\New_ => $this->newExpression($node),
            $node instanceof Expr\Closure => $this->closure($node),
            $node instanceof Expr\ArrowFunction => '(('.$this->arrowParams($node).') => '.$this->expr($node->expr).')',
            $node instanceof Expr\Match_ => $this->matchExpression($node),
            $node instanceof Expr\Cast\Int_ => 'PAM.int('.$this->expr($node->expr).')',
            $node instanceof Expr\Cast\Double => 'PAM.num('.$this->expr($node->expr).')',
            $node instanceof Expr\Cast\String_ => 'PAM.s('.$this->expr($node->expr).')',
            $node instanceof Expr\Cast\Bool_ => 'PAM.b('.$this->expr($node->expr).')',
            $node instanceof Expr\Cast\Array_ => 'PAM.toArray('.$this->expr($node->expr).')',
            $node instanceof Expr\Instanceof_ => '('.$this->expr($node->expr).' instanceof '.$this->classTarget($node->class, $node).')',
            $node instanceof Expr\ErrorSuppress => $this->expr($node->expr),
            $node instanceof Expr\Throw_ => 'PAM.raise('.$this->expr($node->expr).')',
            $node instanceof Expr\Clone_ => 'PAM.clone('.$this->expr($node->expr).')',
            $node instanceof Expr\List_ => throw $this->error($node, 'list() is only supported as an assignment target'),
            default => throw $this->error($node, $node->getType().' is not supported in web builds'),
        };
    }

    private function variable(Expr\Variable $node): string
    {
        if (!is_string($node->name)) {
            throw $this->error($node, 'variable variables are not supported in web builds');
        }
        if ($node->name === 'this') {
            if ($this->static) {
                throw $this->error($node, '$this in a static context');
            }

            return 'this';
        }

        return '$'.$node->name;
    }

    private function interpolated(Scalar\InterpolatedString $node): string
    {
        $parts = [];
        foreach ($node->parts as $part) {
            $parts[] = $part instanceof Node\InterpolatedStringPart
                ? Js::string($part->value)
                : 'PAM.s('.$this->expr($part).')';
        }

        return '('.implode(' + ', $parts === [] ? ['""'] : $parts).')';
    }

    private function constant(Expr\ConstFetch $node): string
    {
        $name = $node->name->toString();
        $lower = strtolower($name);
        if (in_array($lower, ['true', 'false', 'null'], true)) {
            return $lower;
        }
        $short = ltrim($name, '\\');
        if (isset(Stdlib::CONSTANTS[$short])) {
            return Stdlib::CONSTANTS[$short];
        }

        throw $this->error($node, "constant {$name} is not available in web builds");
    }

    /** A literal, built when the class loads; same translation as expr(). */
    private function constantExpression(Expr $node): string
    {
        return $this->expr($node);
    }

    private function arrayLiteral(Expr\Array_ $node): string
    {
        $keyed = false;
        $spread = false;
        foreach ($node->items as $item) {
            if ($item === null) {
                throw $this->error($node, 'empty array item');
            }
            if ($item->byRef) {
                throw $this->error($node, 'array items by reference are not supported in web builds');
            }
            $keyed = $keyed || $item->key !== null;
            $spread = $spread || $item->unpack;
        }
        if (!$keyed) {
            return '['.implode(', ', array_map(
                fn (Node\ArrayItem $item): string => $item->unpack ? '...PAM.values('.$this->expr($item->value).')' : $this->expr($item->value),
                $node->items,
            )).']';
        }
        if ($spread) {
            throw $this->error($node, 'spreading into an array with keys is not supported in web builds; use array_merge()');
        }

        return 'PAM.arr(['.implode(', ', array_map(
            fn (Node\ArrayItem $item): string => '['.($item->key === null ? 'null' : $this->expr($item->key)).', '.$this->expr($item->value).']',
            $node->items,
        )).'])';
    }

    private function assign(Expr\Assign $node): string
    {
        if ($node->var instanceof Expr\List_ || $node->var instanceof Expr\Array_) {
            return '('.$this->destructure($node->var).' = PAM.listOf('.$this->expr($node->expr).'))';
        }

        return $this->assignTo($node->var, $this->expr($node->expr));
    }

    /** Assigns already-translated JS to a PHP assignment target. */
    private function assignTo(Expr $target, string $value): string
    {
        if ($target instanceof Expr\List_ || $target instanceof Expr\Array_) {
            return '('.$this->destructure($target)." = PAM.listOf({$value}))";
        }
        if ($target instanceof Expr\ArrayDimFetch) {
            [$base, $keys] = $this->dimChain($target);
            $root = $this->expr($base);
            if (count($keys) === 1) {
                return "({$root} = PAM.set({$root}, {$keys[0]}, {$value}))";
            }

            return "({$root} = PAM.setIn({$root}, [".implode(', ', $keys)."], {$value}))";
        }
        if ($target instanceof Expr\Variable || $target instanceof Expr\PropertyFetch || $target instanceof Expr\StaticPropertyFetch) {
            return '('.$this->expr($target)." = {$value})";
        }

        throw $this->error($target, 'unsupported assignment target');
    }

    /** JS destructuring pattern for list()/[] targets. */
    private function destructure(Expr\List_|Expr\Array_ $pattern): string
    {
        $keyed = false;
        foreach ($pattern->items as $item) {
            $keyed = $keyed || ($item !== null && $item->key !== null);
        }
        $parts = [];
        foreach ($pattern->items as $item) {
            if ($item === null) {
                $parts[] = '';
                continue;
            }
            $value = $item->value instanceof Expr\List_ || $item->value instanceof Expr\Array_
                ? $this->destructure($item->value)
                : $this->expr($item->value);
            if (!($item->value instanceof Expr\Variable || $item->value instanceof Expr\List_ || $item->value instanceof Expr\Array_ || $item->value instanceof Expr\PropertyFetch)) {
                throw $this->error($item, 'list() items must be variables or properties in web builds');
            }
            $parts[] = $keyed ? '['.$this->expr($item->key ?? throw $this->error($item, 'mixed keyed and positional list()')).']: '.$value : $value;
        }

        return $keyed ? '{'.implode(', ', $parts).'}' : '['.implode(', ', $parts).']';
    }

    private function assignOp(Expr\AssignOp $node): string
    {
        $current = $this->expr($node->var);
        $value = $this->expr($node->expr);
        $result = match (true) {
            $node instanceof Expr\AssignOp\Concat => "PAM.s({$current}) + PAM.s({$value})",
            $node instanceof Expr\AssignOp\Coalesce => 'PAM.co('.$this->safe($node->var).", () => {$value})",
            $node instanceof Expr\AssignOp\Plus => $this->numeric($node->var).' + '.$this->numeric($node->expr),
            $node instanceof Expr\AssignOp\Minus => $this->numeric($node->var).' - '.$this->numeric($node->expr),
            $node instanceof Expr\AssignOp\Mul => $this->numeric($node->var).' * '.$this->numeric($node->expr),
            $node instanceof Expr\AssignOp\Div => $this->numeric($node->var).' / '.$this->numeric($node->expr),
            $node instanceof Expr\AssignOp\Mod => "{$current} % {$value}",
            $node instanceof Expr\AssignOp\Pow => "Math.pow({$current}, {$value})",
            $node instanceof Expr\AssignOp\BitwiseAnd => "{$current} & {$value}",
            $node instanceof Expr\AssignOp\BitwiseOr => "{$current} | {$value}",
            $node instanceof Expr\AssignOp\BitwiseXor => "{$current} ^ {$value}",
            $node instanceof Expr\AssignOp\ShiftLeft => "{$current} << {$value}",
            $node instanceof Expr\AssignOp\ShiftRight => "{$current} >> {$value}",
            default => throw $this->error($node, 'unsupported assignment operator'),
        };

        return $this->assignTo($node->var, $result);
    }

    private function increment(Expr $var, string $sign, bool $pre): string
    {
        if ($var instanceof Expr\Variable || $var instanceof Expr\PropertyFetch || $var instanceof Expr\StaticPropertyFetch) {
            $target = $this->expr($var);

            return $pre ? "({$sign}{$sign}{$target})" : "({$target}{$sign}{$sign})";
        }

        return $this->assignTo($var, '('.$this->numeric($var)." {$sign} 1)");
    }

    private function binary(Expr\BinaryOp $node): string
    {
        $left = $node->left;
        $right = $node->right;

        return match (true) {
            $node instanceof Expr\BinaryOp\Concat => '(PAM.s('.$this->expr($left).') + PAM.s('.$this->expr($right).'))',
            $node instanceof Expr\BinaryOp\Plus => '('.$this->numeric($left).' + '.$this->numeric($right).')',
            $node instanceof Expr\BinaryOp\Minus => '('.$this->numeric($left).' - '.$this->numeric($right).')',
            $node instanceof Expr\BinaryOp\Mul => '('.$this->numeric($left).' * '.$this->numeric($right).')',
            $node instanceof Expr\BinaryOp\Div => '('.$this->numeric($left).' / '.$this->numeric($right).')',
            $node instanceof Expr\BinaryOp\Mod => '('.$this->numeric($left).' % '.$this->numeric($right).')',
            $node instanceof Expr\BinaryOp\Pow => 'Math.pow('.$this->numeric($left).', '.$this->numeric($right).')',
            $node instanceof Expr\BinaryOp\Identical => $this->identical($left, $right, false),
            $node instanceof Expr\BinaryOp\NotIdentical => $this->identical($left, $right, true),
            $node instanceof Expr\BinaryOp\Equal => 'PAM.eq('.$this->expr($left).', '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\NotEqual => '!PAM.eq('.$this->expr($left).', '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\Smaller => $this->compare($left, $right, '<'),
            $node instanceof Expr\BinaryOp\SmallerOrEqual => $this->compare($left, $right, '<='),
            $node instanceof Expr\BinaryOp\Greater => $this->compare($left, $right, '>'),
            $node instanceof Expr\BinaryOp\GreaterOrEqual => $this->compare($left, $right, '>='),
            $node instanceof Expr\BinaryOp\Spaceship => 'PAM.cmp('.$this->expr($left).', '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\BooleanAnd, $node instanceof Expr\BinaryOp\LogicalAnd => '('.$this->condition($left).' && '.$this->condition($right).')',
            $node instanceof Expr\BinaryOp\BooleanOr, $node instanceof Expr\BinaryOp\LogicalOr => '('.$this->condition($left).' || '.$this->condition($right).')',
            $node instanceof Expr\BinaryOp\LogicalXor => '('.$this->condition($left).' !== '.$this->condition($right).')',
            $node instanceof Expr\BinaryOp\Coalesce => 'PAM.co('.$this->safe($left).', () => '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\BitwiseAnd => '('.$this->expr($left).' & '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\BitwiseOr => '('.$this->expr($left).' | '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\BitwiseXor => '('.$this->expr($left).' ^ '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\ShiftLeft => '('.$this->expr($left).' << '.$this->expr($right).')',
            $node instanceof Expr\BinaryOp\ShiftRight => '('.$this->expr($left).' >> '.$this->expr($right).')',
            default => throw $this->error($node, 'unsupported operator'),
        };
    }

    private function identical(Expr $left, Expr $right, bool $negate): string
    {
        foreach ([[$left, $right], [$right, $left]] as [$a, $b]) {
            if ($a instanceof Expr\ConstFetch && strtolower($a->name->toString()) === 'null') {
                return '('.$this->expr($b).($negate ? ' != ' : ' == ').'null)';
            }
        }
        if ($this->scalar($left) || $this->scalar($right)) {
            return '('.$this->expr($left).($negate ? ' !== ' : ' === ').$this->expr($right).')';
        }

        return ($negate ? '!' : '').'PAM.same('.$this->expr($left).', '.$this->expr($right).')';
    }

    private function compare(Expr $left, Expr $right, string $operator): string
    {
        if ($this->isNumeric($left) && $this->isNumeric($right)) {
            return '('.$this->expr($left)." {$operator} ".$this->expr($right).')';
        }

        return '(PAM.cmp('.$this->expr($left).', '.$this->expr($right).") {$operator} 0)";
    }

    private function ternary(Expr\Ternary $node): string
    {
        if ($node->if === null) {
            return '((v) => PAM.b(v) ? v : '.$this->expr($node->else).')('.$this->expr($node->cond).')';
        }

        return '('.$this->condition($node->cond).' ? '.$this->expr($node->if).' : '.$this->expr($node->else).')';
    }

    private function dimFetch(Expr\ArrayDimFetch $node): string
    {
        if ($node->dim === null) {
            throw $this->error($node, 'cannot read $array[]');
        }

        return $this->expr($node->var).'['.$this->expr($node->dim).']';
    }

    /**
     * Base and keys of `$a['x'][1]` / `$this->p['x'][]` (`[]` is PAM.APPEND).
     *
     * @return array{0: Expr, 1: list<string>}
     */
    private function dimChain(Expr\ArrayDimFetch $node): array
    {
        $keys = [];
        $current = $node;
        while ($current instanceof Expr\ArrayDimFetch) {
            array_unshift($keys, $current->dim === null ? 'PAM.APPEND' : $this->expr($current->dim));
            $current = $current->var;
        }
        if (!($current instanceof Expr\Variable || $current instanceof Expr\PropertyFetch || $current instanceof Expr\StaticPropertyFetch)) {
            throw $this->error($node, 'can only write into arrays held by variables or properties');
        }

        return [$current, $keys];
    }

    /** An access that tolerates missing keys and null (isset, empty, ??). */
    private function safe(Expr $node): string
    {
        $keys = [];
        $current = $node;
        while (true) {
            if ($current instanceof Expr\ArrayDimFetch && $current->dim !== null) {
                array_unshift($keys, $this->expr($current->dim));
                $current = $current->var;
                continue;
            }
            if ($current instanceof Expr\PropertyFetch || $current instanceof Expr\NullsafePropertyFetch) {
                array_unshift($keys, $this->propertyKey($current->name));
                $current = $current->var;
                continue;
            }
            break;
        }
        if ($keys === []) {
            return $this->expr($node);
        }

        return 'PAM.dig('.$this->expr($current).', ['.implode(', ', $keys).'])';
    }

    private function propertyName(Node $name): string
    {
        if ($name instanceof Node\Identifier || $name instanceof Node\VarLikeIdentifier) {
            return '.'.$name->toString();
        }

        return '['.$this->expr($name).']';
    }

    private function propertyKey(Node $name): string
    {
        if ($name instanceof Node\Identifier || $name instanceof Node\VarLikeIdentifier) {
            return Js::string($name->toString());
        }

        return $this->expr($name);
    }

    private function classConstant(Expr\ClassConstFetch $node): string
    {
        $constant = $node->name instanceof Node\Identifier ? $node->name->toString() : throw $this->error($node, 'dynamic class constant');
        if (strtolower($constant) === 'class') {
            if ($node->class instanceof Name) {
                $name = $this->resolveClassName($node->class);
                if (isset($this->classes[strtolower($name)])) {
                    return Js::name($name);
                }

                return Js::string($name);
            }

            return 'PAM.className('.$this->expr($node->class).')';
        }

        return $this->classTarget($node->class, $node).'.'.$constant;
    }

    private function functionCall(Expr\FuncCall $node): string
    {
        if (!$node->name instanceof Name) {
            return 'PAM.call('.$this->expr($node->name).', ['.$this->arguments($node).'])';
        }
        $name = $node->name->toString();
        $namespaced = $node->name->getAttribute('namespacedName');
        $candidates = array_filter([
            $namespaced instanceof Name ? $namespaced->toString() : null,
            $name,
        ]);
        foreach ($candidates as $candidate) {
            if (isset($this->functions[strtolower(ltrim($candidate, '\\'))])) {
                return $this->functions[strtolower(ltrim($candidate, '\\'))].'('.$this->arguments($node).')';
            }
        }
        $short = strtolower(ltrim($name, '\\'));
        if ($node->isFirstClassCallable()) {
            return 'PAM.f.'.$short;
        }
        if (($short === 'preg_match' || $short === 'preg_match_all') && count($node->args) >= 3) {
            $matches = $node->args[2];
            if (!$matches instanceof Node\Arg) {
                throw $this->error($node, "{$short}() needs a \$matches variable");
            }
            $result = $this->temporary();
            $rest = array_map(fn (Node\Arg|Node\VariadicPlaceholder $arg): string => $this->argument($arg), [$node->args[0], $node->args[1], ...array_slice($node->args, 3)]);

            return "(({$result} = PAM.r.{$short}(".implode(', ', $rest).')), '.$this->assignTo($matches->value, "{$result}[0]").", {$result}[1])";
        }
        $byReference = ['sort', 'rsort', 'usort', 'uasort', 'uksort', 'ksort', 'krsort', 'asort', 'arsort', 'shuffle',
            'array_push', 'array_pop', 'array_shift', 'array_unshift', 'array_splice'];
        if (in_array($short, $byReference, true)) {
            $target = $node->args[0] ?? null;
            if (!$target instanceof Node\Arg) {
                throw $this->error($node, "{$short}() needs an array");
            }
            $result = $this->temporary();
            $rest = array_slice($node->args, 1);

            return "(({$result} = PAM.r.{$short}(".implode(', ', [$this->expr($target->value), ...array_map(
                fn (Node\Arg|Node\VariadicPlaceholder $arg): string => $this->argument($arg),
                $rest,
            )]).')), '.$this->assignTo($target->value, "{$result}[0]").", {$result}[1])";
        }
        if (in_array($short, ['compact', 'extract', 'eval', 'func_get_args', 'call_user_func_array', 'file_get_contents', 'file_put_contents', 'fopen'], true)) {
            throw $this->error($node, "{$short}() is not available in web builds");
        }
        if ($short === 'call_user_func') {
            $args = $node->args;
            $callable = array_shift($args);

            return 'PAM.call('.$this->argument($callable).', ['.implode(', ', array_map($this->argument(...), $args)).'])';
        }
        if ($short === 'array_map' || $short === 'array_filter' || $short === 'array_reduce' || $short === 'usort') {
            // String and array callables resolve at runtime (PAM.fn()).
        }
        $namespaced = $namespaced instanceof Name ? $namespaced->toString() : $name;
        if (str_starts_with(strtolower($namespaced), 'pam\\native\\') || str_starts_with(strtolower($name), 'pam\\native\\')) {
            return Stdlib::call(str_starts_with(strtolower($name), 'pam\\native\\') ? $name : $namespaced, $this->argumentList($node), $this->where($node));
        }

        return Stdlib::call($name, $this->argumentList($node), $this->where($node));
    }

    private function methodCall(Expr\MethodCall $node): string
    {
        $name = $node->name instanceof Node\Identifier ? $node->name->toString() : null;
        $target = $this->expr($node->var);
        if ($node->isFirstClassCallable()) {
            return $name === null
                ? "{$target}[".$this->expr($node->name)."].bind({$target})"
                : "{$target}.{$name}.bind({$target})";
        }
        $method = $name === null ? '['.$this->expr($node->name).']' : '.'.$name;

        return $target.$method.'('.$this->arguments($node).')';
    }

    private function nullsafeMethodCall(Expr\NullsafeMethodCall $node): string
    {
        $name = $node->name instanceof Node\Identifier ? $node->name->toString() : throw $this->error($node, 'dynamic nullsafe call');

        return '((o) => o == null ? null : o.'.$name.'('.$this->arguments($node).'))('.$this->expr($node->var).')';
    }

    private function staticCall(Expr\StaticCall $node): string
    {
        $name = $node->name instanceof Node\Identifier ? $node->name->toString() : throw $this->error($node, 'dynamic static call');
        if ($node->class instanceof Name) {
            $class = strtolower($node->class->toString());
            if ($class === 'parent') {
                return 'super.'.$name.'('.$this->arguments($node).')';
            }
            if (($class === 'self' || $class === 'static') && $this->class !== null && !$this->static
                && isset($this->class->methods[strtolower($name)]) && !$this->class->methods[strtolower($name)]) {
                return 'this.'.$name.'('.$this->arguments($node).')';
            }
        }
        $target = $this->classTarget($node->class, $node);
        if ($node->isFirstClassCallable()) {
            return "{$target}.{$name}.bind({$target})";
        }

        return $target.'.'.$name.'('.$this->arguments($node).')';
    }

    private function newExpression(Expr\New_ $node): string
    {
        if ($node->class instanceof Stmt\Class_) {
            throw $this->error($node, 'anonymous classes are not supported in web builds');
        }
        if ($node->class instanceof Name) {
            $name = ltrim($this->resolveClassName($node->class), '\\');
            if (in_array($name, Stdlib::EXCEPTIONS, true)) {
                $args = $this->argumentList($node);

                return 'PAM.Error('.Js::string($name).', '.($args[0] ?? '""').')';
            }
        }

        return 'new '.$this->classTarget($node->class, $node).'('.$this->arguments($node).')';
    }

    private function closure(Expr\Closure $node): string
    {
        if ($node->byRef) {
            throw $this->error($node, 'closures returning references are not supported');
        }
        $outerParams = $this->params;
        $outerTemporaries = $this->temporaries;
        $this->params = [];
        $this->temporaries = 0;
        $list = $this->paramList($node->params);
        $captured = [];
        foreach ($node->uses as $use) {
            if (is_string($use->var->name)) {
                $captured[$use->var->name] = true;
            }
        }
        $body = $this->statements($node->stmts);
        $locals = $this->locals($node->stmts, $this->params + $captured);
        $temporaries = $this->temporaryDeclarations();
        $this->params = $outerParams;
        $this->temporaries = $outerTemporaries;

        return "(({$list}) => {".$locals.$temporaries."\n".$this->indent($body)."\n})";
    }

    private function arrowParams(Expr\ArrowFunction $node): string
    {
        $outer = $this->params;
        $list = $this->paramList($node->params);
        $this->params = $outer + $this->params;

        return $list;
    }

    private function matchExpression(Expr\Match_ $node): string
    {
        $subject = 'm';
        $code = '';
        $default = "PAM.unmatched({$subject})";
        $arms = [];
        foreach ($node->arms as $arm) {
            if ($arm->conds === null) {
                $default = '('.$this->expr($arm->body).')';
                continue;
            }
            $tests = array_map(
                fn (Expr $cond): string => $this->scalar($cond) ? "{$subject} === ".$this->expr($cond) : "PAM.same({$subject}, ".$this->expr($cond).')',
                $arm->conds,
            );
            $arms[] = [implode(' || ', $tests), '('.$this->expr($arm->body).')'];
        }
        $code = $default;
        foreach (array_reverse($arms) as [$test, $body]) {
            $code = "({$test}) ? {$body} : {$code}";
        }

        return "(({$subject}) => {$code})(".$this->expr($node->cond).')';
    }

    private function arguments(Expr\CallLike $node): string
    {
        return implode(', ', $this->argumentList($node));
    }

    /** @return list<string> */
    private function argumentList(Expr\CallLike $node): array
    {
        return array_map($this->argument(...), $node->getRawArgs());
    }

    private function argument(Node\Arg|Node\VariadicPlaceholder $arg): string
    {
        if ($arg instanceof Node\VariadicPlaceholder) {
            throw $this->error($arg, 'unsupported callable');
        }
        if ($arg->name !== null) {
            throw $this->error($arg, 'named arguments are not supported in web builds yet');
        }
        if ($arg->byRef) {
            throw $this->error($arg, 'arguments by reference are not supported in web builds');
        }

        return ($arg->unpack ? '...PAM.values(' : '').$this->expr($arg->value).($arg->unpack ? ')' : '');
    }

    // ---- Classes ------------------------------------------------------------------

    private function classTarget(Node $class, Node $at): string
    {
        if (!$class instanceof Name) {
            if ($class instanceof Expr) {
                return 'PAM.classOf('.$this->expr($class).')';
            }
            throw $this->error($at, 'unsupported class reference');
        }
        $lower = strtolower($class->toString());
        if ($lower === 'self' || $lower === 'static') {
            if ($this->class === null) {
                throw $this->error($at, "{$lower} outside a class");
            }
            if ($lower === 'static' && !$this->static) {
                return 'this.constructor';
            }

            return Js::name($this->class->name);
        }
        if ($lower === 'parent') {
            if ($this->class?->parent === null) {
                throw $this->error($at, 'parent without a parent class');
            }

            return $this->classReference($this->class->parent, $at);
        }

        return $this->classReference($this->resolveClassName($class), $at);
    }

    private function resolveClassName(Name $name): string
    {
        $resolved = $name->getAttribute('resolvedName');

        return ltrim($resolved instanceof Name ? $resolved->toString() : $name->toString(), '\\');
    }

    private function classReference(string $name, Node $at): string
    {
        $name = ltrim($name, '\\');
        if (isset($this->classes[strtolower($name)])) {
            return Js::name($this->classes[strtolower($name)]->name);
        }
        if (str_starts_with($name, 'Pam\\Native\\')) {
            $short = substr($name, strlen('Pam\\Native\\'));
            if (isset(Stdlib::NATIVE[$short])) {
                return Stdlib::NATIVE[$short];
            }
            throw $this->error($at, "{$name} is not available in web builds yet");
        }
        if (in_array($name, Stdlib::EXCEPTIONS, true)) {
            throw $this->error($at, "extend or reference {$name} only through new/catch in web builds");
        }

        throw $this->error($at, "class {$name} is not part of the app (web builds compile src/ and the entry script)");
    }

    private function hasMethod(Stmt\ClassLike $node, string $name): bool
    {
        foreach ($node->stmts as $statement) {
            if ($statement instanceof Stmt\ClassMethod && strtolower($statement->name->toString()) === $name) {
                return true;
            }
        }

        return false;
    }

    // ---- Helpers ----------------------------------------------------------------------

    /** A JS condition with PHP truthiness. */
    private function condition(Expr $node): string
    {
        return $this->isBoolean($node) ? $this->expr($node) : 'PAM.b('.$this->expr($node).')';
    }

    private function numeric(Expr $node): string
    {
        return $this->isNumeric($node) ? $this->expr($node) : 'PAM.num('.$this->expr($node).')';
    }

    private function isBoolean(Expr $node): bool
    {
        if ($node instanceof Expr\ConstFetch) {
            return in_array(strtolower($node->name->toString()), ['true', 'false'], true);
        }
        if ($node instanceof Expr\FuncCall && $node->name instanceof Name) {
            $short = strtolower($node->name->getLast());

            return str_starts_with($short, 'is_') || in_array($short, ['str_contains', 'str_starts_with', 'str_ends_with', 'in_array', 'array_key_exists', 'boolval', 'array_is_list'], true);
        }

        return $node instanceof Expr\BinaryOp\Identical || $node instanceof Expr\BinaryOp\NotIdentical
            || $node instanceof Expr\BinaryOp\Equal || $node instanceof Expr\BinaryOp\NotEqual
            || $node instanceof Expr\BinaryOp\Smaller || $node instanceof Expr\BinaryOp\SmallerOrEqual
            || $node instanceof Expr\BinaryOp\Greater || $node instanceof Expr\BinaryOp\GreaterOrEqual
            || $node instanceof Expr\BinaryOp\BooleanAnd || $node instanceof Expr\BinaryOp\BooleanOr
            || $node instanceof Expr\BinaryOp\LogicalAnd || $node instanceof Expr\BinaryOp\LogicalOr
            || $node instanceof Expr\BinaryOp\LogicalXor || $node instanceof Expr\BooleanNot
            || $node instanceof Expr\Isset_ || $node instanceof Expr\Empty_ || $node instanceof Expr\Instanceof_
            || $node instanceof Expr\Cast\Bool_;
    }

    private function isNumeric(Expr $node): bool
    {
        if ($node instanceof Scalar\Int_ || $node instanceof Scalar\Float_ || $node instanceof Expr\Cast\Int_ || $node instanceof Expr\Cast\Double) {
            return true;
        }
        if ($node instanceof Expr\UnaryMinus || $node instanceof Expr\UnaryPlus) {
            return true;
        }
        if ($node instanceof Expr\BinaryOp\Plus || $node instanceof Expr\BinaryOp\Minus || $node instanceof Expr\BinaryOp\Mul
            || $node instanceof Expr\BinaryOp\Div || $node instanceof Expr\BinaryOp\Mod || $node instanceof Expr\BinaryOp\Pow) {
            return true;
        }
        if ($node instanceof Expr\FuncCall && $node->name instanceof Name) {
            return in_array(strtolower($node->name->getLast()), ['count', 'strlen', 'mb_strlen', 'intdiv', 'abs', 'round', 'floor', 'ceil', 'intval', 'floatval', 'array_sum', 'random_int', 'time'], true);
        }
        if ($node instanceof Expr\Variable && is_string($node->name) && isset($this->params[$node->name])) {
            // Typed numeric parameters (int $index) are numbers already.
            return false;
        }

        return false;
    }

    private function scalar(Expr $node): bool
    {
        return $node instanceof Scalar\String_ || $node instanceof Scalar\Int_ || $node instanceof Scalar\Float_
            || ($node instanceof Expr\ConstFetch && in_array(strtolower($node->name->toString()), ['true', 'false'], true))
            || $node instanceof Expr\ClassConstFetch && $node->name instanceof Node\Identifier && strtolower($node->name->toString()) === 'class';
    }

    private function nullableType(Node $type): bool
    {
        return $type instanceof Node\NullableType
            || ($type instanceof Node\UnionType && array_filter($type->types, static fn (Node $t): bool => $t instanceof Node\Identifier && $t->toLowerString() === 'null') !== [])
            || ($type instanceof Node\Identifier && in_array($type->toLowerString(), ['mixed', 'null'], true));
    }

    /**
     * @param list<Stmt> $statements
     * @return list<Stmt>
     */
    private function flatten(array $statements): array
    {
        $out = [];
        foreach ($statements as $statement) {
            if ($statement instanceof Stmt\Namespace_) {
                array_push($out, ...$this->flatten($statement->stmts));
                continue;
            }
            $out[] = $statement;
        }

        return $out;
    }

    private function indent(string $code): string
    {
        return $code === '' ? '' : '    '.str_replace("\n", "\n    ", $code);
    }

    private function where(Node $node): string
    {
        return $this->file.':'.$node->getStartLine();
    }

    private function error(Node $node, string $message): RuntimeException
    {
        return new RuntimeException($this->where($node).': '.$message.'.');
    }
}
