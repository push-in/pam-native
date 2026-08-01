#ifndef PAM_NATIVE_ENGINE_H
#define PAM_NATIVE_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#define PAM_NATIVE_PROTOCOL_VERSION 1

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PamNativeEngineHandle PamNativeEngineHandle;

typedef enum PamStatus {
    PAM_STATUS_SUCCESS = 1,
    PAM_STATUS_INVALID_ARGUMENT = 2,
    PAM_STATUS_INVALID_FRAME = 3,
    PAM_STATUS_PANIC = 4,
} PamStatus;

typedef struct PamNativeBuffer {
    uint8_t *data;
    size_t length;
    uint64_t lease;
} PamNativeBuffer;

typedef struct PamNativeStats {
    uint64_t commits;
    uint64_t nodes;
    uint64_t created;
    uint64_t removed;
    uint64_t updated;
    uint64_t retained_bytes;
    uint64_t full_commits;
    uint64_t patch_commits;
    uint64_t input_bytes;
    uint64_t output_bytes;
    uint64_t decode_p95_micros;
    uint64_t reconcile_p95_micros;
    uint64_t layout_p95_micros;
    uint64_t encode_p95_micros;
    uint64_t coalesced_commands;
    uint64_t buffer_reuses;
    uint64_t reused_buffer_bytes;
} PamNativeStats;

PamNativeEngineHandle *pam_native_engine_new(void);
PamStatus pam_native_engine_set_asset_root(
    PamNativeEngineHandle *handle,
    const uint8_t *data,
    size_t length
);
void pam_native_engine_free(PamNativeEngineHandle *handle);
PamStatus pam_native_engine_set_viewport(
    PamNativeEngineHandle *handle,
    float width,
    float height
);
PamStatus pam_native_engine_set_text_scale(
    PamNativeEngineHandle *handle,
    float text_scale
);
PamStatus pam_native_engine_relayout(
    PamNativeEngineHandle *handle,
    float width,
    float height,
    PamNativeBuffer *output
);
PamStatus pam_native_engine_relayout_with_metrics(
    PamNativeEngineHandle *handle,
    float width,
    float height,
    float text_scale,
    PamNativeBuffer *output
);
PamStatus pam_native_engine_commit(
    PamNativeEngineHandle *handle,
    const uint8_t *input,
    size_t input_length,
    PamNativeBuffer *output
);
PamStatus pam_native_engine_last_error(
    const PamNativeEngineHandle *handle,
    PamNativeBuffer *output
);
PamStatus pam_native_engine_stats(
    const PamNativeEngineHandle *handle,
    PamNativeStats *output
);
void pam_native_buffer_free(PamNativeBuffer buffer);

#ifdef __cplusplus
}
#endif

#endif
