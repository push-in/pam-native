use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use crate::{Engine, EngineStats};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum PamStatus {
    Success = 1,
    InvalidArgument = 2,
    InvalidFrame = 3,
    Panic = 4,
}

#[repr(C)]
pub struct PamNativeBuffer {
    pub data: *mut u8,
    pub length: usize,
}

impl Default for PamNativeBuffer {
    fn default() -> Self {
        Self {
            data: ptr::null_mut(),
            length: 0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct PamNativeStats {
    pub commits: u64,
    pub nodes: u64,
    pub created: u64,
    pub removed: u64,
    pub updated: u64,
    pub retained_bytes: u64,
    pub full_commits: u64,
    pub patch_commits: u64,
    pub input_bytes: u64,
    pub output_bytes: u64,
}

impl From<EngineStats> for PamNativeStats {
    fn from(value: EngineStats) -> Self {
        Self {
            commits: value.commits,
            nodes: value.nodes,
            created: value.created,
            removed: value.removed,
            updated: value.updated,
            retained_bytes: value.retained_bytes,
            full_commits: value.full_commits,
            patch_commits: value.patch_commits,
            input_bytes: value.input_bytes,
            output_bytes: value.output_bytes,
        }
    }
}

#[repr(C)]
pub struct PamNativeEngineHandle {
    engine: Engine,
}

#[unsafe(no_mangle)]
pub extern "C" fn pam_native_engine_new() -> *mut PamNativeEngineHandle {
    Box::into_raw(Box::new(PamNativeEngineHandle {
        engine: Engine::new(),
    }))
}

#[unsafe(no_mangle)]
/// Releases an engine allocated by [`pam_native_engine_new`].
///
/// # Safety
///
/// `handle` must be null or the unique, still-live pointer returned by
/// [`pam_native_engine_new`]. A non-null handle may be released exactly once.
pub unsafe extern "C" fn pam_native_engine_free(handle: *mut PamNativeEngineHandle) {
    if !handle.is_null() {
        // SAFETY: The caller transfers the unique pointer returned by
        // pam_native_engine_new and calls this function at most once.
        drop(unsafe { Box::from_raw(handle) });
    }
}

#[unsafe(no_mangle)]
/// Changes the viewport used by subsequent commits.
///
/// # Safety
///
/// `handle` must point to a live engine and the caller must provide exclusive
/// access to it for the duration of this call.
pub unsafe extern "C" fn pam_native_engine_set_viewport(
    handle: *mut PamNativeEngineHandle,
    width: f32,
    height: f32,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    match catch_unwind(AssertUnwindSafe(|| {
        handle.engine.set_viewport(width, height)
    })) {
        Ok(Ok(())) => PamStatus::Success,
        Ok(Err(_)) => PamStatus::InvalidArgument,
        Err(_) => PamStatus::Panic,
    }
}

#[unsafe(no_mangle)]
/// Changes the text scale used by subsequent commits.
///
/// # Safety
///
/// `handle` must point to a live engine and the caller must provide exclusive
/// access to it for the duration of this call.
pub unsafe extern "C" fn pam_native_engine_set_text_scale(
    handle: *mut PamNativeEngineHandle,
    text_scale: f32,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    match catch_unwind(AssertUnwindSafe(|| {
        handle.engine.set_text_scale(text_scale)
    })) {
        Ok(Ok(())) => PamStatus::Success,
        Ok(Err(_)) => PamStatus::InvalidArgument,
        Err(_) => PamStatus::Panic,
    }
}

#[unsafe(no_mangle)]
/// Changes the viewport and returns layout mutations for the retained tree.
///
/// # Safety
///
/// `handle` must point to a live, exclusively borrowed engine and `output`
/// must point to writable storage for one [`PamNativeBuffer`].
pub unsafe extern "C" fn pam_native_engine_relayout(
    handle: *mut PamNativeEngineHandle,
    width: f32,
    height: f32,
    output: *mut PamNativeBuffer,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    *output = PamNativeBuffer::default();
    match catch_unwind(AssertUnwindSafe(|| handle.engine.relayout(width, height))) {
        Ok(Ok(batch)) => {
            *output = leak_buffer(batch);
            PamStatus::Success
        }
        Ok(Err(_)) => PamStatus::InvalidArgument,
        Err(_) => PamStatus::Panic,
    }
}

#[unsafe(no_mangle)]
/// Changes viewport and text metrics and returns retained-tree layout mutations.
///
/// # Safety
///
/// `handle` must point to a live, exclusively borrowed engine and `output`
/// must point to writable storage for one [`PamNativeBuffer`].
pub unsafe extern "C" fn pam_native_engine_relayout_with_metrics(
    handle: *mut PamNativeEngineHandle,
    width: f32,
    height: f32,
    text_scale: f32,
    output: *mut PamNativeBuffer,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    *output = PamNativeBuffer::default();
    match catch_unwind(AssertUnwindSafe(|| {
        handle
            .engine
            .relayout_with_metrics(width, height, text_scale)
    })) {
        Ok(Ok(batch)) => {
            *output = leak_buffer(batch);
            PamStatus::Success
        }
        Ok(Err(_)) => PamStatus::InvalidArgument,
        Err(_) => PamStatus::Panic,
    }
}

#[unsafe(no_mangle)]
/// Applies an encoded tree or patch and writes an owned mutation buffer.
///
/// # Safety
///
/// `handle` must point to a live, exclusively borrowed engine. `input` must be
/// readable for `input_length` bytes, and `output` must be writable. A
/// successful non-empty output must later be passed exactly once to
/// [`pam_native_buffer_free`].
pub unsafe extern "C" fn pam_native_engine_commit(
    handle: *mut PamNativeEngineHandle,
    input: *const u8,
    input_length: usize,
    output: *mut PamNativeBuffer,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    *output = PamNativeBuffer::default();
    if input.is_null() || input_length == 0 {
        return PamStatus::InvalidArgument;
    }
    // SAFETY: The caller guarantees input points to input_length readable bytes
    // and retains the allocation for this call.
    let input = unsafe { std::slice::from_raw_parts(input, input_length) };
    match catch_unwind(AssertUnwindSafe(|| handle.engine.commit(input))) {
        Ok(Ok(batch)) => {
            *output = leak_buffer(batch);
            PamStatus::Success
        }
        Ok(Err(error)) => {
            eprintln!("Pam Native rejected render frame: {error:?}");
            PamStatus::InvalidFrame
        }
        Err(payload) => {
            eprintln!("Pam Native panicked while committing render frame: {payload:?}");
            PamStatus::Panic
        }
    }
}

#[unsafe(no_mangle)]
/// Copies the current engine statistics into `output`.
///
/// # Safety
///
/// `handle` must point to a live engine for the duration of this call and
/// `output` must point to writable storage for one [`PamNativeStats`].
pub unsafe extern "C" fn pam_native_engine_stats(
    handle: *const PamNativeEngineHandle,
    output: *mut PamNativeStats,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_ref() }) else {
        return PamStatus::InvalidArgument;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    *output = handle.engine.stats().into();
    PamStatus::Success
}

#[unsafe(no_mangle)]
/// Releases a mutation buffer returned by [`pam_native_engine_commit`].
///
/// # Safety
///
/// A non-empty `buffer` must be the exact, unmodified value returned by a
/// successful commit and may be released exactly once.
pub unsafe extern "C" fn pam_native_buffer_free(buffer: PamNativeBuffer) {
    if buffer.data.is_null() || buffer.length == 0 {
        return;
    }
    let slice = ptr::slice_from_raw_parts_mut(buffer.data, buffer.length);
    // SAFETY: pam_native_engine_commit returns this exact boxed slice and
    // transfers unique ownership to the caller.
    drop(unsafe { Box::from_raw(slice) });
}

fn leak_buffer(buffer: Vec<u8>) -> PamNativeBuffer {
    if buffer.is_empty() {
        return PamNativeBuffer::default();
    }
    let boxed = buffer.into_boxed_slice();
    let length = boxed.len();
    let data = Box::into_raw(boxed).cast::<u8>();
    PamNativeBuffer { data, length }
}

#[allow(dead_code)]
fn _opaque_pointer(_: *mut c_void) {}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use pam_native_protocol::{Node, NodeKind, Tree};

    use super::*;

    #[test]
    fn ffi_owns_and_releases_every_output() {
        let frame = Tree {
            root: 1,
            nodes: BTreeMap::from([(
                1,
                Node {
                    id: 1,
                    parent: 0,
                    index: 0,
                    kind: NodeKind::Screen,
                    properties: BTreeMap::new(),
                },
            )]),
        }
        .encode()
        .expect("frame");
        let handle = pam_native_engine_new();
        for _ in 0..20_000 {
            let mut output = PamNativeBuffer::default();
            // SAFETY: All pointers are live for the duration of the call.
            let status = unsafe {
                pam_native_engine_commit(handle, frame.as_ptr(), frame.len(), &mut output)
            };
            assert_eq!(status, PamStatus::Success);
            // SAFETY: The output is returned with unique ownership.
            unsafe { pam_native_buffer_free(output) };
        }
        let mut stats = PamNativeStats::default();
        // SAFETY: Handle and output are valid.
        assert_eq!(
            unsafe { pam_native_engine_stats(handle, &mut stats) },
            PamStatus::Success
        );
        assert_eq!(stats.nodes, 1);
        // SAFETY: Handle is released exactly once.
        unsafe { pam_native_engine_free(handle) };
    }
}
