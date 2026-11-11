#!/usr/bin/env node
const fs = require('fs'), path = require('path');
// Runs the runtime's PHP functions in node: `node tests/runtime.test.js`.
const dir = path.join(__dirname, '..');
const stub = () => new Proxy(function () {}, { get: (t, k) => k === Symbol.toPrimitive ? () => 0 : (k === 'length' ? 0 : stub()), apply: () => stub(), construct: () => stub(), set: () => true });
global.window = global; global.document = stub(); global.navigator = stub(); global.addEventListener = () => {}; global.matchMedia = stub(); global.localStorage = stub(); global.requestAnimationFrame = () => 0;
(new Function('module', 'exports', 'define', fs.readFileSync(path.join(dir, 'resources/signals-core.min.js'), 'utf8') + '\n' + fs.readFileSync(path.join(dir, 'resources/runtime.js'), 'utf8') + '\nglobal.PAM = PAM;'))();
const f = PAM.f, r = PAM.r, eq = (a, b, m) => { const x = JSON.stringify(a), y = JSON.stringify(b); console.log((x === y ? 'ok  ' : 'FAIL') + ' ' + m + (x === y ? '' : `: ${x} != ${y}`)); };
eq(f.preg_match('/^\\d+$/', '123'), 1, 'match');
eq(r.preg_match('/(?P<y>\\d{4})-(?<m>\\d\\d)/', 'em 2026-11 ok'), [{0: '2026-11', y: '2026', 1: '2026', m: '11', 2: '11'}, 1], 'named groups');
eq(r.preg_match('/(a)(b)?/', 'a'), [['a', 'a'], 1], 'trailing unmatched dropped');
eq(f.preg_replace('/\\s+/', ' ', "a  b\n\tc"), 'a b c', 'replace');
eq(f.preg_replace('/(\\w+) (\\w+)/', '$2 \\1', 'hello world'), 'world hello', 'backrefs');
eq(f.preg_replace_callback('/\\d+/', m => m[0] * 2, 'a1 b20'), 'a2 b40', 'callback');
eq(f.preg_split('/[\\s,]+/', 'a, b  c,d'), ['a', 'b', 'c', 'd'], 'split');
eq(f.preg_split('//', 'abc', -1, 1), ['a', 'b', 'c'], 'split chars');
eq(f.preg_quote('Hello.World?/x', '/'), 'Hello\\.World\\?\\/x', 'quote');
eq(r.preg_match_all('/(\\d)/', 'a1b2'), [[['1', '2'], ['1', '2']], 2], 'match_all');
eq(r.preg_match_all('/(\\d)/', 'a1b2', 2), [[['1', '1'], ['2', '2']], 2], 'set order');
eq(f.preg_match('/HELLO/i', 'hello'), 1, 'i flag');
eq(f.preg_match('/^b$/m', "a\nb"), 1, 'm flag');
eq(f.preg_match('/\\Aab\\z/', 'ab'), 1, 'anchors');
eq(f.preg_match('/ a b # comment\n c/x', 'abc'), 1, 'x flag');
eq(f.preg_match('/\\p{Lu}/u', 'É'), 1, 'unicode');
try { f.preg_match('/(?i)a/', 'A'); console.log('FAIL inline flags accepted'); } catch (e) { console.log('ok   inline flags refused: ' + e.message); }
eq(f.strtr('Hi all', {Hi: 'Hello', all: 'everyone'}), 'Hello everyone', 'strtr pairs');
eq(f.strip_tags('<b>bold</b> text'), 'bold text', 'strip_tags');
eq(f.array_intersect_key({a: 1, b: 2}, {a: 0}), {a: 1}, 'array_intersect_key');
eq(f.ctype_digit('123'), true, 'ctype_digit');
eq(f.base64_decode(f.base64_encode('ação')), 'ação', 'base64 utf-8');
