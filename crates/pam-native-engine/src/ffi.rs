use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};

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
    pub lease: u64,
}

impl Default for PamNativeBuffer {
    fn default() -> Self {
        Self {
            data: ptr::null_mut(),
            length: 0,
            lease: 0,
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
    pub decode_p95_micros: u64,
    pub reconcile_p95_micros: u64,
    pub layout_p95_micros: u64,
    pub encode_p95_micros: u64,
    pub coalesced_commands: u64,
    pub buffer_reuses: u64,
    pub reused_buffer_bytes: u64,
    pub measured_frames: u64,
    pub deadline_misses: u64,
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
            decode_p95_micros: value.decode_p95_micros,
            reconcile_p95_micros: value.reconcile_p95_micros,
            layout_p95_micros: value.layout_p95_micros,
            encode_p95_micros: value.encode_p95_micros,
            coalesced_commands: value.coalesced_commands,
            buffer_reuses: BUFFER_REUSES.load(Ordering::Relaxed),
            reused_buffer_bytes: REUSED_BUFFER_BYTES.load(Ordering::Relaxed),
            measured_frames: value.measured_frames,
            deadline_misses: value.deadline_misses,
        }
    }
}

#[repr(C)]
pub struct PamNativeEngineHandle {
    engine: Engine,
    last_error: Option<String>,
}

#[unsafe(no_mangle)]
pub extern "C" fn pam_native_engine_new() -> *mut PamNativeEngineHandle {
    Box::into_raw(Box::new(PamNativeEngineHandle {
        engine: Engine::new(),
        last_error: None,
    }))
}

#[unsafe(no_mangle)]
/// Sets the root directory used to resolve `asset://` font families.
///
/// # Safety
///
/// `handle` must point to a live engine. `data` must reference `length` readable
/// bytes containing a valid UTF-8 path for the duration of this call.
pub unsafe extern "C" fn pam_native_engine_set_asset_root(
    handle: *mut PamNativeEngineHandle,
    data: *const u8,
    length: usize,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    if data.is_null() || length == 0 {
        return PamStatus::InvalidArgument;
    }
    // SAFETY: The caller guarantees that `data` references `length` readable
    // bytes for the duration of this call.
    let bytes = unsafe { std::slice::from_raw_parts(data, length) };
    let Ok(path) = std::str::from_utf8(bytes) else {
        return PamStatus::InvalidArgument;
    };
    match catch_unwind(AssertUnwindSafe(|| handle.engine.set_asset_root(path))) {
        Ok(()) => PamStatus::Success,
        Err(_) => PamStatus::Panic,
    }
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
/// Changes the display refresh rate used by the deadline observer.
///
/// # Safety
///
/// `handle` must point to a live engine and the caller must provide exclusive
/// access to it for the duration of this call.
pub unsafe extern "C" fn pam_native_engine_set_refresh_rate(
    handle: *mut PamNativeEngineHandle,
    refresh_rate_hz: f64,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    match catch_unwind(AssertUnwindSafe(|| {
        handle.engine.set_refresh_rate(refresh_rate_hz)
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
    match catch_unwind(AssertUnwindSafe(|| {
        let mut batch = acquire_buffer();
        handle
            .engine
            .relayout_with_metrics_into(width, height, handle.engine.text_scale(), &mut batch)
            .map(|()| batch)
    })) {
        Ok(Ok(batch)) => {
            *output = lease_buffer(batch);
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
        let mut batch = acquire_buffer();
        handle
            .engine
            .relayout_with_metrics_into(width, height, text_scale, &mut batch)
            .map(|()| batch)
    })) {
        Ok(Ok(batch)) => {
            *output = lease_buffer(batch);
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
    match catch_unwind(AssertUnwindSafe(|| {
        let mut batch = acquire_buffer();
        handle.engine.commit_into(input, &mut batch).map(|()| batch)
    })) {
        Ok(Ok(batch)) => {
            handle.last_error = None;
            *output = lease_buffer(batch);
            PamStatus::Success
        }
        Ok(Err(error)) => {
            let detail = format!("{error:?}");
            eprintln!("Pam Native rejected render frame: {detail}");
            handle.last_error = Some(detail);
            PamStatus::InvalidFrame
        }
        Err(payload) => {
            eprintln!("Pam Native panicked while committing render frame: {payload:?}");
            handle.last_error = Some(format!("Panic({payload:?})"));
            PamStatus::Panic
        }
    }
}

#[unsafe(no_mangle)]
/// Copies the most recent commit error into an owned UTF-8 buffer.
///
/// # Safety
///
/// `handle` must point to a live engine and `output` must point to writable
/// storage for one [`PamNativeBuffer`].
pub unsafe extern "C" fn pam_native_engine_last_error(
    handle: *const PamNativeEngineHandle,
    output: *mut PamNativeBuffer,
) -> PamStatus {
    let Some(handle) = (unsafe { handle.as_ref() }) else {
        return PamStatus::InvalidArgument;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return PamStatus::InvalidArgument;
    };
    *output = PamNativeBuffer::default();
    if let Some(error) = &handle.last_error {
        *output = lease_buffer(error.as_bytes().to_vec());
    }
    PamStatus::Success
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
/// Releases an owned buffer returned by the engine C ABI.
///
/// # Safety
///
/// A non-empty `buffer` must be the exact, unmodified value returned by a
/// successful output call and may be released exactly once.
pub unsafe extern "C" fn pam_native_buffer_free(buffer: PamNativeBuffer) {
    if buffer.lease == 0 {
        return;
    }
    // SAFETY: `lease` is the unique Box<Vec<u8>> token created by
    // `lease_buffer`; the caller returns it exactly once and does not modify it.
    let mut bytes = unsafe { Box::from_raw(buffer.lease as *mut Vec<u8>) };
    if bytes.as_mut_ptr() != buffer.data || bytes.len() != buffer.length {
        return;
    }
    bytes.clear();
    recycle_buffer(*bytes);
}

const MAX_POOLED_BUFFERS: usize = 8;
const MAX_POOLED_CAPACITY: usize = 4 * 1024 * 1024;
static BUFFER_REUSES: AtomicU64 = AtomicU64::new(0);
static REUSED_BUFFER_BYTES: AtomicU64 = AtomicU64::new(0);

fn buffer_pool() -> &'static Mutex<Vec<Vec<u8>>> {
    static POOL: OnceLock<Mutex<Vec<Vec<u8>>>> = OnceLock::new();
    POOL.get_or_init(|| Mutex::new(Vec::new()))
}

fn acquire_buffer() -> Vec<u8> {
    let buffer = buffer_pool()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .pop()
        .unwrap_or_default();
    if buffer.capacity() > 0 {
        BUFFER_REUSES.fetch_add(1, Ordering::Relaxed);
        REUSED_BUFFER_BYTES.fetch_add(
            u64::try_from(buffer.capacity()).unwrap_or(u64::MAX),
            Ordering::Relaxed,
        );
    }
    buffer
}

fn recycle_buffer(buffer: Vec<u8>) {
    if buffer.capacity() > MAX_POOLED_CAPACITY {
        return;
    }
    let mut pool = buffer_pool()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if pool.len() < MAX_POOLED_BUFFERS {
        pool.push(buffer);
    }
}

fn lease_buffer(buffer: Vec<u8>) -> PamNativeBuffer {
    if buffer.is_empty() {
        recycle_buffer(buffer);
        return PamNativeBuffer::default();
    }
    let mut leased = Box::new(buffer);
    let data = leased.as_mut_ptr();
    let length = leased.len();
    let lease = Box::into_raw(leased) as u64;
    PamNativeBuffer {
        data,
        length,
        lease,
    }
}

#[allow(dead_code)]
fn _opaque_pointer(_: *mut c_void) {}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use pam_native_protocol::{Node, NodeKind, Tree};

    use super::*;

    #[test]
    fn released_abi_buffers_return_their_allocation_to_the_bounded_pool() {
        let mut bytes = Vec::with_capacity(32_768);
        bytes.extend_from_slice(b"reusable-command-buffer");
        let allocation = bytes.as_ptr();
        let capacity = bytes.capacity();
        let leased = lease_buffer(bytes);
        assert_ne!(leased.lease, 0);
        // SAFETY: The lease came from this module and is returned exactly once.
        unsafe { pam_native_buffer_free(leased) };

        let recycled = acquire_buffer();
        assert_eq!(recycled.as_ptr(), allocation);
        assert_eq!(recycled.capacity(), capacity);
        recycle_buffer(recycled);
    }

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

    #[test]
    fn ffi_exposes_the_last_commit_error() {
        let handle = pam_native_engine_new();
        let invalid = b"NOPE";
        let mut mutations = PamNativeBuffer::default();
        // SAFETY: All pointers are live for the duration of the call.
        assert_eq!(
            unsafe {
                pam_native_engine_commit(handle, invalid.as_ptr(), invalid.len(), &mut mutations)
            },
            PamStatus::InvalidFrame,
        );
        let mut detail = PamNativeBuffer::default();
        // SAFETY: Handle and output are valid.
        assert_eq!(
            unsafe { pam_native_engine_last_error(handle, &mut detail) },
            PamStatus::Success,
        );
        // SAFETY: The function returned a live buffer with `length` bytes.
        let message = unsafe { std::slice::from_raw_parts(detail.data, detail.length) };
        assert!(String::from_utf8_lossy(message).contains("InvalidMagic"));
        // SAFETY: The buffers and handle are released exactly once.
        unsafe {
            pam_native_buffer_free(detail);
            pam_native_engine_free(handle);
        }
    }
}
