#ifndef PAM_NATIVE_IOS_BRIDGE_H
#define PAM_NATIVE_IOS_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PAM_NATIVE_MAX_STAT_VALUES 17

typedef bool (*PamNativeBatchCallback)(
    uint64_t runtime_handle,
    const uint8_t* bytes,
    size_t bytes_size,
    uint64_t batch_handle
);

typedef void (*PamNativeCallCallback)(
    uint64_t runtime_handle,
    int64_t request_id,
    const char* module,
    const char* method,
    const uint8_t* payload,
    size_t payload_size
);

typedef void (*PamNativeTypedCallCallback)(
    uint64_t runtime_handle,
    int64_t request_id,
    int32_t operation,
    const uint8_t* payload,
    size_t payload_size
);

typedef void (*PamNativeErrorCallback)(
    uint64_t runtime_handle,
    const char* message
);

typedef struct {
    PamNativeBatchCallback on_batch;
    PamNativeCallCallback on_call;
    PamNativeTypedCallCallback on_typed_call;
    PamNativeErrorCallback on_error;
} PamNativeRuntimeCallbacks;

uint64_t pam_native_runtime_start(
    const char* entry,
    const char* state_directory,
    float width_dp,
    float height_dp,
    float text_scale,
    bool dark_appearance,
    PamNativeRuntimeCallbacks callbacks
);

void pam_native_runtime_relayout(
    uint64_t handle,
    float width_dp,
    float height_dp,
    float text_scale,
    bool dark_appearance
);

void pam_native_runtime_dispatch_event(
    uint64_t handle,
    int64_t node_id,
    int event_kind,
    const uint8_t* payload,
    size_t payload_size
);

void pam_native_runtime_dispatch_module_result(
    uint64_t handle,
    int64_t request_id,
    int status,
    const uint8_t* payload,
    size_t payload_size
);

void pam_native_runtime_reload(uint64_t handle, const char* entry);

void pam_native_runtime_stats(uint64_t handle, uint64_t values[PAM_NATIVE_MAX_STAT_VALUES]);

void pam_native_runtime_release_batch(uint64_t batch_handle);

void pam_native_runtime_stop(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
