#ifndef PAM_NATIVE_IOS_BRIDGE_H
#define PAM_NATIVE_IOS_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PAM_NATIVE_MAX_STAT_VALUES 19

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

/* Engine text measurement on the PHP worker thread (see text_measure.rs).
 * Pointers are valid only during the call; strings are UTF-8. `output`
 * receives width, height, first baseline and line count (points). Returns
 * non-zero when `output` was written. */
typedef int32_t (*PamNativeMeasureTextCallback)(
    uint64_t node_id,
    const uint8_t* text,
    size_t text_length,
    const uint8_t* spans,
    size_t spans_length,
    const uint8_t* font_family,
    size_t font_family_length,
    const uint8_t* font_features,
    size_t font_features_length,
    float font_size,
    float font_scale,
    float letter_spacing,
    float line_height,
    float available_width,
    int32_t font_weight,
    int32_t italic,
    int32_t text_transform,
    uint32_t max_lines,
    float* output
);

/* PHP's pam_native_crypto() (Pam\Native\Crypto; the iOS PHP runtime has no
 * ext-sodium or ext-openssl), called synchronously on the PHP worker thread.
 * operation 1 = Ed25519 verify (key = public key, nonce = signature, input =
 * message, no output); 2 = AES-256-GCM seal (output = ciphertext . 16-byte
 * tag, capacity input_length + 16); 3 = AES-256-GCM open (input = ciphertext
 * . tag, output = plaintext, capacity input_length - 16). Returns 1 and sets
 * `output_length` on success / a valid signature, 0 to reject. Pointers are
 * valid only during the call. */
typedef int32_t (*PamNativeCryptoCallback)(
    int32_t operation,
    const uint8_t* key,
    size_t key_length,
    const uint8_t* nonce,
    size_t nonce_length,
    const uint8_t* aad,
    size_t aad_length,
    const uint8_t* input,
    size_t input_length,
    uint8_t* output,
    size_t output_capacity,
    size_t* output_length
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
    PamNativeBatchCallback on_batch,
    PamNativeCallCallback on_call,
    PamNativeTypedCallCallback on_typed_call,
    PamNativeErrorCallback on_error
);

/* Installs the host text measurer for runtimes started afterwards. */
void pam_native_ios_set_text_measurer(PamNativeMeasureTextCallback callback);

/* Installs the CryptoKit provider behind PHP's pam_native_crypto(). Without
 * one, pam_native_crypto() returns null and Pam\Native\Crypto raises
 * CryptoUnavailableException. */
void pam_native_ios_set_crypto_provider(PamNativeCryptoCallback callback);

/* Window safe-area insets (points) applied to runtimes started afterwards so
 * the first layout already insets SafeAreaView like later relayouts. */
void pam_native_ios_set_boot_safe_area_insets(float left, float top, float right, float bottom);

/* Window safe-area insets (points); the engine lays out every SafeAreaView
 * from the window edges its frame touches. Applied on the next relayout. */
void pam_native_runtime_set_safe_area_insets(
    uint64_t handle,
    float left,
    float top,
    float right,
    float bottom
);

/* Visible keyboard height (points from the bottom of the PAM root view, 0
 * when hidden). Relayouts immediately when it changed. */
void pam_native_runtime_set_keyboard_inset(
    uint64_t handle,
    float bottom,
    float width_dp,
    float height_dp,
    float text_scale
);
/* Visible keyboard height (points from the bottom of the PAM root view, 0
 * when hidden) over the presented Modal whose node id is `surface`. Its
 * resize/padding KeyboardAvoidingViews relayout immediately when it changed. */
void pam_native_runtime_set_surface_keyboard_inset(
    uint64_t handle,
    uint64_t surface,
    float bottom,
    float width_dp,
    float height_dp,
    float text_scale
);

void pam_native_runtime_relayout(
    uint64_t handle,
    float width_dp,
    float height_dp,
    float text_scale,
    bool dark_appearance
);

void pam_native_runtime_set_refresh_rate(
    uint64_t handle,
    double refresh_rate_hz
);

void pam_native_runtime_set_child_visibility(
    uint64_t handle,
    uint64_t owner,
    uint64_t child,
    bool visible
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

/*
 * Asks the runtime worker to publish the retained tree as a full mount for a
 * recreated host view. The batch arrives through `on_remount`, in order with
 * regular batches; `bytes` is NULL (batch handle 0) when nothing rendered yet.
 */
void pam_native_runtime_remount(uint64_t handle, PamNativeBatchCallback on_remount);

void pam_native_runtime_stats(uint64_t handle, uint64_t values[PAM_NATIVE_MAX_STAT_VALUES]);

void pam_native_runtime_release_batch(uint64_t batch_handle);

void pam_native_runtime_stop(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
