#include <array>
#include <atomic>
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
constexpr std::size_t kMaxQueuedEvents = 1024;

enum class EventType : std::uint8_t {
    Ui = 1,
    ModuleResult = 2,
    Reload = 3,
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

    std::mutex queue_mutex;
    std::condition_variable queue_ready;
    std::deque<Event> events;

    std::mutex engine_mutex;
    std::thread worker;

    std::atomic<bool> stopping = false;
};

thread_local RuntimeState* active_runtime = nullptr;

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
    Z_PARAM_TYPE_INFO(0, frame, IS_STRING, 0)
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

    PamNativeBuffer batch{nullptr, 0};
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
            PamNativeBuffer error_buffer{nullptr, 0};
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
    Z_PARAM_TYPE_INFO(0, request_id, IS_LONG, 0)
    Z_PARAM_TYPE_INFO(0, module, IS_STRING, 0)
    Z_PARAM_TYPE_INFO(0, method, IS_STRING, 0)
    Z_PARAM_TYPE_INFO(0, payload, IS_STRING, 0)
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
    Z_PARAM_TYPE_INFO(0, request_id, IS_LONG, 0)
    Z_PARAM_TYPE_INFO(0, operation, IS_LONG, 0)
    Z_PARAM_TYPE_INFO(0, payload, IS_STRING, 0)
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
    Z_PARAM_TYPE_INFO(0, message, IS_STRING, 0)
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
        arguments,
        nullptr
    );
    zval_ptr_dtor(&callable);
    if (!Z_ISUNDEF(result)) {
        zval_ptr_dtor(&result);
    }

    return status == SUCCESS && !EG(exception);
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
    SG(global_data).ptr = state;
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

    while (!state->stopping.load(std::memory_order_acquire)) {
        Event event;
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
        }

        if (event.type == EventType::Reload) {
            if (!event.payload.empty()) {
                state->entry = event.payload;
            }
            reload = true;
            break;
        }

        dispatch_event(event);

        if (EG(exception)) {
            report_error(state, "Unhandled PHP exception in a Pam Native event.");
            zend_clear_exception();
        }
        gc_collect_cycles();
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
    if (event.payload.size() > kMaxEventBytes) {
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
            return;
        }

        state->events.push_back(std::move(event));
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
    PamNativeRuntimeCallbacks callbacks
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
        callbacks.on_batch == nullptr
        || callbacks.on_call == nullptr
        || callbacks.on_typed_call == nullptr
        || callbacks.on_error == nullptr
    ) {
        return 0;
    }

    auto state = std::make_unique<RuntimeState>();
    state->callbacks = callbacks;
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

    auto handle = reinterpret_cast<uint64_t>(state.get());
    state->worker = std::thread(runtime_loop, state.get());
    state.release();

    return handle;
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

    PamNativeBuffer batch{nullptr, 0};
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
    if (payload_size > kMaxEventBytes) {
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
