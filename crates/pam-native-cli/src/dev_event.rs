use std::path::Path;
use std::sync::LazyLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};

pub const PREFIX: &str = "@pam-event ";

static SEQUENCE: AtomicU64 = AtomicU64::new(0);
static SESSION_ID: LazyLock<String> =
    LazyLock::new(|| format!("{}-{}", std::process::id(), unix_milliseconds()));

#[derive(Clone, Copy)]
#[repr(u8)]
pub enum EventCode {
    SessionStarting = 1,
    SessionReady = 2,
    ChangeDetected = 3,
    ReloadStarted = 4,
    ReloadSucceeded = 5,
    ReloadFailed = 6,
    RuntimeExited = 7,
    SessionStopped = 8,
}

#[derive(Clone, Copy)]
#[repr(u8)]
#[allow(
    dead_code,
    reason = "all cross-host surface codes are reserved by schema 1"
)]
pub enum SurfaceCode {
    Server = 1,
    Android = 2,
    Ios = 3,
    Desktop = 4,
}

pub fn emit(event: EventCode, surface: SurfaceCode, project_root: &Path, data: Value) {
    if !enabled() {
        return;
    }
    let sequence = SEQUENCE.fetch_add(1, Ordering::Relaxed) + 1;
    eprintln!(
        "{PREFIX}{}",
        envelope(event, surface, project_root, data, sequence)
    );
}

fn enabled() -> bool {
    std::env::var("PAM_DEV_EVENTS")
        .is_ok_and(|value| matches!(value.to_ascii_lowercase().as_str(), "1" | "json" | "jsonl"))
}

fn envelope(
    event: EventCode,
    surface: SurfaceCode,
    project_root: &Path,
    data: Value,
    sequence: u64,
) -> Value {
    json!({
        "schemaVersion": 1,
        "eventCode": event as u8,
        "surfaceCode": surface as u8,
        "sessionId": SESSION_ID.as_str(),
        "sequence": sequence,
        "occurredAtUnixMs": unix_milliseconds(),
        "projectRoot": project_root,
        "data": data,
    })
}

fn unix_milliseconds() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_uses_versioned_integer_codes() {
        let value = envelope(
            EventCode::ReloadSucceeded,
            SurfaceCode::Desktop,
            Path::new("/tmp/app"),
            json!({"reloadCode": 2}),
            7,
        );
        assert_eq!(value["schemaVersion"], 1);
        assert_eq!(value["eventCode"], 5);
        assert_eq!(value["surfaceCode"], 4);
        assert_eq!(value["sequence"], 7);
        assert_eq!(value["data"]["reloadCode"], 2);
    }

    #[test]
    fn public_codes_are_sequential() {
        assert_eq!(EventCode::SessionStarting as u8, 1);
        assert_eq!(EventCode::SessionStopped as u8, 8);
        assert_eq!(SurfaceCode::Server as u8, 1);
        assert_eq!(SurfaceCode::Desktop as u8, 4);
    }
}
