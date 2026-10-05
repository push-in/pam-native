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
    uint64_t measured_frames;
    uint64_t deadline_misses;
} PamNativeStats;

/* Host text measurement (see crates/pam-native-engine/src/text_measure.rs).
 * Pointers are valid only during the callback; strings are UTF-8. */
typedef struct PamTextMeasureRequest {
    uint64_t node_id;
    const uint8_t *text;
    size_t text_length;
    const uint8_t *spans;
    size_t spans_length;
    const uint8_t *font_family;
    size_t font_family_length;
    const uint8_t *font_features;
    size_t font_features_length;
    float font_size;
    float font_scale;
    float letter_spacing;
    float line_height;
    float available_width; /* non-finite or <= 0: unbounded */
    uint16_t font_weight;
    uint8_t italic;
    uint8_t include_font_padding;
    uint8_t text_transform;
    uint8_t break_strategy;
    uint8_t hyphenation;
    uint8_t reserved;
    uint32_t max_lines; /* 0: unlimited */
} PamTextMeasureRequest;

typedef struct PamTextMeasureResult {
    float width;
    float height;
    float first_baseline;
    uint32_t line_count;
} PamTextMeasureResult;

/* Returns non-zero when result was written. */
typedef int32_t (*PamTextMeasureCallback)(
    void *context,
    const PamTextMeasureRequest *request,
    PamTextMeasureResult *result
);

PamNativeEngineHandle *pam_native_engine_new(void);
/* callback NULL removes the measurer. */
PamStatus pam_native_engine_set_text_measurer(
    PamNativeEngineHandle *handle,
    PamTextMeasureCallback callback,
    void *context
);
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
/* Window safe-area insets in points; enables engine SafeAreaView layout. */
PamStatus pam_native_engine_set_safe_area_insets(
    PamNativeEngineHandle *handle,
    float left,
    float top,
    float right,
    float bottom
);
PamStatus pam_native_engine_set_refresh_rate(
    PamNativeEngineHandle *handle,
    double refresh_rate_hz
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
/* Mounts the retained tree from scratch for a recreated host surface. */
PamStatus pam_native_engine_remount(
    PamNativeEngineHandle *handle,
    PamNativeBuffer *output
);
PamStatus pam_native_engine_commit(
    PamNativeEngineHandle *handle,
    const uint8_t *input,
    size_t input_length,
    PamNativeBuffer *output
);
/* visible is 0 or 1; child must be a direct child of the custom-view owner. */
PamStatus pam_native_engine_set_native_child_visibility(
    PamNativeEngineHandle *handle,
    uint64_t owner,
    uint64_t child,
    uint8_t visible,
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
