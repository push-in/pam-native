//! Web TV targets: Samsung Tizen and LG webOS run HTML/JS apps, so these
//! commands compile the PHP app to a static HTML/JS build with
//! `pushinbr/pam-native-web` (no PHP and no server on the TV), then hand it to
//! the vendor tools: Tizen CLI (`tizen`) and webOS CLI (`ares-*`).

use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Deserialize;

/// `web` in pam-native.json. Read by the web compiler; declared here so the
/// manifest keeps rejecting unknown keys.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
#[allow(dead_code)]
pub(crate) struct WebOptions {
    /// Launcher icon PNG, relative to the project (falls back to android.icon).
    #[serde(default)]
    icon: Option<PathBuf>,
    #[serde(default)]
    tv: WebTvOptions,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
#[allow(dead_code)]
struct WebTvOptions {
    /// Keep content inside 5% margins where TVs may crop the picture.
    #[serde(default = "default_true")]
    overscan: bool,
}

impl Default for WebTvOptions {
    fn default() -> Self {
        Self { overscan: true }
    }
}

fn default_true() -> bool {
    true
}

struct Options {
    project: PathBuf,
    out: Option<PathBuf>,
    device: Option<String>,
    sign: Option<String>,
}

pub(crate) fn run(command: &str, arguments: impl Iterator<Item = OsString>) -> Result<u8, String> {
    let (target, action) = command.split_once(':').unwrap_or((command, "build"));
    let options = parse(arguments)?;
    let dist = build(target, &options)?;
    match (target, action) {
        (_, "build") => Ok(0),
        ("tizen", "package") => tizen_package(&options, &dist).map(|_| 0),
        ("webos", "package") => webos_package(&options, &dist).map(|_| 0),
        ("tizen", "run") => {
            let package = tizen_package(&options, &dist)?;
            let id = tizen_application_id(&dist)?;
            let device = options.device.as_deref().map(|device| ["-t", device]);
            tool("tizen", |command| {
                command.args(["install", "-n"]).arg(&package);
                if let Some(device) = device {
                    command.args(device);
                }
            })?;
            tool("tizen", |command| {
                command.args(["run", "-p", &id]);
                if let Some(device) = device {
                    command.args(device);
                }
            })?;
            Ok(0)
        }
        ("webos", "run") => {
            let package = webos_package(&options, &dist)?;
            let id = webos_application_id(&dist)?;
            let device = options.device.as_deref().map(|device| ["--device", device]);
            tool("ares-install", |command| {
                if let Some(device) = device {
                    command.args(device);
                }
                command.arg(&package);
            })?;
            tool("ares-launch", |command| {
                if let Some(device) = device {
                    command.args(device);
                }
                command.arg(&id);
            })?;
            Ok(0)
        }
        _ => Err(format!("unknown web TV command {command:?}")),
    }
}

fn parse(mut arguments: impl Iterator<Item = OsString>) -> Result<Options, String> {
    let mut options = Options {
        project: PathBuf::from("."),
        out: None,
        device: None,
        sign: std::env::var("PAM_TIZEN_SIGN")
            .ok()
            .filter(|value| !value.is_empty()),
    };
    let mut project = None;
    while let Some(argument) = arguments.next() {
        let value = |arguments: &mut dyn Iterator<Item = OsString>| {
            arguments
                .next()
                .ok_or_else(|| format!("{} needs a value", argument.to_string_lossy()))
        };
        match argument.to_string_lossy().as_ref() {
            "--out" => options.out = Some(PathBuf::from(value(&mut arguments)?)),
            "--device" => {
                options.device = Some(value(&mut arguments)?.to_string_lossy().into_owned())
            }
            "--sign" => options.sign = Some(value(&mut arguments)?.to_string_lossy().into_owned()),
            flag if flag.starts_with("--") => return Err(format!("unknown option {flag}")),
            _ if project.is_none() => project = Some(PathBuf::from(&argument)),
            extra => return Err(format!("unexpected argument {extra}")),
        }
    }
    if let Some(project) = project {
        options.project = project;
    }
    Ok(options)
}

/// Compiles the app to `dist/<target>` (or `--out`) and returns that directory.
fn build(target: &str, options: &Options) -> Result<PathBuf, String> {
    let project = fs::canonicalize(&options.project)
        .map_err(|error| format!("cannot open {}: {error}", options.project.display()))?;
    let manifest = fs::read_to_string(project.join("pam-native.json"))
        .map_err(|error| format!("cannot read pam-native.json: {error}"))?;
    if target != "web" && !manifest.contains(&format!("\"{target}\"")) {
        eprintln!(
            "PAM Native warning: \"{target}\" is not in pam-native.json targets; add it with `pam add target {target}`"
        );
    }
    let dist = options
        .out
        .clone()
        .unwrap_or_else(|| project.join("dist").join(target));
    let compiler = compiler(&project)?;
    let status = Command::new(crate::mobile::host_php())
        .arg(&compiler)
        .arg("build")
        .arg(&project)
        .args(["--target", target, "--out"])
        .arg(&dist)
        .status()
        .map_err(|error| format!("cannot start the web compiler: {error}"))?;
    if !status.success() {
        return Err(format!("the web compiler exited with {status}"));
    }
    Ok(dist)
}

/// The project's `vendor/bin/pam-native-web`, else the SDK copy.
fn compiler(project: &Path) -> Result<PathBuf, String> {
    let mut candidates = vec![project.join("vendor/bin/pam-native-web")];
    if let Some(configured) = std::env::var_os("PAM_NATIVE_WEB") {
        candidates.insert(0, PathBuf::from(configured));
    }
    let sdk = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../packages/web");
    if sdk.join("vendor/autoload.php").is_file() {
        candidates.push(sdk.join("bin/pam-native-web"));
    }
    candidates
        .into_iter()
        .find(|candidate| candidate.is_file())
        .ok_or_else(|| {
            "the web compiler is missing; run `pam composer require --dev pushinbr/pam-native-web`"
                .to_owned()
        })
}

fn artifacts(options: &Options) -> Result<PathBuf, String> {
    let directory = options.project.join("artifacts");
    fs::create_dir_all(&directory)
        .map_err(|error| format!("cannot create {}: {error}", directory.display()))?;
    fs::canonicalize(&directory).map_err(|error| error.to_string())
}

/// Signs and zips the build into `artifacts/<name>.wgt` with the Tizen CLI.
/// The signing profile comes from `--sign` or PAM_TIZEN_SIGN (made in the
/// Tizen Certificate Manager or with `tizen certificate` + `tizen security-profiles add`).
fn tizen_package(options: &Options, dist: &Path) -> Result<PathBuf, String> {
    let output = artifacts(options)?;
    let before = wgt_files(&output);
    tool("tizen", |command| {
        command.args(["package", "-t", "wgt"]);
        if let Some(profile) = &options.sign {
            command.args(["-s", profile]);
        }
        command.arg("-o").arg(&output).arg("--").arg(dist);
    })?;
    let package = wgt_files(&output)
        .into_iter()
        .filter(|(path, modified)| before.iter().all(|old| old != &(path.clone(), *modified)))
        .map(|(path, _)| path)
        .next()
        .ok_or("tizen package did not write a .wgt")?;
    println!("Packaged {}", package.display());
    Ok(package)
}

fn wgt_files(directory: &Path) -> Vec<(PathBuf, Option<std::time::SystemTime>)> {
    fs::read_dir(directory)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "wgt"))
        .map(|path| {
            let modified = fs::metadata(&path)
                .and_then(|metadata| metadata.modified())
                .ok();
            (path, modified)
        })
        .collect()
}

/// Packs the build into `artifacts/<id>_<version>_all.ipk` with ares-package.
fn webos_package(options: &Options, dist: &Path) -> Result<PathBuf, String> {
    let output = artifacts(options)?;
    tool("ares-package", |command| {
        command.arg("-o").arg(&output).arg(dist);
    })?;
    let appinfo: serde_json::Value = serde_json::from_str(
        &fs::read_to_string(dist.join("appinfo.json")).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    let package = output.join(format!(
        "{}_{}_all.ipk",
        appinfo["id"].as_str().unwrap_or_default(),
        appinfo["version"].as_str().unwrap_or_default()
    ));
    println!("Packaged {}", package.display());
    Ok(package)
}

fn tizen_application_id(dist: &Path) -> Result<String, String> {
    let config = fs::read_to_string(dist.join("config.xml")).map_err(|error| error.to_string())?;
    config
        .split("<tizen:application id=\"")
        .nth(1)
        .and_then(|rest| rest.split('"').next())
        .map(str::to_owned)
        .ok_or_else(|| "config.xml has no tizen:application id".to_owned())
}

fn webos_application_id(dist: &Path) -> Result<String, String> {
    let appinfo: serde_json::Value = serde_json::from_str(
        &fs::read_to_string(dist.join("appinfo.json")).map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    appinfo["id"]
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| "appinfo.json has no id".to_owned())
}

fn tool(name: &str, configure: impl FnOnce(&mut Command)) -> Result<(), String> {
    let mut command = Command::new(name);
    configure(&mut command);
    let status = command.status().map_err(|error| {
        let install = if name == "tizen" {
            "install the Tizen Studio CLI and put tizen-studio/tools/ide/bin on PATH"
        } else {
            "install the webOS CLI (npm install -g @webos-tools/cli)"
        };
        format!("cannot start {name}: {error}; {install}")
    })?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("{name} exited with {status}"))
    }
}
