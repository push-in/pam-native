//! The adb side of an Android `pam dev` session: one device (`-s <serial>` on
//! every call), one `adb reverse` mapping that belongs to this session, and a
//! teardown that never leaves the device client talking to a removed mapping.
//!
//! adb 37 aborts the whole adb server ("handle_packet disallowed connect to
//! tcp:<port>") when a device opens a reverse connection the server no longer
//! allows. That takes every device off adb, and a phone on wireless debugging
//! does not come back by itself. So a session:
//!
//! - never rebinds a mapping (`--no-rebind`) and never takes over a device port
//!   that another session maps to a different host port;
//! - on exit stops the app first (its dev client is what reconnects), waits for
//!   adbd to drain, and only then removes its own mapping, and only while the
//!   listing still shows exactly that mapping;
//! - never runs `--remove-all`, `disconnect`, `kill-server` or anything else that
//!   touches other sessions or devices.
//!
//! The teardown runs in a small watcher process in its own process group, fed by
//! a pipe from `pam dev`: Ctrl+C, SIGTERM, a crash or SIGKILL all close the pipe,
//! and the watcher then runs the teardown in that order.

use std::io::Write;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::time::{Duration, Instant};

/// What `adb reverse --list` says about this session's device port.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum ReverseState {
    /// The exact mapping `tcp:<device> tcp:<host>` is present.
    Ours,
    /// Nothing maps the device port.
    Absent,
    /// The device port is mapped to another host target (another session).
    Other(String),
}

/// The serial of the device this session uses: `ANDROID_SERIAL`, or the only
/// device `adb devices` lists. Several devices without `ANDROID_SERIAL` is an
/// error, so no adb call ever picks a device by chance.
pub(super) fn resolve_serial() -> Result<String, String> {
    if let Ok(serial) = std::env::var("ANDROID_SERIAL") {
        let serial = serial.trim().to_owned();
        if !serial.is_empty() {
            return Ok(serial);
        }
    }
    let output = Command::new("adb")
        .arg("devices")
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .map_err(|error| format!("cannot start adb: {error}"))?;
    let devices = parse_devices(&String::from_utf8_lossy(&output.stdout));
    match devices.as_slice() {
        [serial] => Ok(serial.clone()),
        [] => Err("no Android device is connected (adb devices lists none)".to_owned()),
        several => Err(format!(
            "{} Android devices are connected ({}); set ANDROID_SERIAL to the one `pam dev` should use",
            several.len(),
            several.join(", ")
        )),
    }
}

/// Serials in the `device` state from `adb devices` output.
pub(super) fn parse_devices(listing: &str) -> Vec<String> {
    listing
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let serial = fields.next()?;
            (fields.next() == Some("device")).then(|| serial.to_owned())
        })
        .collect()
}

/// Classifies `adb -s <serial> reverse --list` output (`<transport> <remote> <local>`).
pub(super) fn reverse_state(listing: &str, device_port: u16, host_port: u16) -> ReverseState {
    let remote = format!("tcp:{device_port}");
    let local = format!("tcp:{host_port}");
    for line in listing.lines() {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if let [_, listed_remote, listed_local] = fields.as_slice()
            && *listed_remote == remote
        {
            return if *listed_local == local {
                ReverseState::Ours
            } else {
                ReverseState::Other((*listed_local).to_owned())
            };
        }
    }
    ReverseState::Absent
}

fn list_reverses(serial: &str) -> Option<String> {
    let output = Command::new("adb")
        .args(["-s", serial, "reverse", "--list"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Arguments that add this session's mapping without replacing any other.
pub(super) fn reverse_add_args(serial: &str, device_port: u16, host_port: u16) -> Vec<String> {
    vec![
        "-s".to_owned(),
        serial.to_owned(),
        "reverse".to_owned(),
        "--no-rebind".to_owned(),
        format!("tcp:{device_port}"),
        format!("tcp:{host_port}"),
    ]
}

/// Maps `tcp:<device>` to `tcp:<host>` on `serial` unless it already is. A
/// device port another session maps elsewhere is refused, never taken over.
pub(super) fn ensure_reverse(serial: &str, device_port: u16, host_port: u16) -> Result<(), String> {
    match list_reverses(serial).map(|listing| reverse_state(&listing, device_port, host_port)) {
        Some(ReverseState::Ours) => return Ok(()),
        Some(ReverseState::Other(target)) => {
            return Err(format!(
                "device port tcp:{device_port} on {serial} is already reversed to {target} by another session; \
                 stop that session first (pam dev never takes over another session's mapping)"
            ));
        }
        Some(ReverseState::Absent) | None => {}
    }
    let status = Command::new("adb")
        .args(reverse_add_args(serial, device_port, host_port))
        .stdin(Stdio::null())
        .status()
        .map_err(|error| format!("cannot start adb: {error}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!(
            "adb -s {serial} reverse tcp:{device_port} tcp:{host_port} exited with status {status}"
        ))
    }
}

/// The teardown, as a POSIX shell script run by the watcher. Arguments: serial,
/// package, device port, host port. Order matters: stop the app (its dev
/// client is what reconnects), let adbd drain, then remove only this mapping.
pub(super) const TEARDOWN_SCRIPT: &str = r#"cat >/dev/null
serial=$1 package=$2 remote=tcp:$3 local=tcp:$4
adb -s "$serial" shell am force-stop "$package" >/dev/null 2>&1
sleep 1
if adb -s "$serial" reverse --list 2>/dev/null | awk -v r="$remote" -v l="$local" '$2 == r && $3 == l { found = 1 } END { exit !found }'; then
  adb -s "$serial" reverse --remove "$remote" >/dev/null 2>&1
fi
"#;

/// Runs [`TEARDOWN_SCRIPT`] when `pam dev` ends, however it ends.
pub(super) struct Teardown {
    watcher: Option<(Child, ChildStdin)>,
}

impl Teardown {
    pub(super) fn arm(serial: &str, package: &str, device_port: u16, host_port: u16) -> Self {
        let mut command = Command::new("sh");
        command
            .args(["-c", TEARDOWN_SCRIPT, "pam-dev-teardown", serial, package])
            .arg(device_port.to_string())
            .arg(host_port.to_string())
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        // Its own process group: Ctrl+C reaches `pam dev`, not the watcher,
        // which must outlive it to run the teardown.
        #[cfg(unix)]
        std::os::unix::process::CommandExt::process_group(&mut command, 0);
        let watcher = command.spawn().ok().and_then(|mut child| {
            let stdin = child.stdin.take()?;
            Some((child, stdin))
        });
        if watcher.is_none() {
            println!(
                "Android sync: cannot start the session teardown watcher; on exit run \
                 `adb -s {serial} shell am force-stop {package}` before \
                 `adb -s {serial} reverse --remove tcp:{device_port}`"
            );
        }
        Self { watcher }
    }
}

impl Drop for Teardown {
    fn drop(&mut self) {
        let Some((mut child, mut stdin)) = self.watcher.take() else {
            return;
        };
        // Closing the pipe starts the teardown; wait for it on a normal exit
        // so the shell prompt comes back with the device already clean.
        let _ = stdin.flush();
        drop(stdin);
        let deadline = Instant::now() + Duration::from_secs(15);
        while Instant::now() < deadline {
            match child.try_wait() {
                Ok(Some(_)) | Err(_) => return,
                Ok(None) => std::thread::sleep(Duration::from_millis(50)),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{ReverseState, TEARDOWN_SCRIPT, parse_devices, reverse_add_args, reverse_state};

    #[test]
    fn only_devices_in_the_device_state_are_candidates() {
        let listing = "List of devices attached\nemulator-5554\tdevice\n\
                       adb-RQ8N-x._adb-tls-connect._tcp\tdevice\nZY22\tunauthorized\nZY23\toffline\n\n";
        assert_eq!(
            parse_devices(listing),
            ["emulator-5554", "adb-RQ8N-x._adb-tls-connect._tcp"]
        );
        assert!(parse_devices("List of devices attached\n\n").is_empty());
    }

    #[test]
    fn a_device_port_mapped_elsewhere_belongs_to_another_session() {
        let listing = "UsbFfs tcp:39100 tcp:39102\nhost-19 tcp:8081 tcp:8081\n";
        assert_eq!(reverse_state(listing, 39_100, 39_102), ReverseState::Ours);
        assert_eq!(
            reverse_state(listing, 39_100, 39_100),
            ReverseState::Other("tcp:39102".to_owned())
        );
        assert_eq!(reverse_state(listing, 39_573, 39_573), ReverseState::Absent);
        assert_eq!(reverse_state("", 39_573, 39_573), ReverseState::Absent);
        // A longer port that merely starts with ours is not ours.
        assert_eq!(
            reverse_state("x tcp:3957 tcp:3957\n", 39_573, 39_573),
            ReverseState::Absent
        );
    }

    #[test]
    fn adding_the_mapping_names_the_device_and_never_rebinds() {
        assert_eq!(
            reverse_add_args("emulator-5554", 39_573, 40_000),
            [
                "-s",
                "emulator-5554",
                "reverse",
                "--no-rebind",
                "tcp:39573",
                "tcp:40000"
            ]
        );
    }

    #[test]
    fn the_teardown_never_touches_other_sessions_or_the_adb_server() {
        for forbidden in [
            "--remove-all",
            "disconnect",
            "reconnect",
            "kill-server",
            "start-server",
            "tcpip",
            " usb",
            "reboot",
            " root",
            "settings",
            "svc ",
        ] {
            assert!(!TEARDOWN_SCRIPT.contains(forbidden), "{forbidden}");
        }
        for line in TEARDOWN_SCRIPT.lines().filter(|line| line.contains("adb ")) {
            assert!(line.contains("adb -s \"$serial\""), "{line}");
        }
    }

    /// Runs the real watcher against a fake `adb` and checks the order of the
    /// calls: nothing before `pam dev` ends, then force-stop, then the removal
    /// of this session's mapping only.
    #[cfg(unix)]
    #[test]
    fn the_watcher_stops_the_app_before_it_removes_its_own_mapping() {
        use std::os::unix::fs::PermissionsExt;

        let root = std::env::temp_dir().join(format!(
            "pam-dev-teardown-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |elapsed| elapsed.as_nanos())
        ));
        std::fs::create_dir_all(&root).expect("temp dir");
        let log = root.join("adb.log");
        let adb = root.join("adb");
        std::fs::write(
            &adb,
            format!(
                "#!/bin/sh\necho \"$*\" >> '{}'\ncase \"$*\" in\n  *'reverse --list'*) \
                 printf 'S tcp:39573 tcp:39573\\nS tcp:39100 tcp:39100\\n' ;;\nesac\n",
                log.display()
            ),
        )
        .expect("fake adb");
        std::fs::set_permissions(&adb, std::fs::Permissions::from_mode(0o755)).expect("chmod");

        let path = format!(
            "{}:{}",
            root.display(),
            std::env::var("PATH").unwrap_or_default()
        );
        let mut child = std::process::Command::new("sh")
            .args([
                "-c",
                TEARDOWN_SCRIPT,
                "t",
                "S",
                "dev.pam.app.debug",
                "39573",
                "39573",
            ])
            .env("PATH", path)
            .stdin(std::process::Stdio::piped())
            .spawn()
            .expect("watcher");
        std::thread::sleep(std::time::Duration::from_millis(300));
        assert!(
            !log.exists(),
            "the watcher must not call adb while pam dev is running"
        );
        drop(child.stdin.take());
        assert!(child.wait().expect("wait").success());

        let calls = std::fs::read_to_string(&log).expect("log");
        let calls: Vec<&str> = calls.lines().collect();
        assert_eq!(
            calls,
            [
                "-s S shell am force-stop dev.pam.app.debug",
                "-s S reverse --list",
                "-s S reverse --remove tcp:39573",
            ]
        );
        std::fs::remove_dir_all(root).expect("cleanup");
    }

    /// A mapping that another session rebound is left alone.
    #[cfg(unix)]
    #[test]
    fn the_watcher_leaves_a_mapping_it_does_not_own() {
        use std::os::unix::fs::PermissionsExt;

        let root = std::env::temp_dir().join(format!(
            "pam-dev-teardown-other-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |elapsed| elapsed.as_nanos())
        ));
        std::fs::create_dir_all(&root).expect("temp dir");
        let log = root.join("adb.log");
        let adb = root.join("adb");
        std::fs::write(
            &adb,
            format!(
                "#!/bin/sh\necho \"$*\" >> '{}'\ncase \"$*\" in\n  *'reverse --list'*) \
                 printf 'S tcp:39573 tcp:40001\\n' ;;\nesac\n",
                log.display()
            ),
        )
        .expect("fake adb");
        std::fs::set_permissions(&adb, std::fs::Permissions::from_mode(0o755)).expect("chmod");
        let path = format!(
            "{}:{}",
            root.display(),
            std::env::var("PATH").unwrap_or_default()
        );
        let status = std::process::Command::new("sh")
            .args([
                "-c",
                TEARDOWN_SCRIPT,
                "t",
                "S",
                "dev.pam.app.debug",
                "39573",
                "39573",
            ])
            .env("PATH", path)
            .stdin(std::process::Stdio::null())
            .status()
            .expect("watcher");
        assert!(status.success());
        let calls = std::fs::read_to_string(&log).expect("log");
        assert!(!calls.contains("--remove"), "{calls}");
        std::fs::remove_dir_all(root).expect("cleanup");
    }
}
