#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include <sapi/embed/php_embed.h>
#include <Zend/zend_exceptions.h>
#include <Zend/zend_execute.h>

#include "pam_native_ios_bridge.h"
#include "pam_native_engine.h"

namespace {

constexpr std::size_t kMaxEventBytes = 1024 * 1024;
// Module results (file reads, base64 media, large JSON) may legitimately be
// bigger than UI events. Anything above this is never dropped silently: PHP
// receives a failure so its callback always runs.
constexpr std::size_t kMaxModuleResultBytes = 32 * 1024 * 1024;
constexpr std::int32_t kModuleResultFailure = 2;

std::string oversized_module_result_message(std::size_t size) {
    return "Native module result exceeds the bridge limit (" + std::to_string(size)
        + " bytes > " + std::to_string(kMaxModuleResultBytes) + " bytes)";
}
constexpr std::size_t kMaxQueuedEvents = 1024;

enum class EventType : std::uint8_t {
    Ui = 1,
    ModuleResult = 2,
    Reload = 3,
    // A recreated host view attached to the live runtime: publish the
    // retained tree as a full mount, in order with other batches.
    Remount = 5,
};

struct Event {
    EventType type;
    std::int64_t first;
    std::int32_t second;
    std::string payload;
};

struct RuntimeState;

struct PublishedBatch {
    explicit PublishedBatch(PamNativeBuffer value) : buffer(value) {}

    ~PublishedBatch() {
        pam_native_buffer_free(buffer);
    }

    PamNativeBuffer buffer;
};

struct RuntimeState {
    std::string entry;
    std::string state_dir;
    std::string php_executable = "pam-native";
    std::string php_entry_argument;
    std::array<char*, 3> php_arguments = {nullptr, nullptr, nullptr};
    bool dark_appearance = false;
    float width = 0.0f;
    float height = 0.0f;
    float text_scale = 0.0f;
    PamNativeEngineHandle* engine = nullptr;
    PamNativeRuntimeCallbacks callbacks;
    std::atomic<PamNativeBatchCallback> on_remount{nullptr};

    std::mutex queue_mutex;
    std::condition_variable queue_ready;
    std::deque<Event> events;

    std::mutex engine_mutex;
    std::thread worker;

    std::atomic<bool> stopping = false;
};

thread_local RuntimeState* active_runtime = nullptr;

std::atomic<PamNativeMeasureTextCallback> host_text_measurer{nullptr};
std::mutex boot_safe_area_mutex;
bool boot_safe_area_set = false;
std::array<float, 4> boot_safe_area{0.0F, 0.0F, 0.0F, 0.0F};

// Text boxes are measured by the same CoreText pipeline that draws them
// (PamTextLayout.swift), like React Native's Yoga measure functions.
std::int32_t measure_text(
    void* /* context */,
    const PamTextMeasureRequest* request,
    PamTextMeasureResult* result
) {
    const auto callback = host_text_measurer.load();
    if (callback == nullptr || request == nullptr || result == nullptr) {
        return 0;
    }
    std::array<float, 4> output{0.0F, 0.0F, 0.0F, 0.0F};
    const auto measured = callback(
        request->node_id,
        request->text,
        request->text_length,
        request->spans,
        request->spans_length,
        request->font_family,
        request->font_family_length,
        request->font_features,
        request->font_features_length,
        request->font_size,
        request->font_scale,
        request->letter_spacing,
        request->line_height,
        request->available_width,
        static_cast<std::int32_t>(request->font_weight),
        static_cast<std::int32_t>(request->italic),
        static_cast<std::int32_t>(request->text_transform),
        request->max_lines,
        output.data()
    );
    if (measured == 0) {
        return 0;
    }
    result->width = output[0];
    result->height = output[1];
    result->first_baseline = output[2];
    result->line_count = static_cast<std::uint32_t>(output[3] > 0.0F ? output[3] : 0.0F);
    return 1;
}

void log_debug(const char* message) {
    fprintf(stdout, "%s\n", message);
}

void log_error(const char* message) {
    fprintf(stderr, "%s\n", message);
}

void report_error(RuntimeState* state, const std::string& message) {
    if (state == nullptr || message.empty()) {
        return;
    }
    log_error(message.c_str());
    if (state->callbacks.on_error != nullptr) {
        state->callbacks.on_error(
            reinterpret_cast<std::uint64_t>(state),
            message.c_str()
        );
    }
}

void publish_batch(RuntimeState* state, PamNativeBuffer buffer) {
    if (state == nullptr || state->callbacks.on_batch == nullptr) {
        return;
    }
    if (buffer.data == nullptr || buffer.length == 0 || buffer.length > kMaxEventBytes) {
        return;
    }

    auto published = std::make_unique<PublishedBatch>(buffer);
    const auto handle = reinterpret_cast<std::uint64_t>(published.get());
    const auto accepted = state->callbacks.on_batch(
        reinterpret_cast<std::uint64_t>(state),
        static_cast<const std::uint8_t*>(buffer.data),
        buffer.length,
        handle
    );
    if (accepted) {
        published.release();
    }
}

// Always calls back (NULL bytes when there is no tree yet) so the host can
// close its remount window. A full mount may exceed the event size limit.
void publish_remount(RuntimeState* state) {
    const PamNativeBatchCallback callback = state->on_remount.load();
    if (callback == nullptr) {
        return;
    }
    PamNativeBuffer buffer{nullptr, 0, 0};
    PamStatus status;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        status = pam_native_engine_remount(state->engine, &buffer);
    }
    if (status != PAM_STATUS_SUCCESS) {
        pam_native_buffer_free(buffer);
        buffer = PamNativeBuffer{nullptr, 0, 0};
    }
    auto published = std::make_unique<PublishedBatch>(buffer);
    const bool empty = buffer.data == nullptr || buffer.length == 0;
    const auto handle = empty ? std::uint64_t{0} : reinterpret_cast<std::uint64_t>(published.get());
    const auto accepted = callback(
        reinterpret_cast<std::uint64_t>(state),
        empty ? nullptr : static_cast<const std::uint8_t*>(buffer.data),
        empty ? 0 : buffer.length,
        handle
    );
    if (accepted && !empty) {
        published.release();
    }
}

void publish_call(
    RuntimeState* state,
    std::int64_t request_id,
    const char* module,
    const char* method,
    const char* payload,
    std::size_t payload_length
) {
    if (
        state == nullptr ||
        state->callbacks.on_call == nullptr ||
        module == nullptr ||
        method == nullptr
    ) {
        return;
    }
    if (payload_length > kMaxEventBytes) {
        return;
    }
    state->callbacks.on_call(
        reinterpret_cast<std::uint64_t>(state),
        request_id,
        module,
        method,
        reinterpret_cast<const std::uint8_t*>(payload),
        payload_length
    );
}

void publish_typed_call(
    RuntimeState* state,
    std::int64_t request_id,
    std::int32_t operation,
    const char* payload,
    std::size_t payload_length
) {
    if (state == nullptr || state->callbacks.on_typed_call == nullptr) {
        return;
    }
    if (payload_length > kMaxEventBytes) {
        return;
    }
    state->callbacks.on_typed_call(
        reinterpret_cast<std::uint64_t>(state),
        request_id,
        operation,
        reinterpret_cast<const std::uint8_t*>(payload),
        payload_length
    );
}

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_commit, 0, 1, _IS_BOOL, 0)
    ZEND_ARG_TYPE_INFO(0, frame, IS_STRING, 0)
ZEND_END_ARG_INFO()

PHP_FUNCTION(pam_native_commit) {
    char* frame = nullptr;
    size_t frame_length = 0;

    ZEND_PARSE_PARAMETERS_START(1, 1)
        Z_PARAM_STRING(frame, frame_length)
    ZEND_PARSE_PARAMETERS_END();

    RuntimeState* state = active_runtime;
    if (state == nullptr || frame_length == 0) {
        RETURN_FALSE;
    }

    PamNativeBuffer batch{nullptr, 0, 0};
    PamStatus status;
    std::string detail;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        status = pam_native_engine_commit(
            state->engine,
            reinterpret_cast<const std::uint8_t*>(frame),
            frame_length,
            &batch
        );
        if (status != PAM_STATUS_SUCCESS) {
            PamNativeBuffer error_buffer{nullptr, 0, 0};
            if (
                pam_native_engine_last_error(state->engine, &error_buffer)
                    == PAM_STATUS_SUCCESS
                && error_buffer.data != nullptr
                && error_buffer.length > 0
            ) {
                detail.assign(
                    reinterpret_cast<const char*>(error_buffer.data),
                    error_buffer.length
                );
            }
            pam_native_buffer_free(error_buffer);
        }
    }

    if (status != PAM_STATUS_SUCCESS) {
        const bool is_patch =
            frame_length >= 4 && std::memcmp(frame, "PNP1", 4) == 0;
        if (is_patch) {
            log_error(
                (
                    "Pam Native rejected an incremental render frame; "
                    "requesting a full-tree recovery. " + detail
                ).c_str()
            );
        } else {
            report_error(
                state,
                "Pam Native rejected an invalid render frame. " + detail
            );
        }
        RETURN_FALSE;
    }
    publish_batch(state, batch);
    RETURN_TRUE;
}

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_call, 0, 4, _IS_BOOL, 0)
    ZEND_ARG_TYPE_INFO(0, request_id, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, module, IS_STRING, 0)
    ZEND_ARG_TYPE_INFO(0, method, IS_STRING, 0)
    ZEND_ARG_TYPE_INFO(0, payload, IS_STRING, 0)
ZEND_END_ARG_INFO()

PHP_FUNCTION(pam_native_call) {
    zend_long request_id = 0;
    char* module = nullptr;
    size_t module_length = 0;
    char* method = nullptr;
    size_t method_length = 0;
    char* payload = nullptr;
    size_t payload_length = 0;

    ZEND_PARSE_PARAMETERS_START(4, 4)
        Z_PARAM_LONG(request_id)
        Z_PARAM_STRING(module, module_length)
        Z_PARAM_STRING(method, method_length)
        Z_PARAM_STRING(payload, payload_length)
    ZEND_PARSE_PARAMETERS_END();

    RuntimeState* state = active_runtime;
    if (state == nullptr || module_length == 0 || method_length == 0) {
        RETURN_FALSE;
    }
    publish_call(
        state,
        static_cast<std::int64_t>(request_id),
        module,
        method,
        payload,
        payload_length
    );
    RETURN_TRUE;
}

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_call_typed, 0, 3, _IS_BOOL, 0)
    ZEND_ARG_TYPE_INFO(0, request_id, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, operation, IS_LONG, 0)
    ZEND_ARG_TYPE_INFO(0, payload, IS_STRING, 0)
ZEND_END_ARG_INFO()

PHP_FUNCTION(pam_native_call_typed) {
    zend_long request_id = 0;
    zend_long operation = 0;
    char* payload = nullptr;
    size_t payload_length = 0;

    ZEND_PARSE_PARAMETERS_START(3, 3)
        Z_PARAM_LONG(request_id)
        Z_PARAM_LONG(operation)
        Z_PARAM_STRING(payload, payload_length)
    ZEND_PARSE_PARAMETERS_END();

    RuntimeState* state = active_runtime;
    if (state == nullptr || operation <= 0 || operation > INT32_MAX) {
        RETURN_FALSE;
    }
    publish_typed_call(
        state,
        static_cast<std::int64_t>(request_id),
        static_cast<std::int32_t>(operation),
        payload,
        payload_length
    );
    RETURN_TRUE;
}

ZEND_BEGIN_ARG_WITH_RETURN_TYPE_INFO_EX(arginfo_pam_native_error, 0, 1, IS_VOID, 0)
    ZEND_ARG_TYPE_INFO(0, message, IS_STRING, 0)
ZEND_END_ARG_INFO()

PHP_FUNCTION(pam_native_error) {
    char* message = nullptr;
    size_t message_length = 0;
    ZEND_PARSE_PARAMETERS_START(1, 1)
        Z_PARAM_STRING(message, message_length)
    ZEND_PARSE_PARAMETERS_END();

    RuntimeState* state = active_runtime;
    if (state == nullptr || message == nullptr) {
        return;
    }
    report_error(state, std::string(message, message_length));
}

const zend_function_entry pam_native_functions[] = {
    PHP_FE(pam_native_call, arginfo_pam_native_call)
    PHP_FE(pam_native_call_typed, arginfo_pam_native_call_typed)
    PHP_FE(pam_native_commit, arginfo_pam_native_commit)
    PHP_FE(pam_native_error, arginfo_pam_native_error)
    PHP_FE_END
};

bool register_php_runtime_api(RuntimeState* state) {
    if (zend_register_functions(
            nullptr,
            pam_native_functions,
            nullptr,
            MODULE_TEMPORARY
        ) == FAILURE) {
        report_error(state, "Pam Native failed to register its PHP runtime API.");
        return false;
    }
    return true;
}

bool call_runtime(
    const char* method,
    std::uint32_t argument_count,
    zval* arguments
) {
    zval callable;
    zval result;
    array_init(&callable);
    add_next_index_string(&callable, "Pam\\Native\\Internal\\Runtime");
    add_next_index_string(&callable, method);
    ZVAL_UNDEF(&result);

    const int status = call_user_function(
        EG(function_table),
        nullptr,
        &callable,
        &result,
        static_cast<uint32_t>(argument_count),
        arguments
    );
    zval_ptr_dtor(&callable);
    if (!Z_ISUNDEF(result)) {
        zval_ptr_dtor(&result);
    }

    return status == SUCCESS && !EG(exception);
}

bool runtime_has_method(const char* method) {
    zend_string* name = zend_string_init(
        "Pam\\Native\\Internal\\Runtime",
        sizeof("Pam\\Native\\Internal\\Runtime") - 1,
        0
    );
    zend_class_entry* entry = zend_lookup_class(name);
    zend_string_release(name);
    if (entry == nullptr) {
        return false;
    }
    return zend_hash_str_exists(&entry->function_table, method, std::strlen(method));
}

bool enable_deferred_rendering() {
    if (!runtime_has_method("deferrendering") || !runtime_has_method("flush")) {
        return false;
    }
    zval argument;
    ZVAL_TRUE(&argument);
    const bool enabled = call_runtime("deferRendering", 1, &argument);
    if (EG(exception)) {
        zend_clear_exception();
        return false;
    }
    return enabled;
}

void dispatch_event(const Event& event) {
    if (event.type == EventType::Ui) {
        zval arguments[3];
        ZVAL_LONG(&arguments[0], event.first);
        ZVAL_LONG(&arguments[1], event.second);
        ZVAL_STRINGL(&arguments[2], event.payload.data(), event.payload.size());
        call_runtime("dispatchEvent", 3, arguments);
        zval_ptr_dtor(&arguments[2]);
    } else if (event.type == EventType::ModuleResult) {
        zval arguments[3];
        ZVAL_LONG(&arguments[0], event.first);
        ZVAL_LONG(&arguments[1], event.second);
        ZVAL_STRINGL(&arguments[2], event.payload.data(), event.payload.size());
        call_runtime("dispatchModuleResult", 3, arguments);
        zval_ptr_dtor(&arguments[2]);
    }
}

bool initialize_php(RuntimeState* state) {
    setenv("PAM_NATIVE_STATE_DIR", state->state_dir.c_str(), 1);
    setenv("PAM_SYSTEM_DARK", state->dark_appearance ? "1" : "0", 1);
    php_embed_module.ini_entries =
        "max_execution_time=0\n"
        "max_input_time=-1\n";

    state->php_entry_argument = state->entry;
    state->php_arguments = {
        state->php_executable.data(),
        state->php_entry_argument.data(),
        nullptr,
    };

    if (php_embed_init(2, state->php_arguments.data()) == FAILURE) {
        report_error(state, "PHP Embed failed to initialize on iOS.");
        return false;
    }
    if (!register_php_runtime_api(state)) {
        php_embed_shutdown();
        return false;
    }

    zend_unset_timeout();
    return true;
}

bool run_php_request(RuntimeState* state) {
    zend_file_handle file_handle;
    bool reload = false;

    zend_stream_init_filename(&file_handle, state->entry.c_str());
    const int status = php_execute_script(&file_handle);
    log_debug("PHP entry execution returned.");
    if (status == FAILURE || EG(exception)) {
        report_error(state, "The Pam Native PHP entry failed during execution.");
        if (EG(exception)) {
            zend_clear_exception();
        }
    }

    // Newer PHP SDKs coalesce rendering until flush(): drain the queue (or a
    // 12 ms budget) and render once instead of once per event/module result.
    const bool deferred = enable_deferred_rendering();
    bool draining = false;
    int events_since_gc = 0;
    auto drain_started = std::chrono::steady_clock::now();
    auto last_gc = drain_started;
    while (!state->stopping.load(std::memory_order_acquire)) {
        Event event;
        bool queue_empty = false;
        {
            std::unique_lock<std::mutex> lock(state->queue_mutex);
            state->queue_ready.wait(lock, [&] {
                return state->stopping.load(std::memory_order_acquire)
                    || !state->events.empty();
            });

            if (state->stopping.load(std::memory_order_acquire)) {
                break;
            }

            event = std::move(state->events.front());
            state->events.pop_front();
            queue_empty = state->events.empty();
        }

        if (event.type == EventType::Reload) {
            if (!event.payload.empty()) {
                state->entry = event.payload;
            }
            reload = true;
            break;
        }

        if (!draining) {
            draining = true;
            drain_started = std::chrono::steady_clock::now();
        }
        if (event.type == EventType::Remount) {
            publish_remount(state);
        } else {
            dispatch_event(event);
        }

        if (EG(exception)) {
            report_error(state, "Unhandled PHP exception in a Pam Native event.");
            zend_clear_exception();
        }
        ++events_since_gc;
        if (deferred
            && !queue_empty
            && std::chrono::steady_clock::now() - drain_started < std::chrono::milliseconds(12)) {
            continue;
        }
        if (deferred) {
            call_runtime("flush", 0, nullptr);
            if (EG(exception)) {
                report_error(state, "Unhandled PHP exception while rendering.");
                zend_clear_exception();
            }
        }
        draining = false;
        const auto after = std::chrono::steady_clock::now();
        // Cycle collection when idle instead of after every event.
        if (queue_empty && (events_since_gc >= 64 || after - last_gc >= std::chrono::seconds(2))) {
            gc_collect_cycles();
            events_since_gc = 0;
            last_gc = after;
        }
    }

    zval result;
    ZVAL_UNDEF(&result);
    call_runtime("shutdown", 0, nullptr);
    if (!Z_ISUNDEF(result)) {
        zval_ptr_dtor(&result);
    }

    return reload;
}

void runtime_loop(RuntimeState* state) {
    active_runtime = state;
    if (initialize_php(state)) {
        while (
            !state->stopping.load(std::memory_order_acquire)
            && run_php_request(state)
        ) {
            log_debug("Restarting PHP request for hot reload.");
            php_request_shutdown(nullptr);
            SG(request_info).argc = 2;
            SG(request_info).argv = state->php_arguments.data();
            if (php_request_startup() == FAILURE) {
                report_error(state, "PHP request failed to restart during hot reload.");
                break;
            }
            if (!register_php_runtime_api(state)) {
                break;
            }
            zend_unset_timeout();
            SG(headers_sent) = 1;
            SG(request_info).no_headers = 1;
            php_register_variable("PHP_SELF", "-", nullptr);
        }
        php_embed_shutdown();
    }
    active_runtime = nullptr;
}

void enqueue(RuntimeState* state, Event event, bool coalesce) {
    if (state == nullptr || state->stopping.load(std::memory_order_acquire)) {
        return;
    }
    const std::size_t limit = event.type == EventType::ModuleResult
        ? kMaxModuleResultBytes
        : kMaxEventBytes;
    if (event.payload.size() > limit) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(state->queue_mutex);
        if (coalesce) {
            for (auto iterator = state->events.rbegin(); iterator != state->events.rend(); ++iterator) {
                if (
                    iterator->type == event.type
                    && iterator->first == event.first
                    && iterator->second == event.second
                ) {
                    iterator->payload = std::move(event.payload);
                    state->queue_ready.notify_one();
                    return;
                }
            }
        }

        if (state->events.size() >= kMaxQueuedEvents) {
            if (event.type == EventType::Ui) {
                return;
            }
            // Results and reloads are completion/lifecycle signals, not
            // disposable input. Reclaim an older UI slot under pressure and
            // allow an all-critical queue to drain without losing callbacks.
            auto disposable = state->events.begin();
            while (
                disposable != state->events.end()
                && disposable->type != EventType::Ui
            ) {
                ++disposable;
            }
            if (disposable != state->events.end()) {
                state->events.erase(disposable);
            }
        }

        if (event.type == EventType::Ui) {
            state->events.push_back(std::move(event));
        } else {
            // Retain FIFO order for critical results/reloads, ahead of input
            // that can be safely delayed or coalesced.
            auto insertion = state->events.begin();
            while (
                insertion != state->events.end()
                && insertion->type != EventType::Ui
            ) {
                ++insertion;
            }
            state->events.insert(insertion, std::move(event));
        }
    }

    state->queue_ready.notify_one();
}

RuntimeState* from_handle(uint64_t handle) {
    return reinterpret_cast<RuntimeState*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

uint64_t pam_native_runtime_start(
    const char* entry,
    const char* state_directory,
    float width_dp,
    float height_dp,
    float text_scale,
    bool dark_appearance,
    PamNativeBatchCallback on_batch,
    PamNativeCallCallback on_call,
    PamNativeTypedCallCallback on_typed_call,
    PamNativeErrorCallback on_error
) {
    if (
        entry == nullptr
        || state_directory == nullptr
        || width_dp <= 0
        || height_dp <= 0
        || text_scale <= 0
    ) {
        return 0;
    }

    if (
        on_batch == nullptr
        || on_call == nullptr
        || on_typed_call == nullptr
        || on_error == nullptr
    ) {
        return 0;
    }

    auto state = std::make_unique<RuntimeState>();
    state->callbacks = PamNativeRuntimeCallbacks{
        on_batch,
        on_call,
        on_typed_call,
        on_error,
    };
    state->entry = entry;
    state->state_dir = state_directory;
    state->dark_appearance = dark_appearance;
    state->width = width_dp;
    state->height = height_dp;
    state->text_scale = text_scale;

    state->engine = pam_native_engine_new();
    const auto entry_separator = state->entry.find_last_of("/\\");
    const auto asset_root = entry_separator == std::string::npos
        ? std::string()
        : state->entry.substr(0, entry_separator);
    const auto asset_root_status = asset_root.empty()
        ? PAM_STATUS_SUCCESS
        : pam_native_engine_set_asset_root(
            state->engine,
            reinterpret_cast<const uint8_t*>(asset_root.data()),
            asset_root.size()
        );
    if (
        state->engine == nullptr
        || asset_root_status != PAM_STATUS_SUCCESS
        || pam_native_engine_set_viewport(state->engine, width_dp, height_dp)
            != PAM_STATUS_SUCCESS
        || pam_native_engine_set_text_scale(state->engine, text_scale)
            != PAM_STATUS_SUCCESS
    ) {
        if (state->engine != nullptr) {
            pam_native_engine_free(state->engine);
            state->engine = nullptr;
        }
        return 0;
    }
    if (host_text_measurer.load() != nullptr) {
        pam_native_engine_set_text_measurer(state->engine, measure_text, state.get());
    }
    {
        std::lock_guard<std::mutex> lock(boot_safe_area_mutex);
        if (boot_safe_area_set) {
            pam_native_engine_set_safe_area_insets(
                state->engine,
                boot_safe_area[0],
                boot_safe_area[1],
                boot_safe_area[2],
                boot_safe_area[3]
            );
        }
    }

    auto handle = reinterpret_cast<uint64_t>(state.get());
    state->worker = std::thread(runtime_loop, state.get());
    state.release();

    return handle;
}

void pam_native_ios_set_text_measurer(PamNativeMeasureTextCallback callback) {
    host_text_measurer.store(callback);
}

void pam_native_ios_set_boot_safe_area_insets(float left, float top, float right, float bottom) {
    std::lock_guard<std::mutex> lock(boot_safe_area_mutex);
    boot_safe_area = {left, top, right, bottom};
    boot_safe_area_set = true;
}

void pam_native_runtime_set_safe_area_insets(
    uint64_t handle,
    float left,
    float top,
    float right,
    float bottom
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(state->engine_mutex);
    pam_native_engine_set_safe_area_insets(state->engine, left, top, right, bottom);
}

void pam_native_runtime_relayout(
    uint64_t handle,
    float width_dp,
    float height_dp,
    float text_scale,
    bool dark_appearance
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || width_dp <= 0 || height_dp <= 0 || text_scale <= 0) {
        return;
    }

    state->dark_appearance = dark_appearance;
    setenv("PAM_SYSTEM_DARK", state->dark_appearance ? "1" : "0", 1);

    PamNativeBuffer batch{nullptr, 0, 0};
    PamStatus status;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        status = pam_native_engine_relayout_with_metrics(
            state->engine,
            width_dp,
            height_dp,
            text_scale,
            &batch
        );
    }

    if (status != PAM_STATUS_SUCCESS) {
        report_error(state, "Pam Native could not update the viewport.");
        return;
    }
    publish_batch(state, batch);
}

void pam_native_runtime_set_keyboard_inset(
    uint64_t handle,
    float bottom,
    float width_dp,
    float height_dp,
    float text_scale
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || width_dp <= 0 || height_dp <= 0 || text_scale <= 0) {
        return;
    }
    PamNativeBuffer batch{nullptr, 0, 0};
    PamStatus status;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        uint8_t changed = 0;
        status = pam_native_engine_set_keyboard_inset(state->engine, bottom, &changed);
        if (status != PAM_STATUS_SUCCESS || changed == 0) {
            return;
        }
        // Lay the trailing panning KeyboardAvoidingView out above the
        // keyboard in the same runloop turn as the keyboard notification.
        status = pam_native_engine_relayout_with_metrics(
            state->engine,
            width_dp,
            height_dp,
            text_scale,
            &batch
        );
    }
    if (status != PAM_STATUS_SUCCESS) {
        pam_native_buffer_free(batch);
        return;
    }
    publish_batch(state, batch);
}

void pam_native_runtime_set_child_visibility(uint64_t handle, uint64_t owner, uint64_t child, bool visible) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || owner == 0 || child == 0) {
        return;
    }
    PamNativeBuffer batch{nullptr, 0, 0};
    PamStatus status;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        status = pam_native_engine_set_native_child_visibility(
            state->engine, owner, child, visible ? 1 : 0, &batch
        );
    }
    if (status == PAM_STATUS_SUCCESS) {
        publish_batch(state, batch);
    } else {
        pam_native_buffer_free(batch);
    }
}

void pam_native_runtime_set_refresh_rate(uint64_t handle, double refresh_rate_hz) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || refresh_rate_hz <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(state->engine_mutex);
    pam_native_engine_set_refresh_rate(state->engine, refresh_rate_hz);
}

void pam_native_runtime_dispatch_event(
    uint64_t handle,
    int64_t node_id,
    int event_kind,
    const uint8_t* payload,
    size_t payload_size
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }

    std::string bytes;
    if (payload != nullptr && payload_size > 0) {
        if (payload_size > kMaxEventBytes) {
            return;
        }
        bytes.assign(reinterpret_cast<const char*>(payload), payload_size);
    }

    enqueue(
        state,
        Event{
            EventType::Ui,
            node_id,
            static_cast<std::int32_t>(event_kind),
            std::move(bytes),
        },
        event_kind == 2 || event_kind == 9 || event_kind == 16 ||
            event_kind == 17 || event_kind == 18
    );
}

void pam_native_runtime_dispatch_module_result(
    uint64_t handle,
    int64_t request_id,
    int status,
    const uint8_t* payload,
    size_t payload_size
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }

    if (payload == nullptr && payload_size != 0) {
        return;
    }
    if (payload_size > kMaxModuleResultBytes) {
        enqueue(
            state,
            Event{
                EventType::ModuleResult,
                request_id,
                kModuleResultFailure,
                oversized_module_result_message(payload_size),
            },
            false
        );
        return;
    }

    std::string bytes;
    if (payload_size > 0) {
        bytes.assign(reinterpret_cast<const char*>(payload), payload_size);
    }

    enqueue(
        state,
        Event{
            EventType::ModuleResult,
            request_id,
            status,
            std::move(bytes),
        },
        false
    );
}

void pam_native_runtime_remount(uint64_t handle, PamNativeBatchCallback on_remount) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }
    state->on_remount.store(on_remount);
    enqueue(state, Event{EventType::Remount, 0, 0, std::string()}, false);
}

void pam_native_runtime_reload(uint64_t handle, const char* entry) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || entry == nullptr) {
        return;
    }

    enqueue(
        state,
        Event{
            EventType::Reload,
            0,
            0,
            std::string(entry),
        },
        false
    );
}

void pam_native_runtime_stats(uint64_t handle, uint64_t values[PAM_NATIVE_MAX_STAT_VALUES]) {
    RuntimeState* state = from_handle(handle);
    const uint64_t fallback[PAM_NATIVE_MAX_STAT_VALUES] = {0};

    if (state == nullptr) {
        memcpy(values, fallback, sizeof(fallback));
        return;
    }

    PamNativeStats stats{};
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        if (pam_native_engine_stats(state->engine, &stats) != PAM_STATUS_SUCCESS) {
            memcpy(values, fallback, sizeof(fallback));
            return;
        }
    }

    values[0] = stats.commits;
    values[1] = stats.nodes;
    values[2] = stats.created;
    values[3] = stats.removed;
    values[4] = stats.updated;
    values[5] = stats.retained_bytes;
    values[6] = stats.full_commits;
    values[7] = stats.patch_commits;
    values[8] = stats.input_bytes;
    values[9] = stats.output_bytes;
    values[10] = stats.decode_p95_micros;
    values[11] = stats.reconcile_p95_micros;
    values[12] = stats.layout_p95_micros;
    values[13] = stats.encode_p95_micros;
    values[14] = stats.coalesced_commands;
    values[15] = stats.buffer_reuses;
    values[16] = stats.reused_buffer_bytes;
    values[17] = stats.measured_frames;
    values[18] = stats.deadline_misses;
}

void pam_native_runtime_release_batch(uint64_t batch_handle) {
    std::unique_ptr<PublishedBatch> batch{
        reinterpret_cast<PublishedBatch*>(batch_handle)
    };
}

void pam_native_runtime_stop(uint64_t handle) {
    auto* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }

    state->stopping.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(state->queue_mutex);
        state->queue_ready.notify_one();
    }
    if (state->worker.joinable()) {
        state->worker.join();
    }

    std::lock_guard<std::mutex> lock(state->engine_mutex);
    if (state->engine != nullptr) {
        pam_native_engine_free(state->engine);
        state->engine = nullptr;
    }
    delete state;
}
