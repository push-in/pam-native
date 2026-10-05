#include <jni.h>
#include <sys/stat.h>
#include <android/log.h>

#include <sapi/embed/php_embed.h>
#include <Zend/zend_execute.h>
#include <Zend/zend_exceptions.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include "pam_native_engine.h"

namespace {

constexpr char kLogTag[] = "PamNative";
constexpr std::size_t kMaxEventBytes = 1024 * 1024;
constexpr std::size_t kMaxQueuedEvents = 1024;
// Upper bound for draining the event queue before rendering once.
constexpr auto kFlushBudget = std::chrono::milliseconds(12);
// Cycle collection runs when idle, not after every event.
constexpr int kGcEventInterval = 64;
constexpr auto kGcIdleInterval = std::chrono::seconds(2);

enum class EventType : std::uint8_t {
    Ui = 1,
    ModuleResult = 2,
    Reload = 3,
    // Engine-only work (no PHP): applied on the worker so the UI thread never
    // waits on the engine lock or decodes a batch synchronously.
    ChildVisibility = 4,
};

struct Event {
    EventType type;
    std::int64_t first;
    std::int32_t second;
    std::string payload;
};

struct RuntimeState {
    JavaVM* vm = nullptr;
    jobject runtime = nullptr;
    jmethodID on_batch = nullptr;
    jmethodID on_call = nullptr;
    jmethodID on_typed_call = nullptr;
    jmethodID on_error = nullptr;
    jmethodID on_measure_text = nullptr;
    std::string entry;
    std::string state_dir;
    std::string php_executable = "pam-native";
    std::string php_entry_argument;
    std::array<char*, 3> php_arguments = {nullptr, nullptr, nullptr};
    bool dark_appearance = false;
    PamNativeEngineHandle* engine = nullptr;
    std::mutex engine_mutex;
    std::mutex queue_mutex;
    std::condition_variable queue_ready;
    std::deque<Event> events;
    std::thread worker;
    std::atomic<bool> stopping = false;
    std::mutex stats_mutex;
    std::array<jlong, 19> last_stats{};
};

// Interactive input that must not wait behind module results or scroll/
// progress traffic: press, change, back, long press, submit, toggle, key
// press, modal close requests, menu actions and navigation gesture pops.
bool is_interactive_event(std::int32_t kind) {
    switch (kind) {
        case 1: case 2: case 3: case 5: case 8: case 11:
        case 27: case 31: case 58: case 59: case 66:
            return true;
        default:
            return false;
    }
}

int event_priority(const Event& event) {
    if (event.type == EventType::Ui) {
        return is_interactive_event(event.second) ? 0 : 2;
    }
    return 1;
}

struct PublishedBatch {
    explicit PublishedBatch(PamNativeBuffer value) : buffer(value) {}

    ~PublishedBatch() {
        pam_native_buffer_free(buffer);
    }

    PamNativeBuffer buffer;
};

thread_local RuntimeState* active_runtime = nullptr;

void log_error(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
}

void log_debug(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
}

class AttachedEnvironment {
public:
    explicit AttachedEnvironment(JavaVM* vm) : vm_(vm) {
        if (vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        }
    }

    ~AttachedEnvironment() {
        if (attached_) {
            vm_->DetachCurrentThread();
        }
    }

    JNIEnv* get() const { return env_; }

private:
    JavaVM* vm_;
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

jstring new_ascii_string(JNIEnv* env, const std::uint8_t* data, std::size_t length) {
    std::string value(reinterpret_cast<const char*>(data == nullptr ? reinterpret_cast<const std::uint8_t*>("") : data), data == nullptr ? 0 : length);
    for (char& character : value) {
        if (static_cast<unsigned char>(character) >= 0x80 || character == '\0') {
            character = '?';
        }
    }
    return env->NewStringUTF(value.c_str());
}

// Text boxes are measured by the same Android text stack that draws them,
// like React Native's Yoga measure functions (see text_measure.rs).
std::int32_t measure_text(
    void* context,
    const PamTextMeasureRequest* request,
    PamTextMeasureResult* result
) {
    auto* state = static_cast<RuntimeState*>(context);
    if (state == nullptr || request == nullptr || result == nullptr
        || state->on_measure_text == nullptr || state->runtime == nullptr) {
        return 0;
    }
    AttachedEnvironment attached(state->vm);
    JNIEnv* env = attached.get();
    if (env == nullptr || env->PushLocalFrame(16) != JNI_OK) {
        if (env != nullptr) {
            env->ExceptionClear();
        }
        return 0;
    }
    const auto text_length = static_cast<jsize>(std::min<std::size_t>(request->text_length, 1U << 30));
    jbyteArray text = env->NewByteArray(text_length);
    jbyteArray spans = env->NewByteArray(static_cast<jsize>(request->spans_length));
    jfloatArray output = env->NewFloatArray(4);
    jstring family = new_ascii_string(env, request->font_family, request->font_family_length);
    jstring features = new_ascii_string(env, request->font_features, request->font_features_length);
    jboolean measured = JNI_FALSE;
    if (text != nullptr && spans != nullptr && output != nullptr && family != nullptr && features != nullptr) {
        if (text_length > 0) {
            env->SetByteArrayRegion(text, 0, text_length, reinterpret_cast<const jbyte*>(request->text));
        }
        if (request->spans_length > 0) {
            env->SetByteArrayRegion(spans, 0, static_cast<jsize>(request->spans_length), reinterpret_cast<const jbyte*>(request->spans));
        }
        measured = env->CallBooleanMethod(
            state->runtime,
            state->on_measure_text,
            static_cast<jlong>(request->node_id),
            text,
            spans,
            family,
            features,
            static_cast<jfloat>(request->font_size),
            static_cast<jfloat>(request->font_scale),
            static_cast<jfloat>(request->letter_spacing),
            static_cast<jfloat>(request->line_height),
            static_cast<jfloat>(request->available_width),
            static_cast<jint>(request->font_weight),
            static_cast<jboolean>(request->italic != 0),
            static_cast<jboolean>(request->include_font_padding != 0),
            static_cast<jint>(request->text_transform),
            static_cast<jint>(request->break_strategy),
            static_cast<jint>(request->hyphenation),
            static_cast<jint>(std::min<std::uint32_t>(request->max_lines, 0x7fffffff)),
            output
        );
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        measured = JNI_FALSE;
    }
    if (measured == JNI_TRUE) {
        std::array<jfloat, 4> values{};
        env->GetFloatArrayRegion(output, 0, 4, values.data());
        result->width = values[0];
        result->height = values[1];
        result->first_baseline = values[2];
        result->line_count = static_cast<std::uint32_t>(std::max(0.0F, values[3]));
    }
    env->PopLocalFrame(nullptr);
    return measured == JNI_TRUE ? 1 : 0;
}

void report_error(RuntimeState* state, const std::string& message) {
    log_error(message);
    AttachedEnvironment attached(state->vm);
    JNIEnv* env = attached.get();
    if (env == nullptr) {
        return;
    }
    jstring java_message = env->NewStringUTF(message.c_str());
    if (java_message != nullptr) {
        env->CallVoidMethod(state->runtime, state->on_error, java_message);
        env->DeleteLocalRef(java_message);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void publish_batch(RuntimeState* state, PamNativeBuffer buffer) {
    std::unique_ptr<PublishedBatch> published =
        std::make_unique<PublishedBatch>(buffer);
    AttachedEnvironment attached(state->vm);
    JNIEnv* env = attached.get();
    if (env == nullptr
        || buffer.data == nullptr
        || buffer.length == 0
        || buffer.length > static_cast<std::size_t>(INT32_MAX)) {
        return;
    }
    jobject batch = env->NewDirectByteBuffer(
        buffer.data,
        static_cast<jlong>(buffer.length)
    );
    if (batch == nullptr) {
        return;
    }
    const auto handle =
        static_cast<jlong>(reinterpret_cast<std::uintptr_t>(published.get()));
    const jboolean accepted =
        env->CallBooleanMethod(state->runtime, state->on_batch, batch, handle);
    env->DeleteLocalRef(batch);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    if (accepted == JNI_TRUE) {
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
    AttachedEnvironment attached(state->vm);
    JNIEnv* env = attached.get();
    if (env == nullptr || payload_length > static_cast<std::size_t>(INT32_MAX)) {
        return;
    }
    jstring java_module = env->NewStringUTF(module);
    jstring java_method = env->NewStringUTF(method);
    jbyteArray java_payload = env->NewByteArray(static_cast<jsize>(payload_length));
    if (java_module == nullptr || java_method == nullptr || java_payload == nullptr) {
        if (java_module != nullptr) env->DeleteLocalRef(java_module);
        if (java_method != nullptr) env->DeleteLocalRef(java_method);
        if (java_payload != nullptr) env->DeleteLocalRef(java_payload);
        return;
    }
    if (payload_length > 0) {
        env->SetByteArrayRegion(
            java_payload,
            0,
            static_cast<jsize>(payload_length),
            reinterpret_cast<const jbyte*>(payload)
        );
    }
    env->CallVoidMethod(
        state->runtime,
        state->on_call,
        static_cast<jlong>(request_id),
        java_module,
        java_method,
        java_payload
    );
    env->DeleteLocalRef(java_payload);
    env->DeleteLocalRef(java_method);
    env->DeleteLocalRef(java_module);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void publish_typed_call(
    RuntimeState* state,
    std::int64_t request_id,
    std::int32_t operation,
    const char* payload,
    std::size_t payload_length
) {
    AttachedEnvironment attached(state->vm);
    JNIEnv* env = attached.get();
    if (env == nullptr || payload_length > static_cast<std::size_t>(INT32_MAX)) {
        return;
    }
    jbyteArray java_payload = env->NewByteArray(static_cast<jsize>(payload_length));
    if (java_payload == nullptr) {
        return;
    }
    if (payload_length > 0) {
        env->SetByteArrayRegion(
            java_payload,
            0,
            static_cast<jsize>(payload_length),
            reinterpret_cast<const jbyte*>(payload)
        );
    }
    env->CallVoidMethod(
        state->runtime,
        state->on_typed_call,
        static_cast<jlong>(request_id),
        static_cast<jint>(operation),
        java_payload
    );
    env->DeleteLocalRef(java_payload);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
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
    if (state == nullptr) {
        RETURN_FALSE;
    }
    log_debug("Received a PHP render frame.");

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
                "Pam Native rejected an incremental render frame; "
                "requesting a full-tree recovery. " + detail
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
    publish_call(state, request_id, module, method, payload, payload_length);
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
        request_id,
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
    if (active_runtime != nullptr) {
        report_error(active_runtime, std::string(message, message_length));
    }
}

const zend_function_entry pam_native_functions[] = {
    PHP_FE(pam_native_commit, arginfo_pam_native_commit)
    PHP_FE(pam_native_call, arginfo_pam_native_call)
    PHP_FE(pam_native_call_typed, arginfo_pam_native_call_typed)
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
    int status = call_user_function(
        EG(function_table),
        nullptr,
        &callable,
        &result,
        argument_count,
        arguments
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

void set_ini(HashTable* configuration, const char* name, const std::string& value) {
    zval entry;
    ZVAL_STRINGL(&entry, value.data(), value.size());
    zend_hash_str_update(configuration, name, std::strlen(name), &entry);
}

void apply_php_ini_defaults(HashTable* configuration) {
    // OPcache keeps compiled scripts in shared memory for the life of the
    // process and in a file cache under the app state directory, so a cold
    // start reuses the opcodes compiled by the previous launch instead of
    // recompiling the framework and app sources. JIT stays off (W^X).
    const bool has_state_dir = active_runtime != nullptr && !active_runtime->state_dir.empty();
    set_ini(configuration, "opcache.enable", has_state_dir ? "1" : "0");
    set_ini(configuration, "opcache.enable_cli", has_state_dir ? "1" : "0");
    set_ini(configuration, "opcache.jit", "disable");
    set_ini(configuration, "opcache.jit_buffer_size", "0");
    set_ini(configuration, "opcache.memory_consumption", "48");
    set_ini(configuration, "opcache.interned_strings_buffer", "8");
    set_ini(configuration, "opcache.max_accelerated_files", "8000");
    // Release bundles are content-addressed; hot reload writes new files, so
    // revalidate on every include (a stat) rather than serving stale code.
    set_ini(configuration, "opcache.validate_timestamps", "1");
    set_ini(configuration, "opcache.revalidate_freq", "0");
    set_ini(configuration, "opcache.enable_file_override", "1");
    if (has_state_dir) {
        set_ini(configuration, "opcache.lockfile_path", active_runtime->state_dir);
        set_ini(configuration, "opcache.file_cache", active_runtime->state_dir + "/opcache");
        set_ini(configuration, "opcache.file_cache_consistency_checks", "1");
    }
}

bool initialize_php(RuntimeState* state) {
    log_debug("Initializing embedded PHP.");
    setenv("PAM_NATIVE_STATE_DIR", state->state_dir.c_str(), 1);
    if (!state->state_dir.empty()) {
        const std::string cache = state->state_dir + "/opcache";
        mkdir(cache.c_str(), 0700);
    }
    setenv("PAM_SYSTEM_DARK", state->dark_appearance ? "1" : "0", 1);
    php_embed_module.ini_defaults = apply_php_ini_defaults;
    state->php_entry_argument = state->entry;
    state->php_arguments = {
        state->php_executable.data(),
        state->php_entry_argument.data(),
        nullptr,
    };

    if (php_embed_init(2, state->php_arguments.data()) == FAILURE) {
        report_error(state, "PHP Embed failed to initialize on Android.");
        return false;
    }
    if (!register_php_runtime_api(state)) {
        php_embed_shutdown();
        return false;
    }
    zend_unset_timeout();
    log_debug("Embedded PHP initialized.");
    return true;
}

void apply_child_visibility(RuntimeState* state, const Event& event) {
    if (event.payload.size() != sizeof(jlong)) {
        return;
    }
    jlong child = 0;
    std::memcpy(&child, event.payload.data(), sizeof(jlong));
    PamNativeBuffer batch{nullptr, 0, 0};
    PamStatus status;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        status = pam_native_engine_set_native_child_visibility(
            state->engine, static_cast<uint64_t>(event.first), static_cast<uint64_t>(child),
            event.second != 0 ? 1 : 0, &batch
        );
    }
    if (status == PAM_STATUS_SUCCESS) {
        publish_batch(state, batch);
    } else {
        pam_native_buffer_free(batch);
    }
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

// Older PHP SDKs render synchronously per event; newer ones coalesce
// rendering until the bridge flushes after draining its queue.
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

bool run_php_request(RuntimeState* state) {
    zend_file_handle file_handle;
    zend_stream_init_filename(&file_handle, state->entry.c_str());
    const int status = php_execute_script(&file_handle);
    log_debug("PHP entry execution returned.");
    if (status == FAILURE || EG(exception)) {
        report_error(state, "The Pam Native PHP entry failed during execution.");
        if (EG(exception)) {
            zend_clear_exception();
        }
    }

    const bool deferred = enable_deferred_rendering();
    bool reload = false;
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
        const auto now = std::chrono::steady_clock::now();
        if (!draining) {
            draining = true;
            drain_started = now;
        }
        if (event.type == EventType::ChildVisibility) {
            apply_child_visibility(state, event);
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
            && std::chrono::steady_clock::now() - drain_started < kFlushBudget) {
            continue;
        }
        if (deferred) {
            // One render for everything drained since the last flush.
            call_runtime("flush", 0, nullptr);
            if (EG(exception)) {
                report_error(state, "Unhandled PHP exception while rendering.");
                zend_clear_exception();
            }
        }
        draining = false;
        const auto after = std::chrono::steady_clock::now();
        if (queue_empty
            && (events_since_gc >= kGcEventInterval || after - last_gc >= kGcIdleInterval)) {
            gc_collect_cycles();
            events_since_gc = 0;
            last_gc = after;
        }
    }

    zval result;
    ZVAL_UNDEF(&result);
    call_runtime("shutdown", 0, nullptr);
    return reload;
}

void runtime_loop(RuntimeState* state) {
    active_runtime = state;
    // Attach the worker once so every batch/call/error callback reuses the
    // JNIEnv instead of attaching and detaching per call.
    JNIEnv* worker_env = nullptr;
    const bool worker_attached =
        state->vm->GetEnv(reinterpret_cast<void**>(&worker_env), JNI_VERSION_1_6) != JNI_OK
        && state->vm->AttachCurrentThread(&worker_env, nullptr) == JNI_OK;
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

    {
        AttachedEnvironment attached(state->vm);
        if (attached.get() != nullptr) {
            attached.get()->DeleteGlobalRef(state->runtime);
            state->runtime = nullptr;
        }
    }
    if (worker_attached) {
        state->vm->DetachCurrentThread();
    }
}

void enqueue(RuntimeState* state, Event event, bool coalesce) {
    if (event.payload.size() > kMaxEventBytes || state->stopping.load(std::memory_order_acquire)) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(state->queue_mutex);
        if (coalesce) {
            for (auto iterator = state->events.rbegin(); iterator != state->events.rend(); ++iterator) {
                if (iterator->type == event.type
                    && iterator->first == event.first
                    && iterator->second == event.second) {
                    iterator->payload = std::move(event.payload);
                    state->queue_ready.notify_one();
                    return;
                }
            }
        }
        const int priority = event_priority(event);
        if (state->events.size() >= kMaxQueuedEvents) {
            // Module results complete PHP promises/callbacks exactly once and
            // reload controls the runtime lifecycle; never discard either.
            // Transient UI input is dropped, or evicts the oldest
            // non-interactive UI event when it is interactive itself.
            if (priority == 2) {
                return;
            }
            auto disposable = state->events.begin();
            while (
                disposable != state->events.end()
                && event_priority(*disposable) != 2
            ) {
                ++disposable;
            }
            if (disposable != state->events.end()) {
                state->events.erase(disposable);
            } else if (priority == 0) {
                return;
            }
        }
        // Priority lanes, FIFO within each lane: interactive input, then
        // module results/reload, then other UI traffic (scroll, progress...).
        auto insertion = state->events.begin();
        while (
            insertion != state->events.end()
            && event_priority(*insertion) <= priority
        ) {
            ++insertion;
        }
        state->events.insert(insertion, std::move(event));
    }
    state->queue_ready.notify_one();
}

RuntimeState* from_handle(jlong handle) {
    return reinterpret_cast<RuntimeState*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeStart(
    JNIEnv* env,
    jobject runtime,
    jstring entry,
    jstring state_dir,
    jfloat width,
    jfloat height,
    jfloat text_scale,
    jboolean dark_appearance
) {
    log_debug("Starting the Pam Native worker.");
    if (
        entry == nullptr
        || state_dir == nullptr
        || width <= 0
        || height <= 0
        || text_scale <= 0
    ) {
        return 0;
    }
    const char* entry_chars = env->GetStringUTFChars(entry, nullptr);
    if (entry_chars == nullptr) {
        return 0;
    }
    auto state = std::make_unique<RuntimeState>();
    env->GetJavaVM(&state->vm);
    state->runtime = env->NewGlobalRef(runtime);
    state->entry = entry_chars;
    env->ReleaseStringUTFChars(entry, entry_chars);
    const char* state_dir_chars = env->GetStringUTFChars(state_dir, nullptr);
    if (state_dir_chars == nullptr) {
        if (state->runtime != nullptr) {
            env->DeleteGlobalRef(state->runtime);
        }
        return 0;
    }
    state->state_dir = state_dir_chars;
    state->dark_appearance = dark_appearance == JNI_TRUE;
    env->ReleaseStringUTFChars(state_dir, state_dir_chars);
    jclass runtime_class = env->GetObjectClass(runtime);
    if (runtime_class != nullptr) {
        state->on_batch = env->GetMethodID(
            runtime_class,
            "onNativeBatch",
            "(Ljava/nio/ByteBuffer;J)Z"
        );
    }
    if (state->on_batch != nullptr && !env->ExceptionCheck()) {
        state->on_call = env->GetMethodID(
            runtime_class,
            "onNativeCall",
            "(JLjava/lang/String;Ljava/lang/String;[B)V"
        );
    }
    if (state->on_call != nullptr && !env->ExceptionCheck()) {
        state->on_typed_call = env->GetMethodID(
            runtime_class,
            "onNativeCallTyped",
            "(JI[B)V"
        );
    }
    if (state->on_typed_call != nullptr && !env->ExceptionCheck()) {
        state->on_error = env->GetMethodID(
            runtime_class,
            "onNativeError",
            "(Ljava/lang/String;)V"
        );
    }
    if (state->on_error != nullptr && !env->ExceptionCheck()) {
        state->on_measure_text = env->GetMethodID(
            runtime_class,
            "onMeasureText",
            "(J[B[BLjava/lang/String;Ljava/lang/String;FFFFFIZZIIII[F)Z"
        );
        if (env->ExceptionCheck()) {
            // Older hosts without a measurer keep the portable estimator.
            env->ExceptionClear();
            state->on_measure_text = nullptr;
        }
    }
    if (runtime_class != nullptr) {
        env->DeleteLocalRef(runtime_class);
    }
    if (state->runtime == nullptr
        || state->on_batch == nullptr
        || state->on_call == nullptr
        || state->on_typed_call == nullptr
        || state->on_error == nullptr) {
        if (state->runtime != nullptr) {
            env->DeleteGlobalRef(state->runtime);
        }
        return 0;
    }
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
    if (state->engine == nullptr
        || asset_root_status != PAM_STATUS_SUCCESS
        || pam_native_engine_set_viewport(state->engine, width, height) != PAM_STATUS_SUCCESS
        || pam_native_engine_set_text_scale(state->engine, text_scale)
            != PAM_STATUS_SUCCESS) {
        if (state->engine != nullptr) {
            pam_native_engine_free(state->engine);
        }
        env->DeleteGlobalRef(state->runtime);
        return 0;
    }
    if (state->on_measure_text != nullptr) {
        pam_native_engine_set_text_measurer(state->engine, measure_text, state.get());
    }
    RuntimeState* handle = state.release();
    handle->worker = std::thread(runtime_loop, handle);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeRelayout(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat width,
    jfloat height,
    jfloat text_scale,
    jboolean dark_appearance
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || width <= 0 || height <= 0 || text_scale <= 0) {
        return;
    }
    state->dark_appearance = dark_appearance == JNI_TRUE;
    setenv("PAM_SYSTEM_DARK", state->dark_appearance ? "1" : "0", 1);
    PamNativeBuffer batch{nullptr, 0, 0};
    PamStatus status;
    {
        std::lock_guard<std::mutex> lock(state->engine_mutex);
        status = pam_native_engine_relayout_with_metrics(
            state->engine,
            width,
            height,
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

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeSetChildVisibility(
    JNIEnv*, jobject, jlong handle, jlong owner, jlong child, jboolean visible
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || owner <= 0 || child <= 0) return;
    std::string payload(sizeof(jlong), '\0');
    std::memcpy(payload.data(), &child, sizeof(jlong));
    enqueue(
        state,
        Event{EventType::ChildVisibility, owner, visible == JNI_TRUE ? 1 : 0, std::move(payload)},
        false
    );
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeSetSafeAreaInsets(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat left,
    jfloat top,
    jfloat right,
    jfloat bottom
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(state->engine_mutex);
    pam_native_engine_set_safe_area_insets(state->engine, left, top, right, bottom);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeSetRefreshRate(
    JNIEnv*,
    jobject,
    jlong handle,
    jdouble refresh_rate_hz
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || refresh_rate_hz <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(state->engine_mutex);
    pam_native_engine_set_refresh_rate(state->engine, refresh_rate_hz);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeDispatchEvent(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong node_id,
    jint event_kind,
    jbyteArray payload
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr) {
        return;
    }
    std::string bytes;
    if (payload != nullptr) {
        const jsize length = env->GetArrayLength(payload);
        if (length < 0 || static_cast<std::size_t>(length) > kMaxEventBytes) {
            return;
        }
        bytes.resize(static_cast<std::size_t>(length));
        if (length > 0) {
            env->GetByteArrayRegion(
                payload,
                0,
                length,
                reinterpret_cast<jbyte*>(bytes.data())
            );
        }
    }
    enqueue(
        state,
        Event{EventType::Ui, node_id, event_kind, std::move(bytes)},
        event_kind == 2
            || event_kind == 9
            || event_kind == 16
            || event_kind == 17
            || event_kind == 18
    );
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeDispatchModuleResult(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong request_id,
    jint status,
    jbyteArray payload
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || payload == nullptr) {
        return;
    }
    const jsize length = env->GetArrayLength(payload);
    if (length < 0 || static_cast<std::size_t>(length) > kMaxEventBytes) {
        return;
    }
    std::string bytes(static_cast<std::size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(
            payload,
            0,
            length,
            reinterpret_cast<jbyte*>(bytes.data())
        );
    }
    enqueue(
        state,
        Event{EventType::ModuleResult, request_id, status, std::move(bytes)},
        false
    );
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeReload(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring entry
) {
    RuntimeState* state = from_handle(handle);
    if (state == nullptr || entry == nullptr) {
        return;
    }
    const char* entry_chars = env->GetStringUTFChars(entry, nullptr);
    if (entry_chars != nullptr) {
        std::string next_entry(entry_chars);
        env->ReleaseStringUTFChars(entry, entry_chars);
        enqueue(state, Event{EventType::Reload, 0, 0, std::move(next_entry)}, false);
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeStats(JNIEnv* env, jobject, jlong handle) {
    RuntimeState* state = from_handle(handle);
    jlong values[19] = {};
    if (state != nullptr) {
        PamNativeStats stats{};
        // Never block the UI thread behind a PHP commit: reuse the last
        // snapshot when the engine is busy.
        std::unique_lock<std::mutex> lock(state->engine_mutex, std::try_to_lock);
        if (!lock.owns_lock()) {
            std::lock_guard<std::mutex> cached(state->stats_mutex);
            std::copy(state->last_stats.begin(), state->last_stats.end(), values);
        } else if (pam_native_engine_stats(state->engine, &stats) == PAM_STATUS_SUCCESS) {
            values[0] = static_cast<jlong>(stats.commits);
            values[1] = static_cast<jlong>(stats.nodes);
            values[2] = static_cast<jlong>(stats.created);
            values[3] = static_cast<jlong>(stats.removed);
            values[4] = static_cast<jlong>(stats.updated);
            values[5] = static_cast<jlong>(stats.retained_bytes);
            values[6] = static_cast<jlong>(stats.full_commits);
            values[7] = static_cast<jlong>(stats.patch_commits);
            values[8] = static_cast<jlong>(stats.input_bytes);
            values[9] = static_cast<jlong>(stats.output_bytes);
            values[10] = static_cast<jlong>(stats.decode_p95_micros);
            values[11] = static_cast<jlong>(stats.reconcile_p95_micros);
            values[12] = static_cast<jlong>(stats.layout_p95_micros);
            values[13] = static_cast<jlong>(stats.encode_p95_micros);
            values[14] = static_cast<jlong>(stats.coalesced_commands);
            values[15] = static_cast<jlong>(stats.buffer_reuses);
            values[16] = static_cast<jlong>(stats.reused_buffer_bytes);
            values[17] = static_cast<jlong>(stats.measured_frames);
            values[18] = static_cast<jlong>(stats.deadline_misses);
            std::lock_guard<std::mutex> cached(state->stats_mutex);
            std::copy(values, values + 19, state->last_stats.begin());
        }
    }
    jlongArray result = env->NewLongArray(19);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 19, values);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeReleaseBatch(
    JNIEnv*,
    jobject,
    jlong batch_handle
) {
    std::unique_ptr<PublishedBatch> batch(
        reinterpret_cast<PublishedBatch*>(
            static_cast<std::uintptr_t>(batch_handle)
        )
    );
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pam_nativeapp_PamRuntime_nativeStop(JNIEnv*, jobject, jlong handle) {
    std::unique_ptr<RuntimeState> state(from_handle(handle));
    if (state == nullptr) {
        return;
    }
    state->stopping.store(true, std::memory_order_release);
    state->queue_ready.notify_one();
    if (state->worker.joinable()) {
        state->worker.join();
    }
    pam_native_engine_free(state->engine);
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
