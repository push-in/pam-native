//! Content-addressed development sync for `pam dev` and `pam ios:dev`.
//!
//! The development host keeps a manifest of every bundle file (SHA-256, size,
//! path) and serves file contents by digest. A device fetches the manifest
//! (or a delta against the manifest it already applied), asks only for the
//! digests it does not hold, streams them to disk and swaps them into its
//! live development tree. There is no whole-bundle size limit: the first sync
//! streams every missing file, later reloads move only edited files.
//!
//! Protocol (HTTP/1.1 over loopback, `Connection: close`):
//! - `GET /status?version=<v>&wait=1` long-polls up to 20 s for a version
//!   other than `<v>`; the body is the current version (lowercase hex).
//!   With `&delta=1` and a retained `<v>`, a new version answers in the same
//!   round trip: `PNS1 <new> <delta length>\n`, the delta text (see below)
//!   and a `PNO1` object stream with every added or edited file.
//! - `GET /manifest?version=<v>[&base=<b>]` returns `PNM1\n` followed by one
//!   `<sha256> <size> <path>\n` line per file sorted by path. The version is
//!   the SHA-256 of that exact text. When the server still retains `<b>`, the
//!   body is a delta instead: `PNMD1 <b>\n` followed by
//!   `+ <sha256> <size> <path>\n` and `- <path>\n` lines.
//! - `POST /objects` with newline-separated SHA-256 digests streams `PNO1`
//!   and, per digest in request order, `<32-byte digest><u64 LE size><bytes>`.
//! - `POST /reloaded?version=<v>&outcome=<1|2>&ms=<n>&bytes=<n>&files=<n>`
//!   reports the device result (1 succeeded, 2 failed) for the terminal log.

use std::collections::{HashMap, VecDeque};
use std::fmt::Write as _;
use std::fs;
use std::io::{BufWriter, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};

pub(super) const MANIFEST_MAGIC: &str = "PNM1\n";
pub(super) const DELTA_MAGIC: &str = "PNMD1 ";
pub(super) const OBJECTS_MAGIC: &[u8; 4] = b"PNO1";
pub(super) const SYNC_MAGIC: &str = "PNS1 ";
const MAX_REQUEST_HEAD_BYTES: usize = 16 * 1024;
const MAX_REQUEST_BODY_BYTES: usize = 1024 * 1024;
const MAX_REQUESTED_OBJECTS: usize = 10_000;
const MAX_CONNECTIONS: usize = 16;
const LONG_POLL: Duration = Duration::from_secs(20);
const RETAINED_VERSIONS: usize = 8;

/// Where the bytes of a manifest entry live on the development host.
#[derive(Clone, Debug)]
pub(super) enum Source {
    /// A project file, read (and re-verified) when a device asks for it.
    File(PathBuf),
    /// Generated content such as the entry shim.
    Memory(Arc<Vec<u8>>),
}

#[derive(Clone, Debug)]
pub(super) struct Entry {
    pub path: String,
    pub sha256: [u8; 32],
    pub size: u64,
    pub source: Source,
}

impl Entry {
    pub fn memory(path: &str, bytes: Vec<u8>) -> Self {
        Self {
            path: path.to_owned(),
            sha256: Sha256::digest(&bytes).into(),
            size: bytes.len() as u64,
            source: Source::Memory(Arc::new(bytes)),
        }
    }
}

#[derive(Debug)]
pub(super) struct Manifest {
    pub version: String,
    pub text: Vec<u8>,
    pub entries: Vec<Entry>,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub(super) struct Changes {
    pub changed: Vec<String>,
    pub removed: Vec<String>,
    pub bytes: u64,
}

impl Changes {
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.changed.is_empty() && self.removed.is_empty()
    }

    /// One terminal line naming the first few paths.
    pub fn summary(&self) -> String {
        let mut paths = self
            .changed
            .iter()
            .map(String::as_str)
            .chain(self.removed.iter().map(String::as_str))
            .take(3)
            .collect::<Vec<_>>()
            .join(", ");
        let total = self.changed.len() + self.removed.len();
        if total > 3 {
            let _ = write!(paths, " and {} more", total - 3);
        }
        format!(
            "{total} file{} changed ({paths})",
            if total == 1 { "" } else { "s" }
        )
    }
}

impl Manifest {
    pub fn new(mut entries: Vec<Entry>) -> Result<Self, String> {
        entries.sort_by(|left, right| left.path.cmp(&right.path));
        if let Some(pair) = entries.windows(2).find(|pair| pair[0].path == pair[1].path) {
            return Err(format!("duplicate hot reload path {}", pair[0].path));
        }
        if !entries.iter().any(|entry| entry.path == "index.php") {
            return Err("hot reload bundle must contain index.php".to_owned());
        }
        let mut text = String::with_capacity(MANIFEST_MAGIC.len() + entries.len() * 112);
        text.push_str(MANIFEST_MAGIC);
        for entry in &entries {
            push_line(&mut text, "", entry);
        }
        let text = text.into_bytes();
        let version = hex(&Sha256::digest(&text));
        Ok(Self {
            version,
            text,
            entries,
        })
    }

    pub fn short_version(&self) -> &str {
        &self.version[..12]
    }

    /// Files added or edited and files removed relative to `base`.
    pub fn changes_from(&self, base: Option<&Manifest>) -> Changes {
        let mut changes = Changes::default();
        let Some(base) = base else {
            changes.changed = self
                .entries
                .iter()
                .map(|entry| entry.path.clone())
                .collect();
            changes.bytes = self.entries.iter().map(|entry| entry.size).sum();
            return changes;
        };
        merge(&base.entries, &self.entries, |old, new| match (old, new) {
            (Some(old), None) => changes.removed.push(old.path.clone()),
            (old, Some(new)) => {
                if old.is_none_or(|old| old.sha256 != new.sha256) {
                    changes.changed.push(new.path.clone());
                    changes.bytes += new.size;
                }
            }
            (None, None) => {}
        });
        changes
    }

    /// Distinct entries whose contents a device holding `base` lacks.
    pub fn added_from<'a>(&'a self, base: &'a Manifest) -> Vec<&'a Entry> {
        let mut added = Vec::new();
        let mut seen = std::collections::HashSet::new();
        merge(&base.entries, &self.entries, |old, new| {
            if let Some(new) = new
                && old.is_none_or(|old| old.sha256 != new.sha256 || old.size != new.size)
                && seen.insert(new.sha256)
            {
                added.push(new);
            }
        });
        added
    }

    /// The delta body a device holding `base` turns into this manifest.
    pub fn delta_from(&self, base: &Manifest) -> Vec<u8> {
        let mut text = format!("{DELTA_MAGIC}{}\n", base.version);
        merge(&base.entries, &self.entries, |old, new| match (old, new) {
            (Some(old), None) => {
                let _ = writeln!(text, "- {}", old.path);
            }
            (old, Some(new)) => {
                if old.is_none_or(|old| old.sha256 != new.sha256 || old.size != new.size) {
                    push_line(&mut text, "+ ", new);
                }
            }
            (None, None) => {}
        });
        text.into_bytes()
    }
}

fn push_line(text: &mut String, prefix: &str, entry: &Entry) {
    text.push_str(prefix);
    text.push_str(&hex(&entry.sha256));
    let _ = writeln!(text, " {} {}", entry.size, entry.path);
}

/// Walks two path-sorted entry lists in lockstep.
fn merge<'a>(
    old: &'a [Entry],
    new: &'a [Entry],
    mut visit: impl FnMut(Option<&'a Entry>, Option<&'a Entry>),
) {
    let (mut left, mut right) = (0, 0);
    while left < old.len() || right < new.len() {
        match (old.get(left), new.get(right)) {
            (Some(a), Some(b)) if a.path == b.path => {
                visit(Some(a), Some(b));
                left += 1;
                right += 1;
            }
            (Some(a), Some(b)) if a.path < b.path => {
                visit(Some(a), None);
                left += 1;
            }
            (Some(a), None) => {
                visit(Some(a), None);
                left += 1;
            }
            (_, Some(b)) => {
                visit(None, Some(b));
                right += 1;
            }
            (None, None) => break,
        }
    }
}

pub(super) fn hex(bytes: &[u8]) -> String {
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(text, "{byte:02x}");
    }
    text
}

fn parse_digest(text: &str) -> Option<[u8; 32]> {
    if text.len() != 64
        || !text
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return None;
    }
    let mut digest = [0_u8; 32];
    for (index, slot) in digest.iter_mut().enumerate() {
        *slot = u8::from_str_radix(&text[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(digest)
}

pub(super) fn format_bytes(bytes: u64) -> String {
    const KIB: f64 = 1024.0;
    let value = bytes as f64;
    if value < KIB {
        format!("{bytes} B")
    } else if value < KIB * KIB {
        format!("{:.1} KiB", value / KIB)
    } else {
        format!("{:.1} MiB", value / (KIB * KIB))
    }
}

/// Integer outcome codes a device reports through `/reloaded`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub(super) enum ReloadOutcome {
    Succeeded = 1,
    Failed = 2,
}

impl ReloadOutcome {
    fn from_code(code: u64) -> Option<Self> {
        match code {
            1 => Some(Self::Succeeded),
            2 => Some(Self::Failed),
            _ => None,
        }
    }
}

struct State {
    /// Front is the current manifest; older ones serve deltas.
    manifests: VecDeque<Arc<Manifest>>,
    objects: HashMap<[u8; 32], (Source, u64)>,
    published_at: HashMap<String, Instant>,
}

impl State {
    fn current(&self) -> &Arc<Manifest> {
        &self.manifests[0]
    }

    fn manifest(&self, version: &str) -> Option<&Arc<Manifest>> {
        self.manifests
            .iter()
            .find(|manifest| manifest.version == version)
    }

    fn rebuild_objects(&mut self) {
        self.objects.clear();
        for manifest in self.manifests.iter().rev() {
            for entry in &manifest.entries {
                self.objects
                    .insert(entry.sha256, (entry.source.clone(), entry.size));
            }
        }
    }
}

struct Shared {
    state: Mutex<State>,
    changed: Condvar,
    connections: AtomicUsize,
    last_request: Mutex<Instant>,
    label: &'static str,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

/// The development sync server. Each connection runs on its own short-lived
/// thread so a long first sync never blocks status polls.
pub(super) struct DevServer {
    shared: Arc<Shared>,
}

impl DevServer {
    pub fn start(listener: TcpListener, initial: Manifest, label: &'static str) -> Self {
        let mut state = State {
            manifests: VecDeque::from([Arc::new(initial)]),
            objects: HashMap::new(),
            published_at: HashMap::new(),
        };
        state.rebuild_objects();
        let shared = Arc::new(Shared {
            state: Mutex::new(state),
            changed: Condvar::new(),
            connections: AtomicUsize::new(0),
            last_request: Mutex::new(Instant::now()),
            label,
        });
        let accepting = Arc::clone(&shared);
        std::thread::Builder::new()
            .name("pam-dev-sync".to_owned())
            .spawn(move || accept(&listener, &accepting))
            .expect("spawn development sync server");
        Self { shared }
    }

    /// Publishes `next` and wakes long-polling devices. Returns the changes
    /// against the current manifest, or `None` when nothing changed.
    pub fn publish(&self, next: Manifest, detected_at: Instant) -> Option<Changes> {
        let mut state = self.shared.lock();
        if state.current().version == next.version {
            return None;
        }
        let changes = next.changes_from(Some(state.current()));
        // Restoring a file republishes an older version: keep one copy.
        state
            .manifests
            .retain(|manifest| manifest.version != next.version);
        state.published_at.insert(next.version.clone(), detected_at);
        state.manifests.push_front(Arc::new(next));
        while state.manifests.len() > RETAINED_VERSIONS {
            if let Some(dropped) = state.manifests.pop_back() {
                state.published_at.remove(&dropped.version);
            }
        }
        state.rebuild_objects();
        drop(state);
        self.shared.changed.notify_all();
        Some(changes)
    }

    /// Time since a device last talked to this server.
    pub fn idle_for(&self) -> Duration {
        self.shared
            .last_request
            .lock()
            .map(|instant| instant.elapsed())
            .unwrap_or_default()
    }
}

fn accept(listener: &TcpListener, shared: &Arc<Shared>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else {
            std::thread::sleep(Duration::from_millis(50));
            continue;
        };
        if shared.connections.fetch_add(1, Ordering::AcqRel) >= MAX_CONNECTIONS {
            shared.connections.fetch_sub(1, Ordering::AcqRel);
            continue;
        }
        let connection = Arc::clone(shared);
        let spawned = std::thread::Builder::new()
            .name("pam-dev-sync-connection".to_owned())
            .spawn(move || {
                let mut stream = stream;
                let _ = handle(&mut stream, &connection);
                connection.connections.fetch_sub(1, Ordering::AcqRel);
            });
        if spawned.is_err() {
            shared.connections.fetch_sub(1, Ordering::AcqRel);
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub(super) struct Request {
    pub method: String,
    pub path: String,
    pub query: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn parameter(&self, name: &str) -> Option<&str> {
        let mut values = self.query.iter().filter(|(key, _)| key == name);
        let first = values.next()?;
        // Repeated parameters are ambiguous; refuse them.
        if values.next().is_some() {
            return None;
        }
        Some(first.1.as_str())
    }
}

pub(super) fn read_request(stream: &mut impl Read) -> Result<Request, String> {
    let mut head = Vec::with_capacity(1024);
    let mut buffer = [0_u8; 4096];
    let header_end = loop {
        if let Some(position) = head.windows(4).position(|window| window == b"\r\n\r\n") {
            break position + 4;
        }
        if head.len() > MAX_REQUEST_HEAD_BYTES {
            return Err("request head is too large".to_owned());
        }
        let read = stream
            .read(&mut buffer)
            .map_err(|error| error.to_string())?;
        if read == 0 {
            return Err("connection closed before the request head".to_owned());
        }
        head.extend_from_slice(&buffer[..read]);
    };
    let text = std::str::from_utf8(&head[..header_end])
        .map_err(|_| "request head is not UTF-8".to_owned())?;
    let mut lines = text.split("\r\n");
    let mut fields = lines.next().unwrap_or_default().split_ascii_whitespace();
    let method = fields.next().unwrap_or_default().to_owned();
    let target = fields.next().unwrap_or_default();
    let protocol = fields.next().unwrap_or_default();
    if fields.next().is_some() || !matches!(protocol, "HTTP/1.0" | "HTTP/1.1") {
        return Err("malformed request line".to_owned());
    }
    let mut content_length = 0_usize;
    for line in lines {
        if let Some((name, value)) = line.split_once(':')
            && name.trim().eq_ignore_ascii_case("content-length")
        {
            content_length = value
                .trim()
                .parse()
                .map_err(|_| "invalid Content-Length".to_owned())?;
        }
    }
    if content_length > MAX_REQUEST_BODY_BYTES {
        return Err("request body is too large".to_owned());
    }
    let mut body = head[header_end..].to_vec();
    while body.len() < content_length {
        let read = stream
            .read(&mut buffer)
            .map_err(|error| error.to_string())?;
        if read == 0 {
            return Err("connection closed before the request body".to_owned());
        }
        body.extend_from_slice(&buffer[..read]);
    }
    body.truncate(content_length);
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let query = query
        .split('&')
        .filter(|pair| !pair.is_empty())
        .map(|pair| {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            (key.to_owned(), value.to_owned())
        })
        .collect();
    Ok(Request {
        method,
        path: path.to_owned(),
        query,
        body,
    })
}

fn handle(stream: &mut TcpStream, shared: &Shared) -> Result<(), String> {
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|error| error.to_string())?;
    let _ = stream.set_nodelay(true);
    let request = match read_request(stream) {
        Ok(request) => request,
        Err(_) => return empty(stream, "400 Bad Request"),
    };
    if let Ok(mut last) = shared.last_request.lock() {
        *last = Instant::now();
    }
    match (request.method.as_str(), request.path.as_str()) {
        ("GET", "/status") => status(stream, shared, &request),
        ("GET", "/manifest") => manifest(stream, shared, &request),
        ("POST", "/objects") => objects(stream, shared, &request),
        ("POST", "/reloaded") => reloaded(stream, shared, &request),
        (_, "/status" | "/manifest" | "/objects" | "/reloaded") => {
            empty(stream, "405 Method Not Allowed")
        }
        _ => empty(stream, "404 Not Found"),
    }
}

fn status(stream: &mut TcpStream, shared: &Shared, request: &Request) -> Result<(), String> {
    let known = request.parameter("version").unwrap_or_default().to_owned();
    let wait = request.parameter("wait") == Some("1");
    let mut state = shared.lock();
    if wait && state.current().version == known {
        state = shared
            .changed
            .wait_timeout_while(state, LONG_POLL, |state| state.current().version == known)
            .map(|(state, _)| state)
            .unwrap_or_else(|poisoned| poisoned.into_inner().0);
    }
    let current = Arc::clone(state.current());
    let base = (request.parameter("delta") == Some("1") && current.version != known)
        .then(|| state.manifest(&known).cloned())
        .flatten();
    drop(state);
    let Some(base) = base else {
        return respond(stream, "text/plain", current.version.as_bytes());
    };
    // One round trip: version, delta and the edited files.
    let started = Instant::now();
    let delta = current.delta_from(&base);
    let added = current.added_from(&base);
    let header = format!("{SYNC_MAGIC}{} {}\n", current.version, delta.len());
    let length = (header.len() + delta.len() + OBJECTS_MAGIC.len()) as u64
        + added.iter().map(|entry| 40 + entry.size).sum::<u64>();
    let mut writer = BufWriter::with_capacity(256 * 1024, &mut *stream);
    write_head(&mut writer, length)?;
    writer
        .write_all(header.as_bytes())
        .and_then(|()| writer.write_all(&delta))
        .map_err(|error| error.to_string())?;
    let sent = write_objects(
        &mut writer,
        added
            .iter()
            .map(|entry| (entry.sha256, entry.source.clone(), entry.size)),
    )?;
    writer.flush().map_err(|error| error.to_string())?;
    log_sent(shared.label, added.len(), sent, started);
    Ok(())
}

fn write_head(writer: &mut impl Write, length: u64) -> Result<(), String> {
    write!(
        writer,
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {length}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
    )
    .map_err(|error| error.to_string())
}

/// Writes `PNO1` and each object, re-verifying file contents against the
/// published digest. Returns the content bytes written.
fn write_objects(
    writer: &mut impl Write,
    sources: impl Iterator<Item = ([u8; 32], Source, u64)>,
) -> Result<u64, String> {
    writer
        .write_all(OBJECTS_MAGIC)
        .map_err(|error| error.to_string())?;
    let mut sent = 0_u64;
    for (digest, source, size) in sources {
        let bytes = match &source {
            Source::File(path) => Arc::new(
                fs::read(path)
                    .map_err(|error| format!("cannot read {}: {error}", path.display()))?,
            ),
            Source::Memory(bytes) => Arc::clone(bytes),
        };
        // The file changed after the manifest was published: abort; the
        // device retries against the newer version.
        if bytes.len() as u64 != size
            || <[u8; 32]>::from(Sha256::digest(bytes.as_slice())) != digest
        {
            return Err("object changed while it was served".to_owned());
        }
        writer
            .write_all(&digest)
            .map_err(|error| error.to_string())?;
        writer
            .write_all(&size.to_le_bytes())
            .map_err(|error| error.to_string())?;
        writer
            .write_all(&bytes)
            .map_err(|error| error.to_string())?;
        sent += size;
    }
    Ok(sent)
}

fn log_sent(label: &str, files: usize, bytes: u64, started: Instant) {
    if files > 0 {
        println!(
            "{label} sync: sent {files} file{} ({}) in {} ms",
            if files == 1 { "" } else { "s" },
            format_bytes(bytes),
            started.elapsed().as_millis()
        );
    }
}

fn manifest(stream: &mut TcpStream, shared: &Shared, request: &Request) -> Result<(), String> {
    let Some(version) = request.parameter("version") else {
        return empty(stream, "400 Bad Request");
    };
    let state = shared.lock();
    let Some(manifest) = state.manifest(version).cloned() else {
        drop(state);
        return empty(stream, "409 Conflict");
    };
    let base = request
        .parameter("base")
        .and_then(|base| state.manifest(base).cloned());
    drop(state);
    let body = match base {
        Some(base) if base.version != manifest.version => manifest.delta_from(&base),
        _ => manifest.text.clone(),
    };
    respond(stream, "text/plain", &body)
}

fn objects(stream: &mut TcpStream, shared: &Shared, request: &Request) -> Result<(), String> {
    let started = Instant::now();
    let Ok(body) = std::str::from_utf8(&request.body) else {
        return empty(stream, "400 Bad Request");
    };
    let mut digests = Vec::new();
    for line in body.lines().filter(|line| !line.is_empty()) {
        let Some(digest) = parse_digest(line) else {
            return empty(stream, "400 Bad Request");
        };
        digests.push(digest);
        if digests.len() > MAX_REQUESTED_OBJECTS {
            return empty(stream, "400 Bad Request");
        }
    }
    let state = shared.lock();
    let mut sources = Vec::with_capacity(digests.len());
    for digest in &digests {
        let Some((source, size)) = state.objects.get(digest) else {
            drop(state);
            return empty(stream, "409 Conflict");
        };
        sources.push((*digest, source.clone(), *size));
    }
    drop(state);
    let length =
        OBJECTS_MAGIC.len() as u64 + sources.iter().map(|(_, _, size)| 40 + size).sum::<u64>();
    let mut writer = BufWriter::with_capacity(256 * 1024, &mut *stream);
    write_head(&mut writer, length)?;
    let count = sources.len();
    let sent = write_objects(&mut writer, sources.into_iter())?;
    writer.flush().map_err(|error| error.to_string())?;
    log_sent(shared.label, count, sent, started);
    Ok(())
}

fn reloaded(stream: &mut TcpStream, shared: &Shared, request: &Request) -> Result<(), String> {
    let number = |name: &str| {
        request
            .parameter(name)
            .and_then(|value| value.parse::<u64>().ok())
    };
    let (Some(version), Some(outcome), Some(milliseconds)) = (
        request.parameter("version"),
        number("outcome").and_then(ReloadOutcome::from_code),
        number("ms"),
    ) else {
        return empty(stream, "400 Bad Request");
    };
    // Once per published change; a later report (app restart) has no meaningful total.
    let since_change = shared
        .lock()
        .published_at
        .remove(version)
        .map(|instant| instant.elapsed().as_millis());
    let short = &version[..version.len().min(12)];
    let transfer = match (number("files"), number("bytes")) {
        (Some(files), Some(bytes)) if files > 0 => format!(
            ", {files} file{} ({}) applied",
            if files == 1 { "" } else { "s" },
            format_bytes(bytes)
        ),
        _ => String::new(),
    };
    let total = since_change
        .map(|total| format!("; change to screen {total} ms"))
        .unwrap_or_default();
    match outcome {
        ReloadOutcome::Succeeded => println!(
            "{} reload {short}: screen updated in {milliseconds} ms{transfer}{total}",
            shared.label
        ),
        ReloadOutcome::Failed => println!(
            "{} reload {short}: failed after {milliseconds} ms{transfer}; the error overlay is open on the device and clears on the next good save",
            shared.label
        ),
    }
    empty(stream, "204 No Content")
}

fn respond(stream: &mut TcpStream, content_type: &str, body: &[u8]) -> Result<(), String> {
    let headers = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
        body.len()
    );
    stream
        .write_all(headers.as_bytes())
        .and_then(|()| stream.write_all(body))
        .map_err(|error| error.to_string())
}

fn empty(stream: &mut TcpStream, status: &str) -> Result<(), String> {
    stream
        .write_all(
            format!(
                "HTTP/1.1 {status}\r\nContent-Length: 0\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            )
            .as_bytes(),
        )
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};

    fn entry(path: &str, contents: &str) -> Entry {
        Entry::memory(path, contents.as_bytes().to_vec())
    }

    fn manifest(entries: &[(&str, &str)]) -> Manifest {
        Manifest::new(
            entries
                .iter()
                .map(|(path, contents)| entry(path, contents))
                .collect(),
        )
        .expect("manifest")
    }

    #[test]
    fn manifest_version_is_the_digest_of_its_sorted_text() {
        let first = manifest(&[("src/B.php", "b"), ("index.php", "<?php")]);
        let second = manifest(&[("index.php", "<?php"), ("src/B.php", "b")]);
        assert_eq!(first.version, second.version);
        assert_eq!(first.version, hex(&Sha256::digest(&first.text)));
        let text = String::from_utf8(first.text.clone()).expect("text");
        assert!(text.starts_with(MANIFEST_MAGIC));
        assert_eq!(
            text.lines()
                .skip(1)
                .map(|line| line.rsplit(' ').next().unwrap())
                .collect::<Vec<_>>(),
            ["index.php", "src/B.php"]
        );
        assert!(Manifest::new(vec![entry("src/A.php", "a")]).is_err());
        assert!(Manifest::new(vec![entry("index.php", "a"), entry("index.php", "b")]).is_err());
    }

    #[test]
    fn changes_and_deltas_name_only_edited_added_and_removed_files() {
        let base = manifest(&[
            ("index.php", "<?php"),
            ("src/A.php", "a"),
            ("src/Old.php", "o"),
        ]);
        let next = manifest(&[
            ("index.php", "<?php"),
            ("src/A.php", "a2"),
            ("src/New.php", "n"),
        ]);
        let changes = next.changes_from(Some(&base));
        assert_eq!(changes.changed, ["src/A.php", "src/New.php"]);
        assert_eq!(changes.removed, ["src/Old.php"]);
        assert_eq!(changes.bytes, 3);
        assert_eq!(
            changes.summary(),
            "3 files changed (src/A.php, src/New.php, src/Old.php)"
        );
        let delta = String::from_utf8(next.delta_from(&base)).expect("delta");
        let lines = delta.lines().collect::<Vec<_>>();
        assert_eq!(lines[0], format!("PNMD1 {}", base.version));
        assert_eq!(lines.len(), 4);
        assert!(lines[1].starts_with("+ ") && lines[1].ends_with(" 2 src/A.php"));
        assert!(lines[2].starts_with("+ ") && lines[2].ends_with(" 1 src/New.php"));
        assert_eq!(lines[3], "- src/Old.php");
        assert!(next.changes_from(Some(&next)).is_empty());
        assert_eq!(next.changes_from(None).changed.len(), 3);
    }

    #[test]
    fn requests_parse_bodies_and_reject_ambiguous_parameters() {
        let raw = b"POST /objects?version=ab&wait=1 HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello";
        let request = read_request(&mut raw.as_slice()).expect("request");
        assert_eq!(request.method, "POST");
        assert_eq!(request.path, "/objects");
        assert_eq!(request.parameter("version"), Some("ab"));
        assert_eq!(request.body, b"hello");
        let repeated =
            read_request(&mut b"GET /status?version=a&version=b HTTP/1.1\r\n\r\n".as_slice())
                .expect("repeated");
        assert_eq!(repeated.parameter("version"), None);
        assert!(read_request(&mut b"GET /status HTTP/2\r\n\r\n".as_slice()).is_err());
        let oversized = format!(
            "POST /objects HTTP/1.1\r\nContent-Length: {}\r\n\r\n",
            MAX_REQUEST_BODY_BYTES + 1
        );
        assert!(read_request(&mut oversized.as_bytes()).is_err());
    }

    fn call(port: u16, request: &str) -> (String, Vec<u8>) {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect");
        stream.write_all(request.as_bytes()).expect("request");
        let mut reader = BufReader::new(stream);
        let mut status = String::new();
        reader.read_line(&mut status).expect("status");
        let mut length = 0;
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).expect("header");
            if line == "\r\n" {
                break;
            }
            if let Some(value) = line.strip_prefix("Content-Length: ") {
                length = value.trim().parse().expect("length");
            }
        }
        let mut body = vec![0; length];
        reader.read_exact(&mut body).expect("body");
        (status.trim().to_owned(), body)
    }

    #[test]
    fn server_long_polls_serves_deltas_and_streams_only_requested_objects() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind");
        let port = listener.local_addr().expect("address").port();
        let first = manifest(&[("index.php", "<?php"), ("src/A.php", "a")]);
        let first_version = first.version.clone();
        let server = DevServer::start(listener, first, "Test");

        let (status, body) = call(port, "GET /status HTTP/1.1\r\n\r\n");
        assert_eq!(status, "HTTP/1.1 200 OK");
        assert_eq!(body, first_version.as_bytes());

        let waiting = std::thread::spawn(move || {
            call(
                port,
                &format!("GET /status?version={first_version}&wait=1 HTTP/1.1\r\n\r\n"),
            )
        });
        std::thread::sleep(Duration::from_millis(100));
        let second = manifest(&[("index.php", "<?php"), ("src/A.php", "edited")]);
        let second_version = second.version.clone();
        let changes = server.publish(second, Instant::now()).expect("changes");
        assert_eq!(changes.changed, ["src/A.php"]);
        let (_, woken) = waiting.join().expect("long poll");
        assert_eq!(woken, second_version.as_bytes());
        let first_version = server.shared.lock().manifests[1].version.clone();

        // A device holding the previous version gets everything in one round trip.
        let (_, sync) = call(
            port,
            &format!("GET /status?version={first_version}&wait=1&delta=1 HTTP/1.1\r\n\r\n"),
        );
        let header_end = sync
            .iter()
            .position(|byte| *byte == b'\n')
            .expect("sync header")
            + 1;
        let header = std::str::from_utf8(&sync[..header_end]).expect("header");
        let fields = header.trim_end().split(' ').collect::<Vec<_>>();
        assert_eq!(fields[0], "PNS1");
        assert_eq!(fields[1], second_version);
        let delta_length = fields[2].parse::<usize>().expect("delta length");
        let delta =
            std::str::from_utf8(&sync[header_end..header_end + delta_length]).expect("delta");
        assert!(delta.starts_with(&format!("PNMD1 {first_version}\n+ ")));
        let objects = &sync[header_end + delta_length..];
        assert_eq!(&objects[..4], OBJECTS_MAGIC);
        assert_eq!(&objects[44..], b"edited");
        // Unknown bases fall back to the plain version.
        let (_, plain) = call(port, "GET /status?version=unknown&delta=1 HTTP/1.1\r\n\r\n");
        assert_eq!(plain, second_version.as_bytes());

        let (_, delta) = call(
            port,
            &format!(
                "GET /manifest?version={second_version}&base={first_version} HTTP/1.1\r\n\r\n"
            ),
        );
        let delta = String::from_utf8(delta).expect("delta");
        assert!(delta.starts_with(&format!("PNMD1 {first_version}\n+ ")));
        let (_, full) = call(
            port,
            &format!("GET /manifest?version={second_version}&base=unknown HTTP/1.1\r\n\r\n"),
        );
        assert_eq!(hex(&Sha256::digest(&full)), second_version);
        let (stale, _) = call(port, "GET /manifest?version=abc HTTP/1.1\r\n\r\n");
        assert_eq!(stale, "HTTP/1.1 409 Conflict");

        let digest = hex(&Sha256::digest(b"edited"));
        let body = format!("{digest}\n");
        let (status, objects) = call(
            port,
            &format!(
                "POST /objects HTTP/1.1\r\nContent-Length: {}\r\n\r\n{body}",
                body.len()
            ),
        );
        assert_eq!(status, "HTTP/1.1 200 OK");
        assert_eq!(&objects[..4], OBJECTS_MAGIC);
        assert_eq!(hex(&objects[4..36]), digest);
        assert_eq!(u64::from_le_bytes(objects[36..44].try_into().unwrap()), 6);
        assert_eq!(&objects[44..], b"edited");

        let unknown = format!("{}\n", "0".repeat(64));
        let (missing, _) = call(
            port,
            &format!(
                "POST /objects HTTP/1.1\r\nContent-Length: {}\r\n\r\n{unknown}",
                unknown.len()
            ),
        );
        assert_eq!(missing, "HTTP/1.1 409 Conflict");
        let (reported, _) = call(
            port,
            &format!(
                "POST /reloaded?version={second_version}&outcome=1&ms=120&bytes=6&files=1 HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
            ),
        );
        assert_eq!(reported, "HTTP/1.1 204 No Content");
        let (method, _) = call(port, "POST /status HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
        assert_eq!(method, "HTTP/1.1 405 Method Not Allowed");
        assert!(server.idle_for() < Duration::from_secs(5));
    }

    #[test]
    fn served_files_are_reverified_against_the_published_digest() {
        let root = std::env::temp_dir().join(format!(
            "pam-dev-sync-verify-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        fs::create_dir_all(&root).expect("root");
        let file = root.join("A.php");
        fs::write(&file, b"one").expect("file");
        let entry = Entry {
            path: "src/A.php".to_owned(),
            sha256: Sha256::digest(b"one").into(),
            size: 3,
            source: Source::File(file.clone()),
        };
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind");
        let port = listener.local_addr().expect("address").port();
        let _server = DevServer::start(
            listener,
            Manifest::new(vec![
                entry,
                super::Entry::memory("index.php", b"<?php".to_vec()),
            ])
            .expect("manifest"),
            "Test",
        );
        fs::write(&file, b"two").expect("edit after publish");
        let body = format!("{}\n", hex(&Sha256::digest(b"one")));
        let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect");
        stream
            .write_all(
                format!(
                    "POST /objects HTTP/1.1\r\nContent-Length: {}\r\n\r\n{body}",
                    body.len()
                )
                .as_bytes(),
            )
            .expect("request");
        let mut response = Vec::new();
        let _ = stream.read_to_end(&mut response);
        // Headers promise the object, but the stream ends before its bytes.
        let text = String::from_utf8_lossy(&response);
        assert!(text.starts_with("HTTP/1.1 200 OK"));
        assert!(!response.ends_with(b"one"));
        fs::remove_dir_all(root).expect("cleanup");
    }
}
