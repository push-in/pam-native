/*
 * PAM Native web runtime: PHP value helpers, the DOM renderer of compiled
 * templates, D-pad focus and remote keys for web-based TVs (Samsung Tizen,
 * LG webOS) and browsers. ES2017 on purpose: TVs from 2019 run Chromium 63.
 * Reactivity is @preact/signals-core (bundled before this file).
 */
var PAM = (function () {
    'use strict';

    var S = preactSignalsCore;
    var signal = S.signal, effect = S.effect, batch = S.batch, untracked = S.untracked;
    var config = { target: 'web', tv: false, overscan: true, width: 960 };
    var g = {};

    // ---- PHP values -------------------------------------------------------

    function isList(value) {
        return Array.isArray(value);
    }

    function isArr(value) {
        return Array.isArray(value) || (value !== null && typeof value === 'object' && value.constructor === Object);
    }

    /** PHP string conversion. */
    function s(value) {
        if (value === null || value === undefined || value === false) return '';
        if (value === true) return '1';
        if (typeof value === 'number') {
            if (Number.isInteger(value)) return String(value);
            if (!isFinite(value)) return value > 0 ? 'INF' : (value < 0 ? '-INF' : 'NAN');
            return String(parseFloat(value.toPrecision(14)));
        }
        if (typeof value === 'string') return value;
        if (isArr(value)) return 'Array';
        if (value && typeof value.__toString === 'function') return value.__toString();
        return String(value);
    }

    /** PHP truthiness. */
    function b(value) {
        if (value === '0' || value === '' || value === null || value === undefined) return false;
        if (Array.isArray(value)) return value.length > 0;
        if (value !== null && typeof value === 'object' && value.constructor === Object) {
            for (var key in value) return true;
            return false;
        }
        return !!value;
    }

    function key(k) {
        if (typeof k === 'boolean') return k ? 1 : 0;
        if (typeof k === 'number') return Math.trunc(k);
        if (typeof k === 'string' && /^(0|-?[1-9][0-9]*)$/.test(k)) return Number(k);
        return k === null || k === undefined ? '' : k;
    }

    /** Copy-on-write `$a[$k] = $v` (`$k === APPEND` is `$a[] = $v`). */
    var APPEND = {};
    function set(target, k, v) {
        if (target === null || target === undefined) target = [];
        if (Array.isArray(target)) {
            if (k === APPEND || key(k) === target.length) {
                var pushed = target.slice();
                pushed.push(v);
                return pushed;
            }
            var index = key(k);
            if (typeof index === 'number' && index >= 0 && index < target.length) {
                var copy = target.slice();
                copy[index] = v;
                return copy;
            }
            var object = {};
            for (var i = 0; i < target.length; i++) object[i] = target[i];
            object[index] = v;
            return object;
        }
        var result = Object.assign({}, target);
        if (k === APPEND) {
            var next = 0;
            for (var name in result) {
                var n = key(name);
                if (typeof n === 'number' && n >= next) next = n + 1;
            }
            result[next] = v;
        } else {
            result[key(k)] = v;
        }
        return result;
    }

    function setIn(target, keys, v) {
        if (keys.length === 1) return set(target, keys[0], v);
        var head = keys[0];
        var child = head === APPEND || target === null || target === undefined ? undefined : target[key(head)];
        return set(target, head, setIn(child, keys.slice(1), v));
    }

    function unset(target, k) {
        if (!isArr(target)) return target;
        var index = key(k);
        if (Array.isArray(target)) {
            if (typeof index !== 'number' || index < 0 || index >= target.length) return target;
            if (index === target.length - 1) return target.slice(0, -1);
            var object = {};
            for (var i = 0; i < target.length; i++) if (i !== index) object[i] = target[i];
            return object;
        }
        var result = Object.assign({}, target);
        delete result[index];
        return result;
    }

    /** `$a['x']['y']` that tolerates missing keys (isset, ??). */
    function dig(value, keys) {
        for (var i = 0; i < keys.length; i++) {
            if (value === null || value === undefined) return undefined;
            if (typeof value === 'string') {
                value = value.charAt(key(keys[i]));
                continue;
            }
            if (typeof value !== 'object' && typeof value !== 'function') return undefined;
            value = value[keys[i]];
        }
        return value;
    }

    function co(value, fallback) {
        return value === null || value === undefined ? fallback() : value;
    }

    function entries(value) {
        if (Array.isArray(value)) return value.map(function (item, index) { return [index, item]; });
        if (value === null || value === undefined) return [];
        if (typeof value[Symbol.iterator] === 'function') {
            var out = [];
            var index = 0;
            for (var item of value) out.push([index++, item]);
            return out;
        }
        return Object.keys(value).map(function (k) { return [key(k), value[k]]; });
    }

    function values(value) {
        if (Array.isArray(value)) return value;
        return entries(value).map(function (pair) { return pair[1]; });
    }

    /** Array literal with explicit keys: [[key|null, value], ...]. */
    function arr(pairs) {
        var result = [];
        for (var i = 0; i < pairs.length; i++) {
            result = pairs[i][0] === null ? set(result, APPEND, pairs[i][1]) : set(result, pairs[i][0], pairs[i][1]);
        }
        return result;
    }

    function same(a, c) {
        if (a === c) return true;
        if (a === undefined) a = null;
        if (c === undefined) c = null;
        if (a === c) return true;
        if (isArr(a) && isArr(c)) {
            var left = entries(a), right = entries(c);
            if (left.length !== right.length) return false;
            for (var i = 0; i < left.length; i++) {
                if (left[i][0] !== right[i][0] || !same(left[i][1], right[i][1])) return false;
            }
            return true;
        }
        return false;
    }

    function eq(a, c) {
        if (a === undefined) a = null;
        if (c === undefined) c = null;
        if (a === null || c === null) return !b(a === null ? c : a);
        if (typeof a === 'boolean' || typeof c === 'boolean') return b(a) === b(c);
        if (typeof a === 'number' && typeof c === 'string') return c.trim() !== '' && !isNaN(c) ? a === Number(c) : String(a) === c;
        if (typeof c === 'number' && typeof a === 'string') return eq(c, a);
        if (isArr(a) && isArr(c)) {
            var left = entries(a), right = entries(c);
            if (left.length !== right.length) return false;
            var map = {};
            right.forEach(function (pair) { map[pair[0]] = pair[1]; });
            return left.every(function (pair) { return pair[0] in map && eq(pair[1], map[pair[0]]); });
        }
        return a == c;
    }

    function num(value) {
        if (typeof value === 'number') return value;
        if (typeof value === 'boolean') return value ? 1 : 0;
        if (value === null || value === undefined) return 0;
        var parsed = parseFloat(value);
        return isNaN(parsed) ? 0 : parsed;
    }

    function int(value) {
        var n = num(value);
        return isFinite(n) ? Math.trunc(n) : 0;
    }

    function cmp(a, c) {
        if (typeof a === 'string' && typeof c === 'string') return a < c ? -1 : (a > c ? 1 : 0);
        var x = num(a), y = num(c);
        return x < y ? -1 : (x > y ? 1 : 0);
    }

    function PhpError(type, message) {
        var error = new Error(message);
        error.phpType = type;
        error.getMessage = function () { return message; };
        return error;
    }

    function isa(error, types) {
        var type = error && error.phpType ? error.phpType : 'Error';
        if (types.indexOf('Throwable') >= 0 || types.indexOf(type) >= 0) return true;
        var parents = {
            RuntimeException: ['Exception'], LogicException: ['Exception'],
            InvalidArgumentException: ['LogicException', 'Exception'],
            DomainException: ['LogicException', 'Exception'],
            LengthException: ['LogicException', 'Exception'],
            OutOfRangeException: ['LogicException', 'Exception'],
            OutOfBoundsException: ['RuntimeException', 'Exception'],
            RangeException: ['RuntimeException', 'Exception'],
            UnexpectedValueException: ['RuntimeException', 'Exception'],
            UnhandledMatchError: ['Error'], TypeError: ['Error'], ValueError: ['Error'],
            JsonException: ['Exception'],
        };
        return (parents[type] || []).some(function (parent) { return types.indexOf(parent) >= 0; });
    }

    function unmatched(value) {
        throw PhpError('UnhandledMatchError', 'Unhandled match case ' + JSON.stringify(value));
    }

    function sprintf(format) {
        var args = Array.prototype.slice.call(arguments, 1);
        var next = 0;
        return String(format).replace(/%(?:(\d+)\$)?([-+ 0]|'.)*(\d+)?(?:\.(\d+))?([%bcdeEfFgGosuxX])/g, function (match, position, flag, width, precision, type) {
            if (type === '%') return '%';
            var value = args[position ? Number(position) - 1 : next++];
            var flags = match.slice(1).match(/^(?:\d+\$)?((?:[-+ 0]|'.)*)/)[1];
            var pad = ' ', left = false, plus = false;
            for (var i = 0; i < flags.length; i++) {
                var f = flags[i];
                if (f === '-') left = true; else if (f === '+') plus = true; else if (f === '0') pad = '0'; else if (f === "'") pad = flags[++i];
            }
            var out;
            switch (type) {
                case 'd': case 'u': out = String(int(value)); if (plus && int(value) >= 0) out = '+' + out; break;
                case 'f': case 'F': out = num(value).toFixed(precision === undefined ? 6 : Number(precision)); if (plus && num(value) >= 0) out = '+' + out; break;
                case 'e': case 'E': out = num(value).toExponential(precision === undefined ? 6 : Number(precision)).replace(/e([+-])(\d)$/, 'e$1$2'); if (type === 'E') out = out.toUpperCase(); break;
                case 'g': case 'G': out = String(num(value)); break;
                case 'x': out = int(value).toString(16); break;
                case 'X': out = int(value).toString(16).toUpperCase(); break;
                case 'o': out = int(value).toString(8); break;
                case 'b': out = int(value).toString(2); break;
                case 'c': out = String.fromCharCode(int(value)); break;
                default: out = s(value); if (precision !== undefined) out = out.slice(0, Number(precision));
            }
            if (width !== undefined && out.length < Number(width)) {
                var fill = new Array(Number(width) - out.length + 1).join(pad);
                out = left ? out + (pad === '0' ? fill.replace(/0/g, ' ') : fill) : fill + out;
            }
            return out;
        });
    }

    function range(start, end, step) {
        step = Math.abs(step || 1);
        var out = [];
        if (typeof start === 'string' && typeof end === 'string' && start.length === 1 && end.length === 1) {
            var a = start.charCodeAt(0), z = end.charCodeAt(0);
            for (var c = a; a <= z ? c <= z : c >= z; c += a <= z ? step : -step) out.push(String.fromCharCode(c));
            return out;
        }
        if (start <= end) for (var i = start; i <= end; i += step) out.push(i);
        else for (var j = start; j >= end; j -= step) out.push(j);
        return out;
    }

    function jsonValue(value) {
        if (Array.isArray(value)) return value.map(jsonValue);
        if (isArr(value)) {
            var out = {};
            Object.keys(value).forEach(function (k) { out[k] = jsonValue(value[k]); });
            return out;
        }
        if (value && typeof value.jsonSerialize === 'function') return jsonValue(value.jsonSerialize());
        return value === undefined ? null : value;
    }

    function jsonDecode(source, assoc) {
        try {
            return JSON.parse(source);
        } catch (error) {
            return null;
        }
    }

    function preserve(value, test) {
        if (Array.isArray(value)) {
            var all = true;
            var out = {};
            value.forEach(function (item, index) {
                if (test(item, index)) out[index] = item; else all = false;
            });
            return all ? value.slice() : out;
        }
        var result = {};
        Object.keys(value).forEach(function (k) {
            if (test(value[k], key(k))) result[k] = value[k];
        });
        return result;
    }

    /** The PHP standard library functions an app may call. */
    var f = {
        count: function (v) { return Array.isArray(v) ? v.length : (isArr(v) ? Object.keys(v).length : (v && typeof v.count === 'function' ? v.count() : 1)); },
        max: function () { var list = arguments.length === 1 ? values(arguments[0]) : Array.prototype.slice.call(arguments); return list.reduce(function (a, c) { return cmp(c, a) > 0 ? c : a; }); },
        min: function () { var list = arguments.length === 1 ? values(arguments[0]) : Array.prototype.slice.call(arguments); return list.reduce(function (a, c) { return cmp(c, a) < 0 ? c : a; }); },
        abs: function (v) { return Math.abs(num(v)); },
        floor: function (v) { return Math.floor(num(v)); },
        ceil: function (v) { return Math.ceil(num(v)); },
        round: function (v, p) { var m = Math.pow(10, p || 0); var x = num(v) * m; return (x < 0 ? -Math.round(-x) : Math.round(x)) / m; },
        intdiv: function (a, c) { return Math.trunc(int(a) / int(c)); },
        fmod: function (a, c) { return num(a) % num(c); },
        sqrt: function (v) { return Math.sqrt(num(v)); },
        pow: function (a, c) { return Math.pow(num(a), num(c)); },
        intval: int,
        floatval: num,
        strval: s,
        boolval: b,
        is_null: function (v) { return v === null || v === undefined; },
        is_string: function (v) { return typeof v === 'string'; },
        is_int: function (v) { return typeof v === 'number' && Number.isInteger(v); },
        is_float: function (v) { return typeof v === 'number' && !Number.isInteger(v); },
        is_numeric: function (v) { return typeof v === 'number' || (typeof v === 'string' && v.trim() !== '' && !isNaN(v)); },
        is_bool: function (v) { return typeof v === 'boolean'; },
        is_array: isArr,
        array_is_list: function (v) { return Array.isArray(v); },
        strlen: function (v) { return unescape(encodeURIComponent(s(v))).length; },
        mb_strlen: function (v) { return Array.from(s(v)).length; },
        strtoupper: function (v) { return s(v).toUpperCase(); },
        strtolower: function (v) { return s(v).toLowerCase(); },
        mb_strtoupper: function (v) { return s(v).toUpperCase(); },
        mb_strtolower: function (v) { return s(v).toLowerCase(); },
        ucfirst: function (v) { v = s(v); return v.charAt(0).toUpperCase() + v.slice(1); },
        lcfirst: function (v) { v = s(v); return v.charAt(0).toLowerCase() + v.slice(1); },
        ucwords: function (v) { return s(v).replace(/(^|\s)(\S)/g, function (m, a, c) { return a + c.toUpperCase(); }); },
        trim: function (v, chars) { return chars === undefined ? s(v).replace(/^[ \t\n\r\0\x0B]+|[ \t\n\r\0\x0B]+$/g, '') : f.rtrim(f.ltrim(v, chars), chars); },
        ltrim: function (v, chars) { v = s(v); var set = chars === undefined ? ' \t\n\r\0\x0B' : chars; var i = 0; while (i < v.length && set.indexOf(v[i]) >= 0) i++; return v.slice(i); },
        rtrim: function (v, chars) { v = s(v); var set = chars === undefined ? ' \t\n\r\0\x0B' : chars; var i = v.length; while (i > 0 && set.indexOf(v[i - 1]) >= 0) i--; return v.slice(0, i); },
        str_contains: function (h, n) { return s(h).indexOf(s(n)) >= 0; },
        str_starts_with: function (h, n) { return s(h).indexOf(s(n)) === 0; },
        str_ends_with: function (h, n) { h = s(h); n = s(n); return n === '' || h.slice(-n.length) === n; },
        strpos: function (h, n, o) { var i = s(h).indexOf(s(n), o || 0); return i < 0 ? false : i; },
        str_repeat: function (v, t) { return new Array(int(t) + 1).join(s(v)); },
        str_pad: function (v, len, pad, type) { v = s(v); pad = pad === undefined ? ' ' : s(pad); var missing = int(len) - v.length; if (missing <= 0 || pad === '') return v; var fill = function (n) { return new Array(Math.ceil(n / pad.length) + 1).join(pad).slice(0, n); }; if (type === 0) return fill(missing) + v; if (type === 2) return fill(Math.floor(missing / 2)) + v + fill(Math.ceil(missing / 2)); return v + fill(missing); },
        str_replace: function (search, replace, subject) { var from = Array.isArray(search) ? search : [search]; var out = s(subject); from.forEach(function (item, i) { var to = Array.isArray(replace) ? s(replace[i]) : s(replace); out = out.split(s(item)).join(to); }); return out; },
        substr: function (v, start, length) { v = s(v); start = int(start); if (start < 0) start = Math.max(0, v.length + start); var end = length === undefined || length === null ? v.length : (length < 0 ? v.length + int(length) : start + int(length)); return v.slice(start, Math.max(start, end)); },
        mb_substr: function (v, start, length) { return Array.from(s(v)).slice(start, length === undefined || length === null ? undefined : (length < 0 ? length : start + length)).join(''); },
        number_format: function (v, decimals, point, thousands) { decimals = decimals || 0; point = point === undefined ? '.' : point; thousands = thousands === undefined ? ',' : thousands; var fixed = f.round(Math.abs(num(v)), decimals).toFixed(decimals).split('.'); fixed[0] = fixed[0].replace(/\B(?=(\d{3})+(?!\d))/g, thousands); return (num(v) < 0 ? '-' : '') + fixed.join(point); },
        sprintf: sprintf,
        implode: function (glue, pieces) { if (pieces === undefined) { pieces = glue; glue = ''; } return values(pieces).map(s).join(s(glue)); },
        join: function (glue, pieces) { return f.implode(glue, pieces); },
        explode: function (sep, v, limit) { var parts = s(v).split(s(sep)); if (limit !== undefined && limit > 0 && parts.length > limit) parts = parts.slice(0, limit - 1).concat([parts.slice(limit - 1).join(s(sep))]); return parts; },
        nl2br: function (v) { return s(v).replace(/(\r\n|\n|\r)/g, '<br />$1'); },
        htmlspecialchars: function (v) { return s(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;'); },
        array_keys: function (v) { return entries(v).map(function (pair) { return pair[0]; }); },
        array_values: function (v) { return values(v).slice(); },
        array_map: function (callback) { if (callback !== null) callback = fn(callback); var lists = Array.prototype.slice.call(arguments, 1); if (lists.length === 1) { if (Array.isArray(lists[0])) return lists[0].map(function (item) { return callback(item); }); var out = {}; Object.keys(lists[0]).forEach(function (k) { out[k] = callback(lists[0][k]); }); return out; } var columns = lists.map(values); var length = Math.max.apply(null, columns.map(function (c) { return c.length; })); var result = []; for (var i = 0; i < length; i++) result.push(callback === null ? columns.map(function (c) { return c[i] === undefined ? null : c[i]; }) : callback.apply(null, columns.map(function (c) { return c[i] === undefined ? null : c[i]; }))); return result; },
        array_filter: function (v, callback, mode) { if (callback !== undefined && callback !== null) callback = fn(callback); return preserve(v, function (item, k) { return callback === undefined || callback === null ? b(item) : b(mode === 2 ? callback(k) : (mode === 1 ? callback(item, k) : callback(item))); }); },
        array_reduce: function (v, callback, initial) { callback = fn(callback); return values(v).reduce(function (carry, item) { return callback(carry, item); }, initial === undefined ? null : initial); },
        array_merge: function () { var lists = Array.prototype.slice.call(arguments); if (lists.every(Array.isArray)) return [].concat.apply([], lists); var result = []; lists.forEach(function (list) { entries(list).forEach(function (pair) { result = typeof pair[0] === 'number' ? set(result, APPEND, pair[1]) : set(result, pair[0], pair[1]); }); }); return result; },
        array_slice: function (v, offset, length) { var list = values(v); offset = int(offset); if (offset < 0) offset = Math.max(0, list.length + offset); var end = length === undefined || length === null ? list.length : (length < 0 ? list.length + length : offset + length); return list.slice(offset, end); },
        array_reverse: function (v) { return values(v).slice().reverse(); },
        array_search: function (needle, v, strict) { var found = entries(v).filter(function (pair) { return strict ? same(pair[1], needle) : eq(pair[1], needle); })[0]; return found ? found[0] : false; },
        array_key_exists: function (k, v) { return v !== null && v !== undefined && Object.prototype.hasOwnProperty.call(v, Array.isArray(v) ? key(k) : k); },
        array_key_first: function (v) { var e = entries(v); return e.length ? e[0][0] : null; },
        array_key_last: function (v) { var e = entries(v); return e.length ? e[e.length - 1][0] : null; },
        array_column: function (v, column, index) { var result = index === undefined || index === null ? [] : {}; values(v).forEach(function (row) { var value = column === null ? row : row[column]; if (index === undefined || index === null) result.push(value); else result[row[index]] = value; }); return result; },
        array_combine: function (keys, vals) { var result = {}; var k = values(keys), w = values(vals); k.forEach(function (name, i) { result[name] = w[i]; }); return result; },
        array_flip: function (v) { var result = {}; entries(v).forEach(function (pair) { result[pair[1]] = pair[0]; }); return result; },
        array_unique: function (v) { var seen = []; return preserve(v, function (item) { var hit = seen.some(function (other) { return eq(other, item); }); seen.push(item); return !hit; }); },
        array_sum: function (v) { return values(v).reduce(function (a, c) { return a + num(c); }, 0); },
        array_fill: function (start, n, value) { var result = []; for (var i = 0; i < n; i++) result.push(value); return start === 0 ? result : arr(result.map(function (item, i) { return [start + i, item]; })); },
        array_chunk: function (v, size) { var list = values(v); var out = []; for (var i = 0; i < list.length; i += size) out.push(list.slice(i, i + size)); return out; },
        array_push: function () { throw PhpError('Error', 'array_push() takes the array by reference; use $array[] = $value'); },
        in_array: function (needle, v, strict) { return values(v).some(function (item) { return strict ? same(item, needle) : eq(item, needle); }); },
        range: range,
        usort: function () { throw PhpError('Error', 'usort() sorts by reference; assign the result of PAM\'s sorted copy instead'); },
        json_encode: function (v) { return JSON.stringify(jsonValue(v)); },
        json_decode: jsonDecode,
        time: function () { return Math.floor(Date.now() / 1000); },
        microtime: function (asFloat) { return asFloat ? Date.now() / 1000 : String(Date.now() / 1000); },
        hrtime: function (asNumber) { var ns = Math.round(performance.now() * 1e6); return asNumber ? ns : [Math.floor(ns / 1e9), ns % 1e9]; },
        random_int: function (lo, hi) { return lo + Math.floor(Math.random() * (hi - lo + 1)); },
        mt_rand: function (lo, hi) { return lo === undefined ? Math.floor(Math.random() * 2147483647) : lo + Math.floor(Math.random() * (hi - lo + 1)); },
        uniqid: function () { return Date.now().toString(16) + Math.floor(Math.random() * 1e5).toString(16); },
        spl_object_id: (function () { var ids = new WeakMap(); var next = 1; return function (o) { if (!ids.has(o)) ids.set(o, next++); return ids.get(o); }; })(),
        gettype: function (v) { return v === null || v === undefined ? 'NULL' : (typeof v === 'boolean' ? 'boolean' : (typeof v === 'number' ? (Number.isInteger(v) ? 'integer' : 'double') : (typeof v === 'string' ? 'string' : (isArr(v) ? 'array' : 'object')))); },
        get_debug_type: function (v) { return v === null || v === undefined ? 'null' : (typeof v === 'boolean' ? 'bool' : (typeof v === 'number' ? (Number.isInteger(v) ? 'int' : 'float') : (typeof v === 'string' ? 'string' : (isArr(v) ? 'array' : (v.constructor && v.constructor.phpName) || 'object')))); },
        date: function (format, timestamp) { var d = timestamp === undefined ? new Date() : new Date(timestamp * 1000); var pad = function (n) { return n < 10 ? '0' + n : String(n); }; return s(format).replace(/[YmdHisjnDlMy]/g, function (c) { switch (c) { case 'Y': return d.getFullYear(); case 'y': return pad(d.getFullYear() % 100); case 'm': return pad(d.getMonth() + 1); case 'n': return d.getMonth() + 1; case 'd': return pad(d.getDate()); case 'j': return d.getDate(); case 'H': return pad(d.getHours()); case 'i': return pad(d.getMinutes()); case 's': return pad(d.getSeconds()); case 'D': return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getDay()]; case 'l': return ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][d.getDay()]; case 'M': return ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()]; } return c; }); },
    };

    function empty(value) {
        return !b(value);
    }

    /** Resolves PHP callables: closures, 'strtoupper', [$object, 'method']. */
    function fn(callable) {
        if (typeof callable === 'function') return callable;
        if (typeof callable === 'string') {
            if (f[callable.toLowerCase()]) return f[callable.toLowerCase()];
            throw PhpError('Error', callable + '() is not available in web builds');
        }
        if (Array.isArray(callable) && callable.length === 2) {
            var target = callable[0];
            return target[callable[1]].bind(target);
        }
        if (callable && typeof callable.__invoke === 'function') return callable.__invoke.bind(callable);
        throw PhpError('TypeError', 'Value is not callable');
    }

    function call(callable, args) {
        return fn(callable).apply(null, args);
    }

    /** list()/[...] destructuring source: positional values of any PHP array. */
    function listOf(value) {
        if (Array.isArray(value)) return value;
        if (value === null || value === undefined) return [];
        var out = [];
        Object.keys(value).forEach(function (k) { var n = key(k); if (typeof n === 'number' && n >= 0) out[n] = value[k]; });
        return Object.assign(out, value);
    }

    function toArray(value) {
        if (isArr(value)) return value;
        if (value === null || value === undefined) return [];
        if (typeof value === 'object') return Object.assign({}, value);
        return [value];
    }

    function raise(error) {
        throw error;
    }

    function clone(value) {
        var copy = Object.create(Object.getPrototypeOf(value));
        Object.keys(value).forEach(function (k) { if (k !== '__s') copy[k] = value[k]; });
        if (value.__s) Object.keys(value.__s).forEach(function (k) { copy[k] = value.__s[k].peek(); });
        if (typeof copy.__clone === 'function') copy.__clone();
        return copy;
    }

    /** Lazy class constants: built on first read, then fixed. */
    function consts(Class, factories) {
        Object.keys(factories).forEach(function (name) {
            Object.defineProperty(Class, name, {
                configurable: true,
                get: function () {
                    var value = factories[name]();
                    Object.defineProperty(Class, name, { value: value, writable: false });
                    return value;
                },
            });
        });
    }

    /** Enum cases as singletons with name/value, plus cases(), from(), tryFrom(). */
    function enumCases(Class, cases) {
        var all = cases.map(function (pair) {
            var instance = Object.create(Class.prototype);
            instance.name = pair[0];
            if (pair[1] !== null) instance.value = pair[1];
            Object.defineProperty(Class, pair[0], { value: instance });
            return instance;
        });
        Class.cases = function () { return all.slice(); };
        Class.tryFrom = function (value) { return all.filter(function (c) { return c.value === value; })[0] || null; };
        Class.from = function (value) {
            var found = Class.tryFrom(value);
            if (found === null) throw PhpError('ValueError', JSON.stringify(value) + ' is not a valid backing value for enum ' + (Class.phpName || ''));
            return found;
        };
    }

    // PHP interfaces: `x instanceof I` walks the classes' __ifaces lists.
    function iface(name, parents) {
        var I = { phpName: name, __parents: parents };
        Object.defineProperty(I, Symbol.hasInstance, { value: function (value) { return implementsIface(value, I); } });
        return I;
    }

    // A class lists its interfaces and inherits their constants.
    function implement(C, list) {
        C.__ifaces = list;
        (function copy(interfaces) {
            interfaces.forEach(function (I) {
                Object.getOwnPropertyNames(I).forEach(function (key) {
                    if (key === 'phpName' || key === '__parents' || key in C) return;
                    Object.defineProperty(C, key, { configurable: true, get: function () { return I[key]; } });
                });
                copy(I.__parents);
            });
        })(list);
    }

    function extendsIface(candidate, I) {
        if (candidate === I) return true;
        for (var i = 0; i < candidate.__parents.length; i++) if (extendsIface(candidate.__parents[i], I)) return true;
        return false;
    }

    function implementsIface(value, I) {
        if (value === null || typeof value !== 'object') return false;
        for (var C = value.constructor; C && C !== Object; C = Object.getPrototypeOf(C)) {
            var list = Object.prototype.hasOwnProperty.call(C, '__ifaces') ? C.__ifaces : [];
            for (var i = 0; i < list.length; i++) if (extendsIface(list[i], I)) return true;
        }
        return false;
    }

    function className(value) {
        return value && value.constructor && value.constructor.phpName ? value.constructor.phpName : '';
    }

    function classOf(value) {
        return typeof value === 'function' ? value : value.constructor;
    }

    function bound(read) {
        read.__bound = true;
        return read;
    }

    /** By-reference array functions: return [newArray, result]. */
    function sortedBy(list, compare) {
        return list.map(function (pair, i) { return [pair, i]; })
            .sort(function (x, y) { return compare(x[0], y[0]) || x[1] - y[1]; })
            .map(function (pair) { return pair[0]; });
    }
    function byValue(v, compare, keep) {
        var sorted = sortedBy(entries(v), function (x, y) { return compare(x[1], y[1]); });
        return keep ? arr(sorted) : sorted.map(function (pair) { return pair[1]; });
    }
    function byKey(v, compare) {
        return arr(sortedBy(entries(v), function (x, y) { return compare(x[0], y[0]); }));
    }
    // ---- PCRE (preg_*) --------------------------------------------------------
    // Patterns become RegExps once; named groups turn into numbered ones
    // (Chromium 63 has no `(?<name>)`) and their names come back in matches.

    var patterns = {};
    function pcre(pattern) {
        pattern = s(pattern);
        if (patterns[pattern]) return patterns[pattern];
        var open = pattern.charAt(0);
        var close = { '(': ')', '{': '}', '[': ']', '<': '>' }[open] || open;
        var end = pattern.lastIndexOf(close);
        if (end <= 0 || /[A-Za-z0-9\\\s]/.test(open)) throw PhpError('ValueError', 'preg: the pattern ' + JSON.stringify(pattern) + ' has no delimiters');
        var body = pattern.slice(1, end), modifiers = pattern.slice(end + 1), flags = '', extended = false;
        for (var m = 0; m < modifiers.length; m++) {
            var mod = modifiers.charAt(m);
            if ('ims'.indexOf(mod) >= 0) flags += mod;
            else if (mod === 'u') flags += 'u';
            else if (mod === 'x') extended = true;
            else if (mod !== 'D' && mod !== 'S' && mod !== '\n') throw PhpError('ValueError', 'preg: the /' + mod + ' modifier is not available in web builds');
        }
        var names = [], out = '', group = 0, inClass = false;
        for (var i = 0; i < body.length; i++) {
            var c = body.charAt(i);
            if (c === '\\') {
                var next = body.charAt(i + 1);
                if (next === 'A') out += '^';
                else if (next === 'z') out += '(?![\\s\\S])';
                else if (next === 'Z') out += '(?=\\n?(?![\\s\\S]))';
                else if (next === 'h') out += inClass ? ' \\t' : '[ \\t]';
                else if (next === 'k' && /[<{']/.test(body.charAt(i + 2))) {
                    var stop = body.indexOf({ '<': '>', '{': '}', "'": "'" }[body.charAt(i + 2)], i + 3);
                    out += '\\' + (names.indexOf(body.slice(i + 3, stop)) + 1);
                    i = stop;
                    continue;
                } else {
                    if (next === 'p' || next === 'P' || next === 'x' && body.charAt(i + 2) === '{') flags += flags.indexOf('u') < 0 ? 'u' : '';
                    out += c + next;
                }
                i++;
                continue;
            }
            if (inClass) {
                if (c === ']') inClass = false;
                out += c;
                continue;
            }
            if (c === '[') {
                inClass = true;
                out += c;
                if (body.charAt(i + 1) === '^') { out += '^'; i++; }
                if (body.charAt(i + 1) === ']') { out += '\\]'; i++; }
                continue;
            }
            if (extended && /\s/.test(c)) continue;
            if (extended && c === '#') {
                while (i < body.length && body.charAt(i) !== '\n') i++;
                continue;
            }
            if (c === '(') {
                var named = /^\?(?:P?<([A-Za-z_]\w*)>|'([A-Za-z_]\w*)')/.exec(body.slice(i + 1));
                if (named) {
                    names[group++] = named[1] || named[2];
                    out += '(';
                    i += named[0].length;
                    continue;
                }
                if (body.charAt(i + 1) === '?') {
                    var kind = body.charAt(i + 2);
                    if (kind === '>' || kind === '|' || kind === '(' || /[0-9+A-Za-z-]/.test(kind)) {
                        throw PhpError('ValueError', 'preg: (?' + kind + ' groups are not available in web builds');
                    }
                } else if (body.charAt(i + 1) !== '*') {
                    names[group++] = null;
                }
                out += c;
                continue;
            }
            if (c === '+' && /[*+?}]/.test(out.charAt(out.length - 1)) && out.charAt(out.length - 2) !== '\\') {
                throw PhpError('ValueError', 'preg: possessive quantifiers are not available in web builds');
            }
            out += c;
        }
        var compiled = { source: out, flags: flags, names: names, groups: group };
        patterns[pattern] = compiled;
        return compiled;
    }

    function regex(pattern, global) {
        var p = pcre(pattern);
        return { re: new RegExp(p.source, p.flags + (global ? 'g' : '')), names: p.names };
    }

    // PHP's match array: numbered groups, each named group also under its name;
    // trailing groups that did not take part are left out, as PCRE does.
    function groups(match, names, flags) {
        var last = match.length - 1;
        if (!(flags & 512)) while (last > 0 && match[last] === undefined) last--;
        var pairs = [], hasNames = false;
        for (var i = 0; i <= last; i++) {
            var text = match[i] === undefined ? (flags & 512 ? null : '') : match[i];
            var value = flags & 256 ? [text, match[i] === undefined ? -1 : match.index + (i === 0 ? 0 : match[0].indexOf(text))] : text;
            if (i > 0 && names[i - 1]) { pairs.push([names[i - 1], value]); hasNames = true; }
            pairs.push([i, value]);
        }
        return hasNames ? arr(pairs) : pairs.map(function (pair) { return pair[1]; });
    }

    function all(pattern, subject) {
        var r = regex(pattern, true), list = [], match;
        subject = s(subject);
        while ((match = r.re.exec(subject)) !== null) {
            list.push(match);
            if (match[0] === '') r.re.lastIndex++;
        }
        return { matches: list, names: r.names };
    }

    function pregReplace(pattern, replacement, subject, limit) {
        if (isArr(subject)) return arr(entries(subject).map(function (pair) { return [pair[0], pregReplace(pattern, replacement, pair[1], limit)]; }));
        var list = isArr(pattern) ? values(pattern) : [pattern];
        var out = s(subject);
        list.forEach(function (one, index) {
            var r = regex(one, true), count = 0;
            var to = typeof replacement === 'function' ? replacement : (isArr(replacement) ? s(values(replacement)[index]) : s(replacement));
            out = out.replace(r.re, function () {
                var match = Array.prototype.slice.call(arguments, 0, -2);
                match.index = arguments[arguments.length - 2];
                if (limit !== undefined && limit !== null && limit >= 0 && count >= limit) return match[0];
                count++;
                if (typeof to === 'function') return s(to(groups(match, r.names, 0)));
                return to.replace(/\\(\d{1,2})|\$(\d{1,2})|\$\{(\d{1,2})\}/g, function (all, a, b, c) { var v = match[Number(a || b || c)]; return v === undefined ? '' : v; });
            });
        });
        return out;
    }

    var preg = {
        preg_quote: function (text, delimiter) { var special = '.\\+*?[^]$(){}=!<>|:-#' + (delimiter ? s(delimiter) : ''); return s(text).replace(/[\s\S]/g, function (c) { return c === '\0' ? '\\000' : (special.indexOf(c) >= 0 ? '\\' + c : c); }); },
        preg_match: function (pattern, subject) { return regex(pattern, false).re.test(s(subject)) ? 1 : 0; },
        preg_match_all: function (pattern, subject) { return all(pattern, subject).matches.length; },
        preg_replace: function (pattern, replacement, subject, limit) { return pregReplace(pattern, replacement, subject, limit); },
        preg_replace_callback: function (pattern, callback, subject, limit) { return pregReplace(pattern, fn(callback), subject, limit); },
        preg_split: function (pattern, subject, limit, flags) {
            subject = s(subject); flags = flags || 0;
            var found = all(pattern, subject).matches, parts = [], at = 0;
            var push = function (text, offset) { if (flags & 1 && text === '') return; parts.push(flags & 4 ? [text, offset] : text); };
            for (var i = 0; i < found.length; i++) {
                if (limit > 0 && parts.length >= limit - 1) break;
                if (found[i][0] === '' && (found[i].index === 0 || found[i].index === subject.length)) continue;
                push(subject.slice(at, found[i].index), at);
                if (flags & 2) for (var g = 1; g < found[i].length; g++) if (found[i][g] !== undefined) push(found[i][g], found[i].index);
                at = found[i].index + found[i][0].length;
            }
            push(subject.slice(at), at);
            return parts;
        },
        preg_grep: function (pattern, input) { var r = regex(pattern, false); return arr(entries(input).filter(function (pair) { return r.re.test(s(pair[1])); })); },
    };

    // More of PHP's standard library, for Composer packages.
    function ctype(test) { return function (v) { v = typeof v === 'string' ? v : ''; return v !== '' && test.test(v); }; }
    function keyed(test) {
        return function (first) {
            var others = Array.prototype.slice.call(arguments, 1);
            return arr(entries(first).filter(function (pair) { return test(pair, others); }).map(function (pair) { return [pair[0], pair[1]]; }));
        };
    }
    function hasKey(v, k) { return Array.isArray(v) ? typeof k === 'number' && k >= 0 && k < v.length : Object.prototype.hasOwnProperty.call(v, k); }
    function hasValue(v, x) { return values(v).some(function (y) { return s(y) === s(x); }); }
    var entities = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#039;': "'", '&#39;': "'", '&apos;': "'", '&nbsp;': ' ' };
    var more = {
        strtr: function (v, from, to) {
            v = s(v);
            if (to !== undefined) { from = s(from); to = s(to); var n = Math.min(from.length, to.length); return v.replace(/[\s\S]/g, function (c) { var i = from.slice(0, n).indexOf(c); return i < 0 ? c : to.charAt(i); }); }
            var pairs = entries(from).map(function (pair) { return [s(pair[0]), s(pair[1])]; }).filter(function (pair) { return pair[0] !== ''; }).sort(function (a, c) { return c[0].length - a[0].length; });
            var out = '', i = 0;
            outer: while (i < v.length) {
                for (var k = 0; k < pairs.length; k++) if (v.startsWith(pairs[k][0], i)) { out += pairs[k][1]; i += pairs[k][0].length; continue outer; }
                out += v.charAt(i++);
            }
            return out;
        },
        strip_tags: function (v) { return s(v).replace(/<!--[\s\S]*?-->|<\/?[a-zA-Z!?][^>]*>/g, ''); },
        method_exists: function (o, m) { var C = typeof o === 'string' ? null : (typeof o === 'function' ? o : o && o.constructor); return !!(C && (typeof C.prototype[m] === 'function' || typeof C[m] === 'function')); },
        property_exists: function (o, p) { return o !== null && typeof o === 'object' && p in o; },
        is_object: function (v) { return v !== null && typeof v === 'object' && !isArr(v); },
        is_callable: function (v) { try { return typeof fn(v) === 'function'; } catch (e) { return false; } },
        is_iterable: function (v) { return isArr(v) || (v !== null && typeof v === 'object' && typeof v.getIterator === 'function'); },
        is_countable: function (v) { return isArr(v) || (v !== null && typeof v === 'object' && typeof v.count === 'function'); },
        is_scalar: function (v) { return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'; },
        array_intersect_key: keyed(function (pair, others) { return others.every(function (o) { return hasKey(o, pair[0]); }); }),
        array_diff_key: keyed(function (pair, others) { return others.every(function (o) { return !hasKey(o, pair[0]); }); }),
        array_intersect: keyed(function (pair, others) { return others.every(function (o) { return hasValue(o, pair[1]); }); }),
        array_diff: keyed(function (pair, others) { return others.every(function (o) { return !hasValue(o, pair[1]); }); }),
        array_fill_keys: function (keys, v) { return arr(values(keys).map(function (k) { return [k, v]; })); },
        array_pad: function (v, size, x) { var list = values(v).slice(); var n = Math.abs(size) - list.length; for (var i = 0; i < n; i++) size > 0 ? list.push(x) : list.unshift(x); return list; },
        array_product: function (v) { return values(v).reduce(function (a, c) { return a * Number(c); }, 1); },
        array_count_values: function (v) { var out = {}; values(v).forEach(function (x) { out[x] = (out[x] || 0) + 1; }); return out; },
        array_replace: function () { var out = arguments[0]; for (var i = 1; i < arguments.length; i++) entries(arguments[i]).forEach(function (pair) { out = set(out, pair[0], pair[1]); }); return out; },
        array_find: function (v, c) { c = fn(c); var e = entries(v); for (var i = 0; i < e.length; i++) if (b(c(e[i][1], e[i][0]))) return e[i][1]; return null; },
        array_any: function (v, c) { c = fn(c); return entries(v).some(function (pair) { return b(c(pair[1], pair[0])); }); },
        array_all: function (v, c) { c = fn(c); return entries(v).every(function (pair) { return b(c(pair[1], pair[0])); }); },
        array_rand: function (v) { var keys = entries(v).map(function (pair) { return pair[0]; }); return keys[Math.floor(Math.random() * keys.length)]; },
        iterator_to_array: function (v) { return isArr(v) ? v : toArray(v); },
        str_split: function (v, n) { v = s(v); n = n || 1; var out = []; for (var i = 0; i < v.length; i += n) out.push(v.slice(i, i + n)); return out.length ? out : ['']; },
        mb_str_split: function (v, n) { var chars = Array.from(s(v)); n = n || 1; var out = []; for (var i = 0; i < chars.length; i += n) out.push(chars.slice(i, i + n).join('')); return out; },
        strrev: function (v) { return s(v).split('').reverse().join(''); },
        substr_count: function (h, n) { return s(h).split(s(n)).length - 1; },
        strrpos: function (h, n) { var i = s(h).lastIndexOf(s(n)); return i < 0 ? false : i; },
        stripos: function (h, n, o) { var i = s(h).toLowerCase().indexOf(s(n).toLowerCase(), o || 0); return i < 0 ? false : i; },
        mb_strpos: function (h, n, o) { var chars = Array.from(s(h)); var i = chars.slice(o || 0).join('').indexOf(s(n)); return i < 0 ? false : Array.from(chars.slice(o || 0).join('').slice(0, i)).length + (o || 0); },
        strstr: function (h, n, before) { h = s(h); var i = h.indexOf(s(n)); return i < 0 ? false : (before ? h.slice(0, i) : h.slice(i)); },
        stristr: function (h, n, before) { h = s(h); var i = h.toLowerCase().indexOf(s(n).toLowerCase()); return i < 0 ? false : (before ? h.slice(0, i) : h.slice(i)); },
        strrchr: function (h, n) { h = s(h); var i = h.lastIndexOf(s(n).charAt(0)); return i < 0 ? false : h.slice(i); },
        str_ireplace: function (search, replace, subject) { var from = Array.isArray(search) ? search : [search]; var out = s(subject); from.forEach(function (item, i) { var to = Array.isArray(replace) ? s(replace[i]) : s(replace); if (s(item) === '') return; out = out.replace(new RegExp(s(item).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), function () { return to; }); }); return out; },
        wordwrap: function (v, width, brk, cut) { width = width || 75; brk = brk === undefined ? '\n' : s(brk); var re = new RegExp('.{1,' + width + '}(\\s|$)' + (cut ? '|.{' + width + '}|.+$' : '|\\S+?(\\s|$)'), 'g'); return (s(v).match(re) || []).map(function (line) { return line.replace(/\s+$/, ''); }).join(brk); },
        strcmp: function (a, c) { a = s(a); c = s(c); return a < c ? -1 : (a > c ? 1 : 0); },
        strcasecmp: function (a, c) { return more.strcmp(s(a).toLowerCase(), s(c).toLowerCase()); },
        strncmp: function (a, c, n) { return more.strcmp(s(a).slice(0, n), s(c).slice(0, n)); },
        strncasecmp: function (a, c, n) { return more.strcmp(s(a).slice(0, n).toLowerCase(), s(c).slice(0, n).toLowerCase()); },
        strnatcmp: function (a, c) { return int(s(a).localeCompare(s(c), 'en', { numeric: true })); },
        strnatcasecmp: function (a, c) { return int(s(a).localeCompare(s(c), 'en', { numeric: true, sensitivity: 'base' })); },
        ord: function (v) { return s(v).length ? s(v).charCodeAt(0) & 255 : 0; },
        chr: function (n) { return String.fromCharCode(((int(n) % 256) + 256) % 256); },
        mb_ord: function (v) { return s(v).codePointAt(0); },
        mb_chr: function (n) { return String.fromCodePoint(n); },
        dechex: function (n) { return int(n).toString(16); },
        hexdec: function (v) { return parseInt(s(v).replace(/[^0-9a-f]/gi, ''), 16) || 0; },
        decbin: function (n) { return int(n).toString(2); },
        bindec: function (v) { return parseInt(s(v).replace(/[^01]/g, ''), 2) || 0; },
        decoct: function (n) { return int(n).toString(8); },
        octdec: function (v) { return parseInt(s(v).replace(/[^0-7]/g, ''), 8) || 0; },
        base_convert: function (v, from, to) { return parseInt(s(v).toLowerCase(), from).toString(to); },
        bin2hex: function (v) { return unescape(encodeURIComponent(s(v))).split('').map(function (c) { return ('0' + c.charCodeAt(0).toString(16)).slice(-2); }).join(''); },
        base64_encode: function (v) { return btoa(unescape(encodeURIComponent(s(v)))); },
        base64_decode: function (v) { try { return decodeURIComponent(escape(atob(s(v)))); } catch (e) { return false; } },
        rawurlencode: function (v) { return encodeURIComponent(s(v)).replace(/[!'()*]/g, function (c) { return '%' + c.charCodeAt(0).toString(16).toUpperCase(); }); },
        urlencode: function (v) { return more.rawurlencode(v).replace(/%20/g, '+').replace(/~/g, '%7E'); },
        rawurldecode: function (v) { return decodeURIComponent(s(v)); },
        urldecode: function (v) { return decodeURIComponent(s(v).replace(/\+/g, ' ')); },
        http_build_query: function (v) { return entries(v).map(function (pair) { return more.urlencode(pair[0]) + '=' + more.urlencode(pair[1]); }).join('&'); },
        htmlspecialchars_decode: function (v) { return s(v).replace(/&(amp|lt|gt|quot|#0?39|apos);/g, function (e) { return entities[e]; }); },
        html_entity_decode: function (v) { return s(v).replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, function (e, name) { if (name.charAt(0) === '#') return String.fromCodePoint(name.charAt(1).toLowerCase() === 'x' ? parseInt(name.slice(2), 16) : Number(name.slice(1))); return entities[e.toLowerCase()] || e; }); },
        addslashes: function (v) { return s(v).replace(/[\\'"\0]/g, function (c) { return '\\' + (c === '\0' ? '0' : c); }); },
        stripslashes: function (v) { return s(v).replace(/\\(.?)/g, function (m, c) { return c === '0' ? '\0' : c; }); },
        vsprintf: function (format, args) { return f.sprintf.apply(null, [format].concat(values(args))); },
        lcg_value: function () { return Math.random(); },
        ctype_digit: ctype(/^[0-9]+$/), ctype_alpha: ctype(/^[A-Za-z]+$/), ctype_alnum: ctype(/^[A-Za-z0-9]+$/),
        ctype_upper: ctype(/^[A-Z]+$/), ctype_lower: ctype(/^[a-z]+$/), ctype_space: ctype(/^[\s\v]+$/),
        ctype_xdigit: ctype(/^[0-9A-Fa-f]+$/), ctype_punct: ctype(/^[!-\/:-@\[-`{-~]+$/),
    };
    Object.keys(more).forEach(function (name) { f[name] = more[name]; });

    Object.keys(preg).forEach(function (name) { f[name] = preg[name]; });

    // preg_match() and preg_match_all() with a $matches argument: [matches, result].
    var pregByReference = {
        preg_match: function (pattern, subject, flags, offset) {
            var r = regex(pattern, true);
            r.re.lastIndex = offset || 0;
            var match = r.re.exec(s(subject));
            return match === null ? [[], 0] : [groups(match, r.names, flags || 0), 1];
        },
        preg_match_all: function (pattern, subject, flags) {
            flags = flags || 1;
            var found = all(pattern, subject), sets = found.matches.map(function (match) { return groups(match, found.names, flags & 768); });
            if (flags & 2) return [sets, sets.length];
            var columns = [], count = pcre(pattern).groups;
            for (var g = 0; g <= count; g++) {
                var column = found.matches.map(function (match) { return match[g] === undefined ? '' : match[g]; });
                if (g > 0 && found.names[g - 1]) columns.push([found.names[g - 1], column]);
                columns.push([g, column]);
            }
            return [arr(columns), sets.length];
        },
    };

    var r = {
        preg_match: pregByReference.preg_match,
        preg_match_all: pregByReference.preg_match_all,
        sort: function (v) { return [byValue(v, cmp, false), true]; },
        rsort: function (v) { return [byValue(v, function (a, c) { return cmp(c, a); }, false), true]; },
        usort: function (v, compare) { var c = fn(compare); return [byValue(v, function (a, d) { return int(c(a, d)); }, false), true]; },
        uasort: function (v, compare) { var c = fn(compare); return [byValue(v, function (a, d) { return int(c(a, d)); }, true), true]; },
        asort: function (v) { return [byValue(v, cmp, true), true]; },
        arsort: function (v) { return [byValue(v, function (a, c) { return cmp(c, a); }, true), true]; },
        ksort: function (v) { return [byKey(v, cmp), true]; },
        krsort: function (v) { return [byKey(v, function (a, c) { return cmp(c, a); }), true]; },
        uksort: function (v, compare) { var c = fn(compare); return [byKey(v, function (a, d) { return int(c(a, d)); }), true]; },
        shuffle: function (v) { var list = values(v).slice(); for (var i = list.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = list[i]; list[i] = list[j]; list[j] = t; } return [list, true]; },
        array_push: function (v) { var out = v; for (var i = 1; i < arguments.length; i++) out = set(out, APPEND, arguments[i]); return [out, f.count(out)]; },
        array_pop: function (v) { var e = entries(v); if (!e.length) return [v, null]; var last = e[e.length - 1]; return [Array.isArray(v) ? v.slice(0, -1) : unset(v, last[0]), last[1]]; },
        array_shift: function (v) { var e = entries(v); if (!e.length) return [v, null]; var rest = e.slice(1); return [arr(rest.map(function (pair) { return [typeof pair[0] === 'number' ? null : pair[0], pair[1]]; })), e[0][1]]; },
        array_unshift: function (v) { var head = Array.prototype.slice.call(arguments, 1); var out = arr(head.map(function (x) { return [null, x]; }).concat(entries(v).map(function (pair) { return [typeof pair[0] === 'number' ? null : pair[0], pair[1]]; }))); return [out, f.count(out)]; },
        array_splice: function (v, offset, length, replacement) { var list = values(v).slice(); var removed = list.splice(offset, length === undefined || length === null ? list.length : length, ...(replacement === undefined ? [] : (Array.isArray(replacement) ? replacement : [replacement]))); return [list, removed]; },
    };

    // ---- Components and reactivity -----------------------------------------

    var owner = null;

    /** An effect owned by the template block being built. */
    function watch(fn) {
        var dispose = effect(fn);
        if (owner) owner.push(dispose);
        return dispose;
    }

    function own(build) {
        var previous = owner;
        var disposers = [];
        owner = disposers;
        try {
            return { value: build(), dispose: function () { disposers.forEach(function (d) { d(); }); disposers.length = 0; } };
        } finally {
            owner = previous;
        }
    }

    /** Declared properties of a component class, backed by signals. */
    function props(Class, defaults) {
        Class.__props = Object.assign({}, Class.__props || {}, defaults);
        Object.keys(defaults).forEach(function (name) {
            Object.defineProperty(Class.prototype, name, {
                configurable: true,
                get: function () { return this.__signal(name).value; },
                set: function (v) { this.__signal(name).value = v; },
            });
        });
    }

    class Base {
        constructor() {
            var args = arguments;
            this.__init();
            if (typeof this.__construct === 'function') this.__construct.apply(this, args);
        }

        __init() {}

        __signal(name) {
            if (!this.__s) this.__s = {};
            var current = this.__s[name];
            if (!current) {
                var factory = this.constructor.__props[name];
                current = this.__s[name] = signal(typeof factory === 'function' ? factory() : null);
            }
            return current;
        }
    }

    class Component extends Base {
        setup() {}
    }

    function mount(Class, attributes) {
        var instance = new Class();
        if (attributes) {
            untracked(function () {
                Object.keys(attributes).forEach(function (name) { instance[name] = attributes[name]; });
            });
        }
        batch(function () { untracked(function () { instance.setup(); }); });
        var render = Class.__render;
        if (typeof render !== 'function') throw PhpError('RuntimeException', (Class.phpName || 'Component') + ' has no template.');
        var node = render(instance, {});
        instance.__node = node;
        if (typeof instance.mounted === 'function') queue(function () { instance.mounted(); });
        return node;
    }

    // ---- DOM ---------------------------------------------------------------

    var BOX = {
        Screen: 'p-screen', View: '', Column: '', Row: 'p-row', Stack: 'p-stack', SafeAreaView: 'p-safe',
        ScrollView: 'p-scroll', Pressable: 'p-press', Button: 'p-press p-button', Link: 'p-press', Text: 'p-text',
        Image: 'p-image', Spacer: 'p-spacer', Divider: 'p-divider', ActivityIndicator: 'p-spinner',
        Input: 'p-input', TextInput: 'p-input', Template: 'p-fragment', Fragment: 'p-fragment', Card: '',
    };
    var FOCUS_TAGS = { Pressable: true, Button: true, Link: true, Input: true, TextInput: true };
    var UNITLESS = { flex: 1, flexGrow: 1, flexShrink: 1, opacity: 1, zIndex: 1, fontWeight: 1, lineHeight: 0, aspectRatio: 1 };
    var STYLE = {
        width: 1, height: 1, minWidth: 1, minHeight: 1, maxWidth: 1, maxHeight: 1, flex: 1, flexGrow: 1, flexShrink: 1,
        flexBasis: 1, gap: 1, rowGap: 1, columnGap: 1, padding: 1, paddingTop: 1, paddingBottom: 1, paddingLeft: 1,
        paddingRight: 1, paddingHorizontal: 1, paddingVertical: 1, margin: 1, marginTop: 1, marginBottom: 1,
        marginLeft: 1, marginRight: 1, marginHorizontal: 1, marginVertical: 1, backgroundColor: 1, color: 1,
        borderRadius: 1, borderWidth: 1, borderColor: 1, opacity: 1, fontSize: 1, fontWeight: 1, lineHeight: 1,
        letterSpacing: 1, textAlign: 1, alignItems: 1, alignSelf: 1, justifyContent: 1, flexDirection: 1,
        flexWrap: 1, position: 1, top: 1, left: 1, right: 1, bottom: 1, zIndex: 1, overflow: 1, aspectRatio: 1,
        display: 1,
    };

    /** `#AARRGGBB` (native ARGB order) or any CSS color. */
    function color(value) {
        if (typeof value === 'number') {
            return 'rgba(' + ((value >>> 16) & 255) + ',' + ((value >>> 8) & 255) + ',' + (value & 255) + ',' + (((value >>> 24) & 255) / 255) + ')';
        }
        var match = /^#([0-9a-f]{2})([0-9a-f]{6})$/i.exec(String(value));
        if (match) return '#' + match[2] + match[1];
        return String(value);
    }

    function length(name, value) {
        if (value === null || value === undefined || value === false) return '';
        if (typeof value === 'number' || /^-?\d+(\.\d+)?$/.test(String(value))) return UNITLESS[name] ? String(value) : value + 'px';
        return String(value);
    }

    function style(el, name, value) {
        var st = el.style;
        switch (name) {
            case 'paddingHorizontal': st.paddingLeft = st.paddingRight = length(name, value); return;
            case 'paddingVertical': st.paddingTop = st.paddingBottom = length(name, value); return;
            case 'marginHorizontal': st.marginLeft = st.marginRight = length(name, value); return;
            case 'marginVertical': st.marginTop = st.marginBottom = length(name, value); return;
            case 'backgroundColor': case 'color': case 'borderColor': st[name] = value === null || value === undefined ? '' : color(value); return;
            case 'flex': st.flex = value === null || value === undefined ? '' : (typeof value === 'number' || /^\d+(\.\d+)?$/.test(value) ? value + ' 1 0%' : value); return;
            case 'gap': case 'rowGap': case 'columnGap':
                // Chromium < 84 has no flex gap: margins on the children stand in.
                st[name] = length(name, value);
                if (!flexGap) gapFallback(el, name, length(name, value));
                return;
            default: st[name] = length(name, value);
        }
    }

    var flexGap = (function () {
        if (typeof document === 'undefined') return true;
        var outer = document.createElement('div');
        outer.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute';
        outer.appendChild(document.createElement('div'));
        outer.appendChild(document.createElement('div'));
        document.documentElement.appendChild(outer);
        var supported = outer.scrollHeight === 1;
        outer.remove();
        return supported;
    })();

    function gapFallback(el, name, value) {
        el.setAttribute('data-gap', value);
        queue(function () { applyGap(el); });
    }

    function applyGap(el) {
        var value = el.getAttribute('data-gap');
        if (value === null) return;
        var row = getComputedStyle(el).flexDirection.indexOf('row') === 0;
        var children = Array.prototype.filter.call(el.children, function (child) { return getComputedStyle(child).position !== 'absolute'; });
        children.forEach(function (child, index) {
            child.style[row ? 'marginLeft' : 'marginTop'] = index === 0 ? '' : value;
        });
    }

    function attribute(el, name, value) {
        switch (name) {
            case 'testId': if (value === null || value === undefined) el.removeAttribute('data-testid'); else el.setAttribute('data-testid', s(value)); return;
            case 'accessibilityLabel': el.setAttribute('aria-label', s(value)); return;
            case 'accessibilityRole': el.setAttribute('role', s(value)); return;
            case 'focusable': focusable(el, b(value) && value !== 'false'); return;
            case 'autoFocus': el.__autoFocus = b(value) && value !== 'false'; if (el.__autoFocus) queue(function () { autoFocus(el); }); return;
            case 'focusGroup': if (b(value) && value !== 'false') el.setAttribute('data-fg', ''); else el.removeAttribute('data-fg'); return;
            case 'focusRing': el.setAttribute('data-ring', s(value)); return;
            case 'captureKeys': el.__capture = s(value).split(',').map(function (k) { return k.trim(); }); return;
            case 'nextFocusUp': case 'nextFocusDown': case 'nextFocusLeft': case 'nextFocusRight': el['__' + name] = s(value); return;
            case 'horizontal': el.classList.toggle('p-h', b(value) && value !== 'false'); return;
            case 'showsVerticalScrollIndicator': case 'showsHorizontalScrollIndicator': el.classList.toggle('p-noind', value === false || value === 'false'); return;
            case 'source': case 'src': if (el.tagName === 'IMG') el.src = typeof value === 'object' && value ? s(value.uri || value.url) : s(value); return;
            case 'resizeMode': case 'contentFit': el.style.objectFit = value === 'stretch' ? 'fill' : (value === 'center' ? 'none' : s(value)); return;
            case 'placeholder': el.placeholder = s(value); return;
            case 'value': if (el.value !== s(value)) el.value = s(value); return;
            case 'numberOfLines': el.style.webkitLineClamp = s(value); el.classList.add('p-clamp'); return;
            case 'disabled': el.__disabled = b(value) && value !== 'false'; el.classList.toggle('p-disabled', el.__disabled); return;
            case 'title': if (el.classList.contains('p-button')) { el.textContent = s(value); } return;
            case 'key': case 'p-key': return;
        }
        if (STYLE[name]) style(el, name, value);
    }

    function focusable(el, on) {
        if (on) {
            el.setAttribute('data-f', '');
            if (el.tabIndex < 0 && el.tagName !== 'INPUT') el.tabIndex = -1;
        } else {
            el.removeAttribute('data-f');
        }
    }

    function className(staticClass, dynamic) {
        var names = staticClass ? [staticClass] : [];
        if (typeof dynamic === 'string') names.push(dynamic);
        else if (Array.isArray(dynamic)) dynamic.forEach(function (name) { if (b(name)) names.push(s(name)); });
        else if (dynamic && typeof dynamic === 'object') Object.keys(dynamic).forEach(function (name) { if (b(dynamic[name])) names.push(name); });
        return names.join(' ');
    }

    var H = {
        /** Element for a PAM tag; `cls` is the static class list (scope included). */
        n: function (tag, cls) {
            var el;
            if (tag === 'Image') el = document.createElement('img');
            else if (tag === 'Input' || tag === 'TextInput') el = document.createElement('input');
            else el = document.createElement('div');
            var base = 'p' + (BOX[tag] ? ' ' + BOX[tag] : '');
            el.__base = cls ? base + ' ' + cls : base;
            el.className = el.__base;
            if (FOCUS_TAGS[tag]) focusable(el, true);
            if (tag === 'SafeAreaView') safeArea(el);
            if (tag === 'Text' || tag === 'Button') el.__text = true;
            return el;
        },
        /** Static attribute. */
        a: function (el, name, value) { attribute(el, name, value); },
        /** Bound attribute. */
        b: function (el, name, fn) { watch(function () { attribute(el, name, fn()); }); },
        /** Bound :class. */
        c: function (el, fn) { watch(function () { el.className = className(el.__base, fn()); }); },
        /** Static text. */
        t: function (el, text) { el.appendChild(document.createTextNode(text)); },
        /** Bound text. */
        x: function (el, fn) {
            var node = document.createTextNode('');
            el.appendChild(node);
            watch(function () { var v = s(fn()); if (node.data !== v) node.data = v; });
        },
        /** Event listener: press, focus, blur, keyDown, change, input. */
        e: function (el, name, handler) {
            var run = function (event) { batch(function () { handler(event); }); };
            switch (name) {
                case 'press': case 'click': el.__press = run; el.addEventListener('click', function (event) { if (!el.__disabled) run(event); }); break;
                case 'longPress': el.__longPress = run; break;
                case 'focus': el.addEventListener('focus', function () { run({ target: el }); }); break;
                case 'blur': el.addEventListener('blur', function () { run({ target: el }); }); break;
                case 'keyDown': el.__keyDown = run; el.setAttribute('data-k', ''); break;
                case 'change': case 'changeText': case 'input': el.addEventListener('input', function () { run(name === 'changeText' ? el.value : { value: el.value, text: el.value }); }); break;
                case 'submit': case 'submitEditing': el.addEventListener('keydown', function (event) { if (event.key === 'Enter') run({ value: el.value, text: el.value }); }); break;
                default: el.addEventListener(name.toLowerCase(), run);
            }
        },
        /** `p-if` / `p-else-if` / `p-else` chain: [[conditionFn|null, buildFn], ...]. */
        i: function (parent, branches) {
            var anchor = document.createComment('if');
            parent.appendChild(anchor);
            var current = -1, block = null;
            watch(function () {
                var chosen = -1;
                for (var i = 0; i < branches.length; i++) {
                    if (branches[i][0] === null || b(branches[i][0]())) { chosen = i; break; }
                }
                if (chosen === current) return;
                current = chosen;
                untracked(function () {
                    if (block) { remove(block.value); block.dispose(); block = null; }
                    if (chosen >= 0) {
                        block = own(function () { return branches[chosen][1](); });
                        anchor.parentNode.insertBefore(block.value, anchor);
                        refocus();
                    }
                });
            });
        },
        /** Keyed `p-for`: build(item, index) reads both through the scope it creates. */
        f: function (parent, source, keyOf, build, scope, itemName, indexName) {
            var anchor = document.createComment('for');
            parent.appendChild(anchor);
            var rows = new Map();
            watch(function () {
                var raw = source();
                var items = typeof raw === 'number' ? range(0, raw - 1) : entries(raw);
                untracked(function () {
                    var next = new Map();
                    var order = [];
                    items.forEach(function (pair, position) {
                        var item = typeof raw === 'number' ? pair : pair[1];
                        var index = typeof raw === 'number' ? position : pair[0];
                        var row = { item: signal(item), index: signal(index) };
                        var local = Object.create(scope);
                        Object.defineProperty(local, itemName, { get: function () { return row.item.value; } });
                        Object.defineProperty(local, indexName, { get: function () { return row.index.value; } });
                        var k = keyOf ? keyOf(local) : position;
                        var existing = rows.get(k);
                        if (existing && !next.has(k)) {
                            existing.item.value = item;
                            existing.index.value = index;
                            next.set(k, existing);
                            order.push(existing);
                            return;
                        }
                        row.block = own(function () { return build(local); });
                        next.set(k, row);
                        order.push(row);
                    });
                    rows.forEach(function (row, k) {
                        if (next.get(k) !== row) { remove(row.block.value); row.block.dispose(); }
                    });
                    var cursor = anchor;
                    for (var i = order.length - 1; i >= 0; i--) {
                        var node = order[i].block.value;
                        if (node.nextSibling !== cursor || node.parentNode !== anchor.parentNode) anchor.parentNode.insertBefore(node, cursor);
                        cursor = node;
                    }
                    rows = next;
                    refocus();
                });
            });
        },
        /** Child component. */
        m: function (parent, Class, attrs) {
            var instance = new Class();
            Object.keys(attrs).forEach(function (name) {
                var value = attrs[name];
                if (typeof value === 'function' && value.__bound) watch(function () { instance[name] = value(); });
                else instance[name] = value;
            });
            batch(function () { untracked(function () { instance.setup(); }); });
            parent.appendChild(Class.__render(instance, {}));
        },
        /** Fragment for blocks with several roots. */
        g: function () { return document.createDocumentFragment(); },
    };

    function remove(node) {
        if (node.nodeType === 11) return;
        if (node.__nodes) node.__nodes.forEach(function (child) { child.remove(); });
        else if (node.parentNode) node.parentNode.removeChild(node);
    }

    // ---- Screen, scale and overscan ------------------------------------------

    var insets = { top: 0, right: 0, bottom: 0, left: 0 };

    function safeArea(el) {
        el.style.paddingTop = insets.top + 'px';
        el.style.paddingRight = insets.right + 'px';
        el.style.paddingBottom = insets.bottom + 'px';
        el.style.paddingLeft = insets.left + 'px';
    }

    function layoutRoot(root) {
        var width = window.innerWidth, height = window.innerHeight;
        var logical = config.tv ? config.width : width;
        var zoom = width / logical;
        root.style.width = logical + 'px';
        root.style.height = Math.round(height / zoom) + 'px';
        root.style.zoom = zoom === 1 ? '' : String(zoom);
        if (config.tv && config.overscan) {
            var h = Math.round(height / zoom);
            insets = { top: Math.round(h * 0.05), bottom: Math.round(h * 0.05), left: Math.round(logical * 0.05), right: Math.round(logical * 0.05) };
        }
    }

    function formFactor() {
        var tv = config.tv;
        var coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
        var phone = !tv && Math.min(window.innerWidth, window.innerHeight) < 600 && coarse;
        var type = tv ? 'tv' : (phone ? 'phone' : (coarse ? 'tablet' : 'tablet'));
        return {
            type: type, tv: tv, phone: phone, tablet: type === 'tablet',
            remote: tv, touch: coarse && !tv,
            inputMode: tv ? 'remote' : (coarse ? 'touch' : 'pointer'),
            pointer: !tv || config.target === 'webos',
        };
    }

    // ---- Focus and keys --------------------------------------------------------

    var KEYCODES = {
        10009: 'GoBack', 461: 'GoBack', 10182: 'Exit', 10252: 'MediaPlayPause', 179: 'MediaPlayPause', 178: 'MediaStop', 415: 'MediaPlay', 19: 'MediaPause',
        413: 'MediaStop', 417: 'MediaFastForward', 412: 'MediaRewind', 427: 'ChannelUp', 428: 'ChannelDown',
        403: 'ColorF0Red', 404: 'ColorF1Green', 405: 'ColorF2Yellow', 406: 'ColorF3Blue', 457: 'Info', 10135: 'ContextMenu',
        37: 'ArrowLeft', 38: 'ArrowUp', 39: 'ArrowRight', 40: 'ArrowDown', 13: 'Enter',
    };
    var KEYNAMES = { Escape: 'GoBack', BrowserBack: 'GoBack', XF86Back: 'GoBack', Backspace: 'GoBack', Spacebar: ' ', Right: 'ArrowRight', Left: 'ArrowLeft', Up: 'ArrowUp', Down: 'ArrowDown' };
    var ARROWS = { ArrowLeft: true, ArrowRight: true, ArrowUp: true, ArrowDown: true };

    function keyName(event) {
        if (KEYCODES[event.keyCode] && (event.key === undefined || event.key === 'Unidentified' || event.keyCode > 255 || event.key === '')) return KEYCODES[event.keyCode];
        var name = event.key;
        if (KEYNAMES[name]) {
            if (name === 'Backspace' && document.activeElement && document.activeElement.tagName === 'INPUT') return name;
            return KEYNAMES[name];
        }
        return name || KEYCODES[event.keyCode] || 'Unidentified';
    }

    class KeyEvent {
        constructor(key, event) {
            this.key = key;
            this.repeat = !!event.repeat;
            this.shift = !!event.shiftKey;
            this.control = !!event.ctrlKey;
            this.alt = !!event.altKey;
            this.meta = !!event.metaKey;
        }
        isArrow() { return !!ARROWS[this.key]; }
        isMedia() { return this.key.indexOf('Media') === 0; }
    }

    function visible(el) {
        if (!el.isConnected || el.getClientRects().length === 0) return false;
        var st = getComputedStyle(el);
        return st.visibility !== 'hidden';
    }

    function focusables() {
        return Array.prototype.filter.call(document.querySelectorAll('[data-f]'), visible);
    }

    function focus(el) {
        if (!el || !el.isConnected) return;
        // Chromium 63 (Tizen 5) ignores preventScroll: put the scroll back.
        var kept = [];
        for (var parent = el.parentElement; parent; parent = parent.parentElement) {
            if (parent.classList.contains('p-scroll')) kept.push([parent, parent.scrollTop, parent.scrollLeft]);
        }
        el.focus({ preventScroll: true });
        kept.forEach(function (entry) { entry[0].scrollTop = entry[1]; entry[0].scrollLeft = entry[2]; });
        reveal(el);
    }

    /** Scrolls every scrolling ancestor just enough to show the element. */
    function reveal(el) {
        var parent = el.parentElement;
        while (parent && parent !== document.body) {
            if (parent.classList.contains('p-scroll')) {
                var box = parent.getBoundingClientRect(), r = el.getBoundingClientRect();
                var zoom = parseFloat(document.getElementById('pam').style.zoom) || 1;
                var margin = 24;
                if (parent.classList.contains('p-h')) {
                    if (r.right > box.right) parent.scrollLeft += (r.right - box.right) / zoom + margin;
                    else if (r.left < box.left) parent.scrollLeft -= (box.left - r.left) / zoom + margin;
                } else {
                    if (r.bottom > box.bottom) parent.scrollTop += (r.bottom - box.bottom) / zoom + margin;
                    else if (r.top < box.top) parent.scrollTop -= (box.top - r.top) / zoom + margin;
                }
            }
            parent = parent.parentElement;
        }
    }

    function focused() {
        var el = document.activeElement;
        return el && el !== document.body && el.hasAttribute && el.hasAttribute('data-f') && el.isConnected ? el : null;
    }

    function autoFocus(el) {
        if (el.__autoFocus && visible(el)) focus(el);
    }

    var refocusQueued = false;
    function refocus() {
        if (refocusQueued) return;
        refocusQueued = true;
        queue(function () {
            refocusQueued = false;
            if (focused()) return;
            var auto = focusables().filter(function (el) { return el.__autoFocus; });
            var list = auto.length ? auto : focusables();
            if (list.length) focus(list[0]);
        });
    }

    var tasks = [];
    function queue(task) {
        if (tasks.push(task) === 1) {
            Promise.resolve().then(function () {
                var run = tasks;
                tasks = [];
                run.forEach(function (t) { t(); });
            });
        }
    }

    function groupOf(el) {
        var parent = el.parentElement;
        while (parent && parent !== document.body) {
            if (parent.hasAttribute('data-fg')) return parent;
            parent = parent.parentElement;
        }
        return null;
    }

    function byTestId(id) {
        return document.querySelector('[data-testid="' + id.replace(/"/g, '\\"') + '"]');
    }

    /** Nearest focus stop in a direction (Android FocusFinder weighting). */
    function nearest(from, direction) {
        var a = from.getBoundingClientRect();
        var best = null, bestScore = Infinity, bestInBeam = false;
        focusables().forEach(function (el) {
            if (el === from || from.contains(el)) return;
            var c = el.getBoundingClientRect();
            var major, minor, beam;
            switch (direction) {
                case 'ArrowRight': if (c.left < a.right - 1 && c.left <= a.left) return; major = c.left - a.right; minor = (c.top + c.height / 2) - (a.top + a.height / 2); beam = c.bottom > a.top && c.top < a.bottom; break;
                case 'ArrowLeft': if (c.right > a.left + 1 && c.right >= a.right) return; major = a.left - c.right; minor = (c.top + c.height / 2) - (a.top + a.height / 2); beam = c.bottom > a.top && c.top < a.bottom; break;
                case 'ArrowDown': if (c.top < a.bottom - 1 && c.top <= a.top) return; major = c.top - a.bottom; minor = (c.left + c.width / 2) - (a.left + a.width / 2); beam = c.right > a.left && c.left < a.right; break;
                default: if (c.bottom > a.top + 1 && c.bottom >= a.bottom) return; major = a.top - c.bottom; minor = (c.left + c.width / 2) - (a.left + a.width / 2); beam = c.right > a.left && c.left < a.right; break;
            }
            major = Math.max(0, major);
            var score = 13 * major * major + minor * minor;
            if ((beam && !bestInBeam) || ((beam === bestInBeam) && score < bestScore)) {
                best = el; bestScore = score; bestInBeam = beam;
            }
        });
        return best;
    }

    function move(direction) {
        var from = focused();
        if (!from) { refocus(); return; }
        var explicit = from['__nextFocus' + direction.slice(5)];
        var target = explicit ? byTestId(explicit) : nearest(from, direction);
        if (!target) return;
        var group = groupOf(target);
        if (group && group !== groupOf(from) && group.__last && group.__last.isConnected && visible(group.__last)) target = group.__last;
        focus(target);
    }

    function back() {
        if (window.tizen && tizen.application) { try { tizen.application.getCurrentApplication().exit(); } catch (e) {} return; }
        if (window.webOS && typeof webOS.platformBack === 'function') { webOS.platformBack(); return; }
        if (window.PamAndroid) { PamAndroid.exit(); return; }
        if (history.length > 1) history.back();
    }

    function onKey(event) {
        var name = keyName(event);
        var captured = false;
        var modified = event.ctrlKey || event.altKey || event.metaKey;
        var start = focused();
        var keyEvent = new KeyEvent(name, event);
        if (start) {
            for (var el = start; el && el !== document.body; el = el.parentElement) {
                if (el.__keyDown) el.__keyDown(keyEvent);
                if (!modified && el.__capture && el.__capture.indexOf(name) >= 0) { captured = true; break; }
            }
        } else {
            Array.prototype.filter.call(document.querySelectorAll('[data-k]'), visible).forEach(function (el) {
                el.__keyDown(keyEvent);
                if (!modified && el.__capture && el.__capture.indexOf(name) >= 0) captured = true;
            });
        }
        if (captured) { event.preventDefault(); return; }
        if (ARROWS[name]) {
            if (start && start.tagName === 'INPUT' && (name === 'ArrowLeft' || name === 'ArrowRight')) return;
            event.preventDefault();
            move(name);
        } else if (name === 'Enter' || name === ' ') {
            if (start && start.tagName === 'INPUT') return;
            if (start && start.__press && !start.__disabled) { event.preventDefault(); start.__press({ target: start }); }
        } else if (name === 'GoBack') {
            event.preventDefault();
            back();
        }
    }

    // A key from the host (Android's Back reaches the WebView's activity, not the page).
    function hostKey(name) {
        onKey({ key: name, keyCode: 0, ctrlKey: false, altKey: false, metaKey: false, preventDefault: function () {} });
    }

    function registerTvKeys() {
        if (window.tizen && tizen.tvinputdevice) {
            ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind', 'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue', 'Info']
                .forEach(function (name) { try { tizen.tvinputdevice.registerKey(name); } catch (e) {} });
        }
    }

    // ---- App ---------------------------------------------------------------

    var App = {
        globals: function (values) { Object.keys(values).forEach(function (k) { g[k] = values[k]; }); },
        run: function (Class) {
            var root = document.getElementById('pam');
            layoutRoot(root);
            window.addEventListener('resize', function () { layoutRoot(root); });
            document.addEventListener('keydown', onKey);
            document.addEventListener('focusin', function (event) {
                var group = groupOf(event.target);
                while (group) { group.__last = event.target; group = groupOf(group); }
            });
            document.addEventListener('mouseover', function (event) {
                var target = event.target.closest && event.target.closest('[data-f]');
                if (target && target !== document.activeElement) focus(target);
            });
            registerTvKeys();
            root.appendChild(mount(Class));
            refocus();
            if (!flexGap) queue(function () { Array.prototype.forEach.call(document.querySelectorAll('[data-gap]'), applyGap); });
        },
    };

    return {
        config: config, g: g, App: App, Base: Base, Component: Component, KeyEvent: KeyEvent, H: H,
        props: props, signal: signal, batch: batch, consts: consts, key: hostKey, iface: iface, implement: implement, enumCases: enumCases, bound: bound,
        fn: fn, call: call, listOf: listOf, toArray: toArray, raise: raise, clone: clone, className: className, classOf: classOf, r: r,
        s: s, b: b, num: num, int: int, set: set, setIn: setIn, unset: unset, APPEND: APPEND, dig: dig, co: co,
        entries: entries, values: values, arr: arr, same: same, eq: eq, cmp: cmp, empty: empty, f: f,
        Error: PhpError, isa: isa, unmatched: unmatched,
        useFormFactor: formFactor,
        useSafeAreaInsets: function () { return insets; },
        useWindowDimensions: function () { var root = document.getElementById('pam'); return { width: root.clientWidth || config.width, height: root.clientHeight || 540 }; },
        useColorScheme: function () { return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'; },
    };
})();
