// Native tree encoder for the PAM Native PHP runtime (Android and iOS).
//
// A byte-for-byte port of Pam\Native\Internal\TreeEncoder's frame writer:
// it walks the Element tree the PHP renderer produced, assigns the same
// stable node ids (XXH3 of the identity path), encodes the same property
// values, and writes the same PNT1 full frames and PNP1 patches against the
// tree it committed last. The PHP class delegates to it when the host
// registers these functions; the PHP implementation stays the reference
// (packages/native/tests/native_encoder_equivalence.php compares both).
//
// It keeps no per-element cache: walking a tree natively costs less than
// the PHP subtree cache lookups it would replace, and identity, collision
// and patch semantics are unchanged.

#include "pam_tree_encoder.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "php.h"
#include "zend_enum.h"
#include "zend_exceptions.h"
#include "zend_interfaces.h"
#include "ext/spl/spl_exceptions.h"
#define XXH_INLINE_ALL 1
#include "ext/hash/xxhash/xxhash.h"

// A named namespace (not anonymous): the iOS bridge compiles this file into
// its own translation unit, next to its own helpers.
namespace pam_tree_encoder_detail {

constexpr std::size_t kMaxNodes = 100'000;
constexpr std::size_t kMaxValueBytes = 1'048'576;

struct NodeRecord {
    std::uint64_t id = 0;
    std::uint64_t parent = 0;
    std::uint32_t index = 0;
    std::uint8_t kind = 0;
    // Encoded property values in wire order (key, tagged value bytes).
    std::vector<std::pair<std::uint16_t, std::string>> values;
};

struct ElementLayout {
    zend_class_entry* element = nullptr;
    zend_class_entry* binary = nullptr;
    std::uint32_t kind = 0;
    std::uint32_t properties = 0;
    std::uint32_t children = 0;
    std::uint32_t events = 0;
    std::uint32_t element_key = 0;
    std::uint32_t dom_identity = 0;
    std::uint32_t identity_slot = 0;
    std::uint32_t encoded_properties = 0;
    std::uint32_t binary_bytes = 0;
    bool ready = false;
};

struct StringHash {
    using is_transparent = void;
    std::size_t operator()(std::string_view value) const noexcept {
        return static_cast<std::size_t>(XXH3_64bits(value.data(), value.size()));
    }
};

struct Encoder {
    zval event_properties;  // array<int EventKind, int PropKey>
    zval refresh_hosted;    // Closure(Element): Element
    zval encode_property;   // Closure(mixed): string (reference encoder, throws)
    zval event_property;    // Closure(int): never (unknown event kinds)
    std::unordered_set<zend_long> refresh_kinds;

    struct KnownIdentity {
        std::uint64_t id;
        bool owner;  // id_identities[id] is this identity (no hash collision)
    };
    std::unordered_map<std::string, KnownIdentity, StringHash, std::equal_to<>> identity_ids;
    std::string identity;  // scratch buffer
    std::unordered_map<std::uint64_t, std::string> id_identities;

    bool has_previous = false;
    std::uint64_t previous_root = 0;
    std::vector<NodeRecord> committed;
    std::unordered_map<std::uint64_t, std::size_t> committed_index;
    zval last_root;       // strong reference: the object cannot be reused
    zval last_callbacks;

    // Windowed lists (TreeEncoder::windowFor/extendWindow): materialized
    // [start, end) row ranges by list path, and per windowed list node its
    // path and row id => index.
    struct Window {
        zend_long virtual_list = -1;
        zend_long placeholder = -1;
        zend_long windowed = -1;
        zend_long horizontal = -1;
        zend_long initial_index = -1;
        zend_long sticky = -1;
        zend_long full_span = -1;
        std::size_t min_rows = 96;
        std::size_t initial_rows = 48;
        std::size_t margin_rows = 64;
    } window;
    std::unordered_map<std::string, std::vector<std::pair<zend_long, zend_long>>> window_ranges;
    struct WindowList {
        std::string path;
        std::unordered_map<std::uint64_t, zend_long> rows;
    };
    std::unordered_map<std::uint64_t, WindowList> window_lists;
    std::uint64_t window_generation = 0;
    std::uint64_t encoded_generation = 0;

    // Per frame.
    std::unordered_set<std::uint64_t> ids;
    std::vector<NodeRecord> nodes;
    zval callbacks;
    std::size_t node_count = 0;
};

ElementLayout layout;
std::unordered_map<zend_long, std::unique_ptr<Encoder>> encoders;
zend_long next_handle = 1;

void put_u16(std::string& out, std::uint32_t value) {
    out.push_back(static_cast<char>(value & 0xFF));
    out.push_back(static_cast<char>((value >> 8) & 0xFF));
}

void put_u32(std::string& out, std::uint32_t value) {
    for (int shift = 0; shift < 32; shift += 8) out.push_back(static_cast<char>((value >> shift) & 0xFF));
}

void put_u64(std::string& out, std::uint64_t value) {
    for (int shift = 0; shift < 64; shift += 8) out.push_back(static_cast<char>((value >> shift) & 0xFF));
}

// PCRE's UTF-8 validity (preg_match('//u')): no overlongs, no surrogates,
// nothing above U+10FFFF.
bool valid_utf8(const unsigned char* s, std::size_t length) {
    std::size_t i = 0;
    while (i < length) {
        const unsigned char c = s[i];
        if (c < 0x80) {
            ++i;
            continue;
        }
        std::size_t need;
        std::uint32_t code;
        if (c >= 0xC2 && c <= 0xDF) {
            need = 1;
            code = c & 0x1F;
        } else if (c >= 0xE0 && c <= 0xEF) {
            need = 2;
            code = c & 0x0F;
        } else if (c >= 0xF0 && c <= 0xF4) {
            need = 3;
            code = c & 0x07;
        } else {
            return false;
        }
        if (length - i <= need) return false;
        for (std::size_t k = 1; k <= need; ++k) {
            const unsigned char next = s[i + k];
            if ((next & 0xC0) != 0x80) return false;
            code = (code << 6) | (next & 0x3F);
        }
        if ((need == 2 && code < 0x800) || (need == 3 && (code < 0x10000 || code > 0x10FFFF))) return false;
        if (code >= 0xD800 && code <= 0xDFFF) return false;
        i += need + 1;
    }
    return true;
}

void throw_logic(const std::string& message) {
    zend_throw_exception(spl_ce_LogicException, message.c_str(), 0);
}

void throw_invalid(const char* message) {
    zend_throw_exception(spl_ce_InvalidArgumentException, message, 0);
}

bool resolve_layout() {
    if (layout.ready) return true;
    zend_string* element_name = zend_string_init("Pam\\Native\\Element", sizeof("Pam\\Native\\Element") - 1, 0);
    zend_string* binary_name =
        zend_string_init("Pam\\Native\\Internal\\BinaryValue", sizeof("Pam\\Native\\Internal\\BinaryValue") - 1, 0);
    layout.element = zend_lookup_class(element_name);
    layout.binary = zend_lookup_class(binary_name);
    zend_string_release(element_name);
    zend_string_release(binary_name);
    if (layout.element == nullptr || layout.binary == nullptr) return false;
    auto offset = [](zend_class_entry* ce, const char* name, std::uint32_t& out) {
        auto* info = static_cast<zend_property_info*>(
            zend_hash_str_find_ptr(&ce->properties_info, name, std::strlen(name)));
        if (info == nullptr || (info->flags & ZEND_ACC_STATIC)) return false;
        out = info->offset;
        return true;
    };
    layout.ready = offset(layout.element, "kind", layout.kind)
        && offset(layout.element, "properties", layout.properties)
        && offset(layout.element, "children", layout.children)
        && offset(layout.element, "events", layout.events)
        && offset(layout.element, "elementKey", layout.element_key)
        && offset(layout.element, "domIdentity", layout.dom_identity)
        && offset(layout.element, "identitySlot", layout.identity_slot)
        && offset(layout.element, "encodedProperties", layout.encoded_properties)
        && offset(layout.binary, "bytes", layout.binary_bytes);
    return layout.ready;
}

inline zval* prop(zend_object* object, std::uint32_t offset) {
    zval* value = OBJ_PROP(object, offset);
    ZVAL_DEREF(value);
    return value;
}

inline zend_long element_kind(zend_object* element) {
    zval* kind = prop(element, layout.kind);
    if (Z_TYPE_P(kind) != IS_OBJECT) return -1;
    zval* value = zend_enum_fetch_case_value(Z_OBJ_P(kind));
    return Z_TYPE_P(value) == IS_LONG ? Z_LVAL_P(value) : -1;
}

inline zend_string* nullable_string(zend_object* element, std::uint32_t offset) {
    zval* value = prop(element, offset);
    return Z_TYPE_P(value) == IS_STRING ? Z_STR_P(value) : nullptr;
}

// Encodes one property value exactly like TreeEncoder::encodeProperty().
bool encode_value(Encoder& encoder, zval* value, std::string& out) {
    out.clear();
    switch (Z_TYPE_P(value)) {
        case IS_STRING: {
            zend_string* text = Z_STR_P(value);
            if (!valid_utf8(reinterpret_cast<const unsigned char*>(ZSTR_VAL(text)), ZSTR_LEN(text))) {
                throw_invalid("Text properties must contain valid UTF-8.");
                return false;
            }
            if (ZSTR_LEN(text) > kMaxValueBytes) {
                throw_invalid("Wire values cannot exceed one megabyte.");
                return false;
            }
            out.reserve(5 + ZSTR_LEN(text));
            out.push_back('\x01');
            put_u32(out, static_cast<std::uint32_t>(ZSTR_LEN(text)));
            out.append(ZSTR_VAL(text), ZSTR_LEN(text));
            return true;
        }
        case IS_LONG:
            out.push_back('\x02');
            put_u64(out, static_cast<std::uint64_t>(Z_LVAL_P(value)));
            return true;
        case IS_DOUBLE: {
            const double number = Z_DVAL_P(value);
            if (!std::isfinite(number)) {
                throw_invalid("Floating properties must be finite.");
                return false;
            }
            std::uint64_t bits;
            std::memcpy(&bits, &number, sizeof(bits));
            out.push_back('\x03');
            put_u64(out, bits);
            return true;
        }
        case IS_TRUE:
            out.assign("\x04\x01", 2);
            return true;
        case IS_FALSE:
            out.assign("\x04\x00", 2);
            return true;
        case IS_OBJECT:
            if (Z_OBJCE_P(value) == layout.binary) {
                zval* bytes = prop(Z_OBJ_P(value), layout.binary_bytes);
                if (Z_TYPE_P(bytes) != IS_STRING) break;
                if (Z_STRLEN_P(bytes) > kMaxValueBytes) {
                    throw_invalid("Wire values cannot exceed one megabyte.");
                    return false;
                }
                out.push_back('\x05');
                put_u32(out, static_cast<std::uint32_t>(Z_STRLEN_P(bytes)));
                out.append(Z_STRVAL_P(bytes), Z_STRLEN_P(bytes));
                return true;
            }
            break;
        default:
            break;
    }
    // Anything else: the PHP reference encoder decides (and throws).
    zval result;
    if (call_user_function(nullptr, nullptr, &encoder.encode_property, &result, 1, value) != SUCCESS
        || EG(exception)) {
        zval_ptr_dtor(&result);
        return false;
    }
    if (Z_TYPE(result) == IS_STRING) out.assign(Z_STRVAL(result), Z_STRLEN(result));
    zval_ptr_dtor(&result);
    return true;
}

zend_object* refresh_hosted(Encoder& encoder, zend_object* element, zval* holder) {
    if (encoder.refresh_kinds.empty() || !encoder.refresh_kinds.count(element_kind(element))) return element;
    zval argument;
    ZVAL_OBJ(&argument, element);
    if (call_user_function(nullptr, nullptr, &encoder.refresh_hosted, holder, 1, &argument) != SUCCESS
        || EG(exception) || Z_TYPE_P(holder) != IS_OBJECT) {
        return nullptr;
    }
    return Z_OBJ_P(holder);
}

bool reserve_id(Encoder& encoder, std::uint64_t id, std::string_view identity, std::string_view path) {
    auto existing = encoder.id_identities.find(id);
    if (id == 0 || !encoder.ids.insert(id).second
        || (existing != encoder.id_identities.end() && existing->second != identity)) {
        throw_logic("Element identity collision at " + std::string(path) + "; assign a unique key.");
        return false;
    }
    if (existing == encoder.id_identities.end()) encoder.id_identities.emplace(id, std::string(identity));
    return true;
}

bool node_id(Encoder& encoder, std::string_view path, zend_string* dom, zend_long kind, std::uint64_t& id) {
    std::string& identity = encoder.identity;
    identity.clear();
    if (dom == nullptr) {
        identity.append(path);
    } else {
        identity.append("dom:", 4);
        identity.append(ZSTR_VAL(dom), ZSTR_LEN(dom));
    }
    identity.push_back('|');
    char digits[24];
    const int written = std::snprintf(digits, sizeof(digits), ZEND_LONG_FMT, kind);
    identity.append(digits, static_cast<std::size_t>(written));
    auto known = encoder.identity_ids.find(std::string_view(identity));
    if (known != encoder.identity_ids.end()) {
        id = known->second.id;
        if (known->second.owner) {
            // Fast path: this identity owns its id, only a duplicate in this
            // frame can collide.
            if (id == 0 || !encoder.ids.insert(id).second) {
                throw_logic("Element identity collision at " + std::string(path) + "; assign a unique key.");
                return false;
            }
            return true;
        }
        return reserve_id(encoder, id, identity, path);
    }
    // Top 60 bits of the XXH3-64 digest, as TreeEncoder::nodeId().
    id = (XXH3_64bits(identity.data(), identity.size()) >> 4) & 0x0FFFFFFFFFFFFFFFULL;
    auto owner = encoder.id_identities.find(id);
    encoder.identity_ids.emplace(identity, Encoder::KnownIdentity{id, owner == encoder.id_identities.end() || owner->second == identity});
    return reserve_id(encoder, id, identity, path);
}

bool encode_node(Encoder& encoder, zend_object* element, std::uint64_t id, std::uint64_t parent,
                 std::uint32_t index, std::string& path);

using Ranges = std::vector<std::pair<zend_long, zend_long>>;

bool covers(const Ranges& ranges, zend_long start, zend_long end) {
    for (const auto& [from, to] : ranges) {
        if (from <= start && to >= end) return true;
    }
    return false;
}

Ranges add_range(Ranges ranges, zend_long start, zend_long end) {
    ranges.emplace_back(start, end);
    std::stable_sort(ranges.begin(), ranges.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    Ranges merged;
    for (const auto& [from, to] : ranges) {
        if (!merged.empty() && from <= merged.back().second) {
            merged.back().second = std::max(merged.back().second, to);
        } else {
            merged.emplace_back(from, to);
        }
    }
    return merged;
}

bool in_ranges(const Ranges& ranges, zend_long index) {
    for (const auto& [from, to] : ranges) {
        if (index >= from && index < to) return true;
    }
    return false;
}

inline bool property_is_true(zend_object* element, zend_long key) {
    zval* properties = prop(element, layout.properties);
    if (key < 0 || Z_TYPE_P(properties) != IS_ARRAY) return false;
    zval* value = zend_hash_index_find(Z_ARRVAL_P(properties), static_cast<zend_ulong>(key));
    return value != nullptr && Z_TYPE_P(value) == IS_TRUE;
}

// TreeEncoder::windowFor(): the materialized ranges of a long vertical list.
const Ranges* window_for(Encoder& encoder, zend_object* list, const std::string& path) {
    if (encoder.window.virtual_list < 0 || element_kind(list) != encoder.window.virtual_list) return nullptr;
    zval* children = prop(list, layout.children);
    if (Z_TYPE_P(children) != IS_ARRAY || zend_hash_num_elements(Z_ARRVAL_P(children)) <= encoder.window.min_rows) {
        return nullptr;
    }
    if (property_is_true(list, encoder.window.horizontal)) return nullptr;
    auto at = encoder.window_ranges.find(path);
    if (at == encoder.window_ranges.end()) {
        Ranges ranges{{0, static_cast<zend_long>(encoder.window.initial_rows)}};
        zval* properties = prop(list, layout.properties);
        zval* initial = encoder.window.initial_index >= 0 && Z_TYPE_P(properties) == IS_ARRAY
            ? zend_hash_index_find(Z_ARRVAL_P(properties), static_cast<zend_ulong>(encoder.window.initial_index))
            : nullptr;
        if (initial != nullptr && Z_TYPE_P(initial) == IS_LONG && Z_LVAL_P(initial) > 0) {
            const zend_long index = Z_LVAL_P(initial);
            ranges = add_range(
                ranges,
                std::max<zend_long>(0, index - static_cast<zend_long>(encoder.window.margin_rows)),
                index + static_cast<zend_long>(encoder.window.initial_rows));
        }
        at = encoder.window_ranges.emplace(path, std::move(ranges)).first;
    }
    return &at->second;
}

inline bool deferrable(Encoder& encoder, zend_object* row) {
    zval* children = prop(row, layout.children);
    return Z_TYPE_P(children) == IS_ARRAY && zend_hash_num_elements(Z_ARRVAL_P(children)) > 0
        && !property_is_true(row, encoder.window.sticky) && !property_is_true(row, encoder.window.full_span);
}

// extra_key >= 0 adds that property as true (ListWindowed / ListPlaceholder);
// a placeholder (no events) never uses template-encoded values, a windowed
// list neither (TreeEncoder::encodeNode / encodePlaceholder).
bool encode_properties(Encoder& encoder, zend_object* element, std::uint64_t id, NodeRecord& record,
                       zend_long extra_key = -1, bool with_events = true) {
    zval* properties = prop(element, layout.properties);
    zval* events = prop(element, layout.events);
    static zval true_value = [] {
        zval value;
        ZVAL_TRUE(&value);
        return value;
    }();
    // Small per-node property lists: a reused buffer and linear lookups.
    static thread_local std::vector<std::pair<zend_long, zval*>> values;
    values.clear();
    if (Z_TYPE_P(properties) == IS_ARRAY) {
        zend_ulong key;
        zend_string* string_key;
        zval* value;
        ZEND_HASH_FOREACH_KEY_VAL(Z_ARRVAL_P(properties), key, string_key, value) {
            if (string_key != nullptr) continue;
            values.emplace_back(static_cast<zend_long>(key), value);
        } ZEND_HASH_FOREACH_END();
    }
    if (with_events && Z_TYPE_P(events) == IS_ARRAY && zend_hash_num_elements(Z_ARRVAL_P(events)) > 0) {
        zend_ulong kind;
        zend_string* string_key;
        zval* callback;
        ZEND_HASH_FOREACH_KEY_VAL(Z_ARRVAL_P(events), kind, string_key, callback) {
            if (string_key != nullptr) continue;
            char key[48];
            const int length = std::snprintf(key, sizeof(key), "%llu:" ZEND_LONG_FMT,
                                             static_cast<unsigned long long>(id), static_cast<zend_long>(kind));
            Z_TRY_ADDREF_P(callback);
            zend_symtable_str_update(Z_ARRVAL(encoder.callbacks), key, static_cast<std::size_t>(length), callback);
            zval* property = zend_hash_index_find(Z_ARRVAL(encoder.event_properties), kind);
            if (property == nullptr || Z_TYPE_P(property) != IS_LONG) {
                zval argument, ignored;
                ZVAL_LONG(&argument, static_cast<zend_long>(kind));
                call_user_function(nullptr, nullptr, &encoder.event_property, &ignored, 1, &argument);
                zval_ptr_dtor(&ignored);
                return false;  // the reference encoder always throws here
            }
            const zend_long event_key = Z_LVAL_P(property);
            bool replaced = false;
            for (auto& entry : values) {
                if (entry.first == event_key) {
                    entry.second = &true_value;
                    replaced = true;
                    break;
                }
            }
            if (!replaced) values.emplace_back(event_key, &true_value);
        } ZEND_HASH_FOREACH_END();
    }

    if (extra_key >= 0) {
        bool replaced = false;
        for (auto& entry : values) {
            if (entry.first == extra_key) {
                entry.second = &true_value;
                replaced = true;
                break;
            }
        }
        if (!replaced) values.emplace_back(extra_key, &true_value);
    }

    // Wire values the template renderer already encoded for these exact
    // properties are used as they are (same order, same bytes).
    zval* cached = prop(element, layout.encoded_properties);
    if (extra_key < 0 && Z_TYPE_P(cached) == IS_ARRAY && Z_TYPE_P(properties) == IS_ARRAY) {
        zval* source = zend_hash_index_find(Z_ARRVAL_P(cached), 0);
        zval* encoded = zend_hash_index_find(Z_ARRVAL_P(cached), 1);
        if (source != nullptr && encoded != nullptr && Z_TYPE_P(encoded) == IS_ARRAY
            && zend_is_identical(source, properties)) {
            record.values.reserve(zend_hash_num_elements(Z_ARRVAL_P(encoded)));
            zend_ulong key;
            zend_string* string_key;
            zval* value;
            ZEND_HASH_FOREACH_KEY_VAL(Z_ARRVAL_P(encoded), key, string_key, value) {
                if (string_key != nullptr || Z_TYPE_P(value) != IS_STRING) continue;
                record.values.emplace_back(static_cast<std::uint16_t>(key), std::string(Z_STRVAL_P(value), Z_STRLEN_P(value)));
            } ZEND_HASH_FOREACH_END();
            return true;
        }
    }

    std::stable_sort(values.begin(), values.end(),
                     [](const auto& a, const auto& b) { return a.first < b.first; });
    record.values.reserve(values.size());
    for (auto& [key, value] : values) {
        record.values.emplace_back(static_cast<std::uint16_t>(key), std::string());
        if (!encode_value(encoder, value, record.values.back().second)) return false;
    }
    return true;
}

bool encode_node(Encoder& encoder, zend_object* element, std::uint64_t id, std::uint64_t parent,
                 std::uint32_t index, std::string& path) {
    if (++encoder.node_count > kMaxNodes) {
        throw_logic("Pam Native trees cannot exceed 100,000 nodes.");
        return false;
    }
    const Ranges* window = window_for(encoder, element, path);
    NodeRecord record;
    record.id = id;
    record.parent = parent;
    record.index = index;
    record.kind = static_cast<std::uint8_t>(element_kind(element));
    if (!encode_properties(encoder, element, id, record, window != nullptr ? encoder.window.windowed : -1)) return false;
    encoder.nodes.push_back(std::move(record));
    // window points into window_ranges (stable: no insertion below this list
    // can rehash it away... copy to be safe across nested lists).
    Ranges window_copy;
    if (window != nullptr) window_copy = *window;
    std::unordered_map<std::uint64_t, zend_long> row_indexes;

    zval* children = prop(element, layout.children);
    if (Z_TYPE_P(children) != IS_ARRAY || zend_hash_num_elements(Z_ARRVAL_P(children)) == 0) return true;

    // Only positional (unkeyed, non-DOM) segments can repeat ("kind:slot").
    std::unordered_set<std::string, StringHash, std::equal_to<>> positional;
    const std::size_t mark = path.size();
    std::string segment;
    zend_ulong child_index;
    zend_string* string_key;
    zval* child_value;
    ZEND_HASH_FOREACH_KEY_VAL(Z_ARRVAL_P(children), child_index, string_key, child_value) {
        if (string_key != nullptr || Z_TYPE_P(child_value) != IS_OBJECT) continue;
        zval holder;
        ZVAL_UNDEF(&holder);
        zend_object* child = refresh_hosted(encoder, Z_OBJ_P(child_value), &holder);
        if (child == nullptr) {
            zval_ptr_dtor(&holder);
            return false;
        }
        const zend_long child_kind = element_kind(child);
        zend_string* key = nullable_string(child, layout.element_key);
        zend_string* dom = nullable_string(child, layout.dom_identity);
        char digits[48];
        segment.clear();
        if (dom != nullptr) {
            segment.append("dom:", 4);
            segment.append(ZSTR_VAL(dom), ZSTR_LEN(dom));
        } else if (key != nullptr) {
            segment.append("key:", 4);
            segment.append(ZSTR_VAL(key), ZSTR_LEN(key));
        } else {
            segment.append(digits, static_cast<std::size_t>(std::snprintf(digits, sizeof(digits), ZEND_LONG_FMT ":", child_kind)));
            zval* slot = prop(child, layout.identity_slot);
            if (Z_TYPE_P(slot) == IS_STRING) {
                segment.append(Z_STRVAL_P(slot), Z_STRLEN_P(slot));
            } else if (Z_TYPE_P(slot) == IS_LONG) {
                segment.append(digits, static_cast<std::size_t>(std::snprintf(digits, sizeof(digits), ZEND_LONG_FMT, Z_LVAL_P(slot))));
            } else {
                segment.append(digits, static_cast<std::size_t>(std::snprintf(digits, sizeof(digits), ZEND_ULONG_FMT, child_index)));
            }
            if (!positional.insert(segment).second) {
                segment.assign(digits, static_cast<std::size_t>(std::snprintf(
                    digits, sizeof(digits), ZEND_LONG_FMT ":#" ZEND_ULONG_FMT, child_kind, child_index)));
                positional.insert(segment);
            }
        }
        path.push_back('/');
        path.append(segment);
        std::uint64_t child_id = 0;
        bool encoded = node_id(encoder, path, dom, child_kind, child_id);
        if (encoded && window != nullptr) {
            row_indexes[child_id] = static_cast<zend_long>(child_index);
            if (!in_ranges(window_copy, static_cast<zend_long>(child_index)) && deferrable(encoder, child)) {
                // TreeEncoder::encodePlaceholder(): the row root only.
                if (++encoder.node_count > kMaxNodes) {
                    throw_logic("Pam Native trees cannot exceed 100,000 nodes.");
                    encoded = false;
                } else {
                    NodeRecord placeholder;
                    placeholder.id = child_id;
                    placeholder.parent = id;
                    placeholder.index = static_cast<std::uint32_t>(child_index);
                    placeholder.kind = static_cast<std::uint8_t>(child_kind);
                    encoded = encode_properties(encoder, child, child_id, placeholder, encoder.window.placeholder, false);
                    if (encoded) encoder.nodes.push_back(std::move(placeholder));
                }
                path.resize(mark);
                zval_ptr_dtor(&holder);
                if (!encoded) return false;
                continue;
            }
        }
        encoded = encoded
            && encode_node(encoder, child, child_id, id, static_cast<std::uint32_t>(child_index), path);
        path.resize(mark);
        zval_ptr_dtor(&holder);
        if (!encoded) return false;
    } ZEND_HASH_FOREACH_END();
    if (window != nullptr) {
        path.resize(mark);
        auto& list = encoder.window_lists[id];
        list.path = path;
        list.rows = std::move(row_indexes);
    }
    return true;
}

void append_node_bytes(std::string& out, const NodeRecord& node) {
    put_u64(out, node.id);
    put_u64(out, node.parent);
    put_u32(out, node.index);
    out.push_back(static_cast<char>(node.kind));
    put_u16(out, static_cast<std::uint32_t>(node.values.size()));
    for (const auto& [key, value] : node.values) {
        put_u16(out, key);
        out.append(value);
    }
}

void build_full_frame(Encoder& encoder, std::uint64_t root_id, std::string& frame) {
    std::size_t estimate = 18;
    for (const auto& node : encoder.nodes) {
        estimate += 23;
        for (const auto& value : node.values) estimate += 2 + value.second.size();
    }
    frame.reserve(estimate);
    frame.append("PNT1", 4);
    put_u16(frame, 1);
    put_u64(frame, root_id);
    put_u32(frame, static_cast<std::uint32_t>(encoder.node_count));
    for (const auto& node : encoder.nodes) append_node_bytes(frame, node);
}

// PatchBuilder + TreeEncoder::patchFrame(): removals (last frame's order),
// then creates, moves and property updates in this frame's order.
bool build_patch(Encoder& encoder, std::uint64_t root_id, std::string& frame) {
    std::uint32_t count = 0;
    std::string removals, creates, moves, updates;
    for (const auto& previous : encoder.committed) {
        if (encoder.ids.count(previous.id)) continue;
        removals.push_back('\x02');
        put_u64(removals, previous.id);
        ++count;
    }
    for (const auto& node : encoder.nodes) {
        auto at = encoder.committed_index.find(node.id);
        if (at == encoder.committed_index.end()) {
            creates.push_back('\x01');
            append_node_bytes(creates, node);
            ++count;
            continue;
        }
        const NodeRecord& previous = encoder.committed[at->second];
        if (previous.parent != node.parent || previous.index != node.index || previous.kind != node.kind) {
            moves.push_back('\x04');
            put_u64(moves, node.id);
            put_u64(moves, node.parent);
            put_u32(moves, node.index);
            ++count;
        }
        if (previous.values == node.values) continue;
        std::size_t i = 0, j = 0;
        std::vector<std::pair<std::uint16_t, std::string>> before(previous.values), after(node.values);
        std::stable_sort(before.begin(), before.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
        std::stable_sort(after.begin(), after.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
        while (i < before.size() || j < after.size()) {
            const bool take_before = j >= after.size() || (i < before.size() && before[i].first < after[j].first);
            const bool take_after = i >= before.size() || (j < after.size() && after[j].first < before[i].first);
            if (take_before) {
                updates.push_back('\x03');
                put_u64(updates, node.id);
                put_u16(updates, before[i].first);
                updates.push_back('\x02');
                ++count;
                ++i;
            } else if (take_after) {
                updates.push_back('\x03');
                put_u64(updates, node.id);
                put_u16(updates, after[j].first);
                updates.push_back('\x01');
                updates.append(after[j].second);
                ++count;
                ++j;
            } else {
                if (before[i].second != after[j].second) {
                    updates.push_back('\x03');
                    put_u64(updates, node.id);
                    put_u16(updates, after[j].first);
                    updates.push_back('\x01');
                    updates.append(after[j].second);
                    ++count;
                }
                ++i;
                ++j;
            }
        }
    }
    std::string set_root;
    if (encoder.previous_root != root_id) {
        set_root.push_back('\x05');
        put_u64(set_root, root_id);
        ++count;
    }
    if (count == 0) return false;
    frame.reserve(10 + removals.size() + creates.size() + moves.size() + updates.size() + set_root.size());
    frame.append("PNP1", 4);
    put_u16(frame, 1);
    put_u32(frame, count);
    frame += removals;
    frame += creates;
    frame += moves;
    frame += updates;
    frame += set_root;
    return true;
}

Encoder* find_encoder(zend_long handle) {
    auto at = encoders.find(handle);
    if (at == encoders.end()) {
        throw_logic("Unknown native tree encoder.");
        return nullptr;
    }
    return at->second.get();
}

void release(Encoder& encoder) {
    zval_ptr_dtor(&encoder.event_properties);
    zval_ptr_dtor(&encoder.refresh_hosted);
    zval_ptr_dtor(&encoder.encode_property);
    zval_ptr_dtor(&encoder.event_property);
    zval_ptr_dtor(&encoder.last_root);
    zval_ptr_dtor(&encoder.last_callbacks);
    zval_ptr_dtor(&encoder.callbacks);
}

PHP_FUNCTION(pam_native_encoder_create) {
    zval* event_properties;
    zval* refresh_kinds;
    zval* refresh_hosted;
    zval* encode_property;
    zval* event_property;
    zval* window = nullptr;
    ZEND_PARSE_PARAMETERS_START(5, 6)
        Z_PARAM_ARRAY(event_properties)
        Z_PARAM_ARRAY(refresh_kinds)
        Z_PARAM_OBJECT(refresh_hosted)
        Z_PARAM_OBJECT(encode_property)
        Z_PARAM_OBJECT(event_property)
        Z_PARAM_OPTIONAL
        Z_PARAM_ARRAY(window)
    ZEND_PARSE_PARAMETERS_END();
    if (!resolve_layout()) {
        throw_logic("The native tree encoder cannot resolve the Element layout.");
        RETURN_THROWS();
    }
    auto encoder = std::make_unique<Encoder>();
    ZVAL_COPY(&encoder->event_properties, event_properties);
    ZVAL_COPY(&encoder->refresh_hosted, refresh_hosted);
    ZVAL_COPY(&encoder->encode_property, encode_property);
    ZVAL_COPY(&encoder->event_property, event_property);
    ZVAL_UNDEF(&encoder->last_root);
    ZVAL_UNDEF(&encoder->last_callbacks);
    ZVAL_UNDEF(&encoder->callbacks);
    zval* kind;
    ZEND_HASH_FOREACH_VAL(Z_ARRVAL_P(refresh_kinds), kind) {
        if (Z_TYPE_P(kind) == IS_LONG) encoder->refresh_kinds.insert(Z_LVAL_P(kind));
    } ZEND_HASH_FOREACH_END();
    if (window != nullptr) {
        // [virtualList kind, ListPlaceholder, ListWindowed, ListHorizontal,
        //  ListInitialScrollIndex, StickyHeader, ListFullSpan, min rows,
        //  initial rows, margin rows]
        zend_long values[10];
        std::size_t count = 0;
        zval* value;
        ZEND_HASH_FOREACH_VAL(Z_ARRVAL_P(window), value) {
            if (count < 10) values[count++] = Z_TYPE_P(value) == IS_LONG ? Z_LVAL_P(value) : -1;
        } ZEND_HASH_FOREACH_END();
        if (count == 10) {
            auto& w = encoder->window;
            w.virtual_list = values[0];
            w.placeholder = values[1];
            w.windowed = values[2];
            w.horizontal = values[3];
            w.initial_index = values[4];
            w.sticky = values[5];
            w.full_span = values[6];
            w.min_rows = static_cast<std::size_t>(values[7]);
            w.initial_rows = static_cast<std::size_t>(values[8]);
            w.margin_rows = static_cast<std::size_t>(values[9]);
        }
    }
    const zend_long handle = next_handle++;
    encoders.emplace(handle, std::move(encoder));
    RETURN_LONG(handle);
}

PHP_FUNCTION(pam_native_encoder_destroy) {
    zend_long handle;
    ZEND_PARSE_PARAMETERS_START(1, 1)
        Z_PARAM_LONG(handle)
    ZEND_PARSE_PARAMETERS_END();
    auto at = encoders.find(handle);
    if (at != encoders.end()) {
        release(*at->second);
        encoders.erase(at);
    }
}

PHP_FUNCTION(pam_native_encoder_reset) {
    zend_long handle;
    ZEND_PARSE_PARAMETERS_START(1, 1)
        Z_PARAM_LONG(handle)
    ZEND_PARSE_PARAMETERS_END();
    Encoder* encoder = find_encoder(handle);
    if (encoder == nullptr) RETURN_THROWS();
    encoder->has_previous = false;
    zval_ptr_dtor(&encoder->last_root);
    ZVAL_UNDEF(&encoder->last_root);
}

PHP_FUNCTION(pam_native_encoder_encode) {
    zend_long handle;
    zval* root_value;
    ZEND_PARSE_PARAMETERS_START(2, 2)
        Z_PARAM_LONG(handle)
        Z_PARAM_OBJECT_OF_CLASS(root_value, layout.element ? layout.element : zend_standard_class_def)
    ZEND_PARSE_PARAMETERS_END();
    Encoder* encoder = find_encoder(handle);
    if (encoder == nullptr) RETURN_THROWS();

    zval holder;
    ZVAL_UNDEF(&holder);
    zend_object* root = refresh_hosted(*encoder, Z_OBJ_P(root_value), &holder);
    if (root == nullptr) {
        zval_ptr_dtor(&holder);
        RETURN_THROWS();
    }
    // The committed tree is this very root: nothing to encode.
    if (encoder->has_previous && Z_TYPE(encoder->last_root) == IS_OBJECT && Z_OBJ(encoder->last_root) == root
        && encoder->encoded_generation == encoder->window_generation) {
        zval_ptr_dtor(&holder);
        array_init(return_value);
        add_assoc_null(return_value, "frame");
        Z_TRY_ADDREF(encoder->last_callbacks);
        add_assoc_zval(return_value, "callbacks", &encoder->last_callbacks);
        add_assoc_bool(return_value, "full", false);
        return;
    }

    encoder->ids.clear();
    encoder->nodes.clear();
    encoder->node_count = 0;
    zval_ptr_dtor(&encoder->callbacks);
    array_init(&encoder->callbacks);

    std::uint64_t root_id = 0;
    std::string path("root");
    path.reserve(256);
    encoder->nodes.reserve(encoder->committed.size() + 16);
    encoder->ids.reserve(encoder->committed.size() + 16);
    const bool encoded = node_id(*encoder, path, nullable_string(root, layout.dom_identity), element_kind(root), root_id)
        && encode_node(*encoder, root, root_id, 0, 0, path);
    if (!encoded || EG(exception)) {
        zval_ptr_dtor(&holder);
        RETURN_THROWS();
    }

    const bool full = !encoder->has_previous;
    std::string frame;
    bool has_frame = true;
    if (full) {
        build_full_frame(*encoder, root_id, frame);
    } else {
        has_frame = build_patch(*encoder, root_id, frame);
    }

    encoder->committed.swap(encoder->nodes);
    encoder->nodes.clear();
    encoder->committed_index.clear();
    encoder->committed_index.reserve(encoder->committed.size());
    for (std::size_t i = 0; i < encoder->committed.size(); ++i) encoder->committed_index.emplace(encoder->committed[i].id, i);
    encoder->previous_root = root_id;
    encoder->has_previous = true;
    encoder->encoded_generation = encoder->window_generation;
    // Windows of lists that are no longer in the tree are forgotten.
    for (auto it = encoder->window_lists.begin(); it != encoder->window_lists.end();) {
        if (encoder->committed_index.count(it->first)) {
            ++it;
            continue;
        }
        encoder->window_ranges.erase(it->second.path);
        it = encoder->window_lists.erase(it);
    }
    zval_ptr_dtor(&encoder->last_root);
    ZVAL_OBJ_COPY(&encoder->last_root, root);
    zval_ptr_dtor(&encoder->last_callbacks);
    ZVAL_COPY(&encoder->last_callbacks, &encoder->callbacks);
    zval_ptr_dtor(&holder);

    array_init(return_value);
    if (has_frame) {
        add_assoc_stringl(return_value, "frame", frame.data(), frame.size());
    } else {
        add_assoc_null(return_value, "frame");
    }
    Z_TRY_ADDREF(encoder->callbacks);
    add_assoc_zval(return_value, "callbacks", &encoder->callbacks);
    add_assoc_bool(return_value, "full", full);
}

// TreeEncoder::extendWindow().
PHP_FUNCTION(pam_native_encoder_extend_window) {
    zend_long handle, list_id, first_id, last_id;
    ZEND_PARSE_PARAMETERS_START(4, 4)
        Z_PARAM_LONG(handle)
        Z_PARAM_LONG(list_id)
        Z_PARAM_LONG(first_id)
        Z_PARAM_LONG(last_id)
    ZEND_PARSE_PARAMETERS_END();
    Encoder* encoder = find_encoder(handle);
    if (encoder == nullptr) RETURN_THROWS();
    auto list = encoder->window_lists.find(static_cast<std::uint64_t>(list_id));
    if (list == encoder->window_lists.end()) RETURN_FALSE;
    const auto& rows = list->second.rows;
    auto first_at = rows.find(static_cast<std::uint64_t>(first_id));
    if (first_at == rows.end()) RETURN_FALSE;
    zend_long first = first_at->second;
    auto last_at = rows.find(static_cast<std::uint64_t>(last_id));
    zend_long last = last_at == rows.end() ? first : last_at->second;
    if (last < first) std::swap(first, last);
    const zend_long margin = std::max<zend_long>(static_cast<zend_long>(encoder->window.margin_rows), (last - first + 1) * 2);
    Ranges& ranges = encoder->window_ranges[list->second.path];
    if (covers(ranges, std::max<zend_long>(0, first - margin / 2), last + margin / 2 + 1)) RETURN_FALSE;
    ranges = add_range(ranges, std::max<zend_long>(0, first - margin), last + margin + 1);
    ++encoder->window_generation;
    RETURN_TRUE;
}

PHP_FUNCTION(pam_native_encoder_nodes) {
    zend_long handle;
    ZEND_PARSE_PARAMETERS_START(1, 1)
        Z_PARAM_LONG(handle)
    ZEND_PARSE_PARAMETERS_END();
    Encoder* encoder = find_encoder(handle);
    if (encoder == nullptr) RETURN_THROWS();
    array_init_size(return_value, static_cast<uint32_t>(encoder->committed.size()));
    for (const auto& node : encoder->committed) {
        zval entry, values;
        array_init_size(&entry, 5);
        add_next_index_long(&entry, static_cast<zend_long>(node.id));
        add_next_index_long(&entry, static_cast<zend_long>(node.parent));
        add_next_index_long(&entry, static_cast<zend_long>(node.index));
        add_next_index_long(&entry, node.kind);
        array_init_size(&values, static_cast<uint32_t>(node.values.size()));
        for (const auto& [key, value] : node.values) add_index_stringl(&values, key, value.data(), value.size());
        add_next_index_zval(&entry, &values);
        add_next_index_zval(return_value, &entry);
    }
}

// Style::properties(): the non-null fields as wire properties, in KEYS
// order (enums as their backing values, GridTemplate via toWire()).
struct StyleLayout {
    zend_class_entry* style = nullptr;
    zend_class_entry* grid_template = nullptr;
    std::vector<std::pair<std::uint32_t, zend_long>> fields;  // property offset, wire key
    bool ready = false;
};
StyleLayout style_layout;

bool resolve_style_layout(zend_object* style, zval* keys) {
    if (style_layout.ready && style_layout.style == style->ce) return true;
    style_layout = StyleLayout{};
    style_layout.style = style->ce;
    zend_string* grid = zend_string_init("Pam\\Native\\GridTemplate", sizeof("Pam\\Native\\GridTemplate") - 1, 0);
    style_layout.grid_template = zend_lookup_class(grid);
    zend_string_release(grid);
    zend_string* field;
    zval* key;
    ZEND_HASH_FOREACH_STR_KEY_VAL(Z_ARRVAL_P(keys), field, key) {
        if (field == nullptr || Z_TYPE_P(key) != IS_LONG) return false;
        auto* info = static_cast<zend_property_info*>(zend_hash_find_ptr(&style->ce->properties_info, field));
        if (info == nullptr || (info->flags & ZEND_ACC_STATIC)) return false;
        style_layout.fields.emplace_back(info->offset, Z_LVAL_P(key));
    } ZEND_HASH_FOREACH_END();
    style_layout.ready = true;
    return true;
}

PHP_FUNCTION(pam_native_style_properties) {
    zval* style_value;
    zval* keys;
    ZEND_PARSE_PARAMETERS_START(2, 2)
        Z_PARAM_OBJECT(style_value)
        Z_PARAM_ARRAY(keys)
    ZEND_PARSE_PARAMETERS_END();
    zend_object* style = Z_OBJ_P(style_value);
    if (!resolve_style_layout(style, keys)) {
        throw_logic("The native style reader cannot resolve the Style layout.");
        RETURN_THROWS();
    }
    array_init(return_value);
    for (const auto& [offset, key] : style_layout.fields) {
        zval* value = OBJ_PROP(style, offset);
        ZVAL_DEREF(value);
        if (Z_TYPE_P(value) == IS_NULL || Z_TYPE_P(value) == IS_UNDEF) continue;
        if (Z_TYPE_P(value) == IS_OBJECT) {
            zend_object* object = Z_OBJ_P(value);
            if (object->ce->ce_flags & ZEND_ACC_ENUM) {
                zval* backing = zend_enum_fetch_case_value(object);
                Z_TRY_ADDREF_P(backing);
                zend_hash_index_update(Z_ARRVAL_P(return_value), static_cast<zend_ulong>(key), backing);
                continue;
            }
            if (style_layout.grid_template != nullptr && object->ce == style_layout.grid_template) {
                zval wire;
                zend_call_method_with_0_params(object, object->ce, nullptr, "towire", &wire);
                if (EG(exception)) {
                    zval_ptr_dtor(&wire);
                    RETURN_THROWS();
                }
                zend_hash_index_update(Z_ARRVAL_P(return_value), static_cast<zend_ulong>(key), &wire);
                continue;
            }
        }
        Z_TRY_ADDREF_P(value);
        zend_hash_index_update(Z_ARRVAL_P(return_value), static_cast<zend_ulong>(key), value);
    }
}

}  // namespace pam_tree_encoder_detail

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_style_properties, 0, 2, IS_ARRAY, 0)
    ZEND_ARG_TYPE_INFO(0, style, IS_OBJECT, 0)
    ZEND_ARG_TYPE_INFO(0, keys, IS_ARRAY, 0)
ZEND_END_ARG_INFO()

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_encoder_extend_window, 0, 4, _IS_BOOL, 0)
    ZEND_ARG_TYPE_INFO(0, handle, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, listId, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, firstRowId, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, lastRowId, IS_LONG, 0)
ZEND_END_ARG_INFO()

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_encoder_create, 0, 5, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, eventProperties, IS_ARRAY, 0)
    ZEND_ARG_TYPE_INFO(0, refreshKinds, IS_ARRAY, 0)
    ZEND_ARG_TYPE_INFO(0, refreshHosted, IS_OBJECT, 0)
    ZEND_ARG_TYPE_INFO(0, encodeProperty, IS_OBJECT, 0)
    ZEND_ARG_TYPE_INFO(0, eventProperty, IS_OBJECT, 0)
    ZEND_ARG_TYPE_INFO(0, window, IS_ARRAY, 0)
ZEND_END_ARG_INFO()

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_encoder_handle, 0, 1, IS_VOID, 0)
    ZEND_ARG_TYPE_INFO(0, handle, IS_LONG, 0)
ZEND_END_ARG_INFO()

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_encoder_encode, 0, 2, IS_ARRAY, 0)
    ZEND_ARG_TYPE_INFO(0, handle, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, root, IS_OBJECT, 0)
ZEND_END_ARG_INFO()

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_encoder_nodes, 0, 1, IS_ARRAY, 0)
    ZEND_ARG_TYPE_INFO(0, handle, IS_LONG, 0)
ZEND_END_ARG_INFO()

const zend_function_entry pam_tree_encoder_functions[] = {
    ZEND_RAW_FENTRY("pam_native_encoder_create", pam_tree_encoder_detail::zif_pam_native_encoder_create, arginfo_pam_native_encoder_create, 0, nullptr, nullptr)
    ZEND_RAW_FENTRY("pam_native_encoder_destroy", pam_tree_encoder_detail::zif_pam_native_encoder_destroy, arginfo_pam_native_encoder_handle, 0, nullptr, nullptr)
    ZEND_RAW_FENTRY("pam_native_encoder_reset", pam_tree_encoder_detail::zif_pam_native_encoder_reset, arginfo_pam_native_encoder_handle, 0, nullptr, nullptr)
    ZEND_RAW_FENTRY("pam_native_encoder_encode", pam_tree_encoder_detail::zif_pam_native_encoder_encode, arginfo_pam_native_encoder_encode, 0, nullptr, nullptr)
    ZEND_RAW_FENTRY("pam_native_encoder_extend_window", pam_tree_encoder_detail::zif_pam_native_encoder_extend_window, arginfo_pam_native_encoder_extend_window, 0, nullptr, nullptr)
    ZEND_RAW_FENTRY("pam_native_style_properties", pam_tree_encoder_detail::zif_pam_native_style_properties, arginfo_pam_native_style_properties, 0, nullptr, nullptr)
    ZEND_RAW_FENTRY("pam_native_encoder_nodes", pam_tree_encoder_detail::zif_pam_native_encoder_nodes, arginfo_pam_native_encoder_nodes, 0, nullptr, nullptr)
    ZEND_FE_END
};

void pam_tree_encoder_shutdown() {
    using namespace pam_tree_encoder_detail;
    for (auto& [handle, encoder] : encoders) release(*encoder);
    encoders.clear();
    layout = ElementLayout{};
    style_layout = StyleLayout{};
}
