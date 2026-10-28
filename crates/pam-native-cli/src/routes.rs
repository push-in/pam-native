//! Typed routes from `routes.php`: runs the PHP route compiler
//! (`vendor/bin/pam-native-routes`) and derives Android app links / iOS
//! associated domains and URL schemes from the route paths, so deep links
//! never drift from the routes the app declares.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Deserialize;

pub const MANIFEST_PATH: &str = ".pam-native/routes.manifest.json";

/// `links` in pam-native.json: where route paths are reachable from.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LinkOptions {
    /// Custom URI schemes (`example` handles `example://chat/5`).
    #[serde(default)]
    pub schemes: Vec<String>,
    /// HTTPS hosts for Android App Links / iOS universal links.
    #[serde(default)]
    pub hosts: Vec<String>,
    /// Android `autoVerify` for the https hosts (default true).
    #[serde(default = "default_true")]
    pub auto_verify: bool,
}

/// `routes` in pam-native.json (all optional).
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RoutesOptions {
    #[serde(default = "default_routes_file")]
    pub file: PathBuf,
    #[serde(default = "default_namespace")]
    pub namespace: String,
    #[serde(default = "default_output")]
    pub output: PathBuf,
}

impl Default for RoutesOptions {
    fn default() -> Self {
        Self {
            file: default_routes_file(),
            namespace: default_namespace(),
            output: default_output(),
        }
    }
}

fn default_true() -> bool {
    true
}

fn default_routes_file() -> PathBuf {
    PathBuf::from("routes.php")
}

fn default_namespace() -> String {
    "App\\Navigation\\Generated".to_owned()
}

fn default_output() -> PathBuf {
    PathBuf::from("src/Navigation/Generated")
}

#[derive(Clone, Debug, Deserialize)]
pub struct RouteManifest {
    pub version: u32,
    pub routes: Vec<RouteEntry>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct RouteEntry {
    pub name: String,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub linkable: bool,
    /// Extra paths from `->alias('/group/{id}')`.
    #[serde(default)]
    pub aliases: Vec<String>,
}

/// One derived link; maps onto `android.deepLinks` entries.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct DerivedLink {
    pub scheme: String,
    pub host: Option<String>,
    pub path_prefix: Option<String>,
    pub auto_verify: bool,
}

/// Static part of a route path that an intent filter can match:
/// `/chat/{id}` -> `/chat/`, `/settings` -> `/settings`, `/` and `/{slug}` -> None.
pub fn path_prefix(path: &str) -> Option<String> {
    let cut = path.find('{').map_or(path, |index| &path[..index]);
    if cut.is_empty() || cut == "/" || !cut.starts_with('/') {
        return None;
    }
    Some(cut.to_owned())
}

/// Android links for the configured schemes and hosts. A custom scheme
/// needs one filter (the router matches the path); each https host gets one
/// filter per distinct route prefix so unrelated web paths stay in the browser.
pub fn derive_links(options: &LinkOptions, manifest: &RouteManifest) -> Vec<DerivedLink> {
    let mut links = BTreeSet::new();
    for scheme in &options.schemes {
        links.insert(DerivedLink {
            scheme: scheme.clone(),
            host: None,
            path_prefix: None,
            auto_verify: false,
        });
    }
    let prefixes: BTreeSet<String> = manifest
        .routes
        .iter()
        .filter(|route| route.linkable)
        .flat_map(|route| route.path.iter().chain(route.aliases.iter()))
        .filter_map(|path| path_prefix(path))
        .collect();
    for host in &options.hosts {
        for prefix in &prefixes {
            links.insert(DerivedLink {
                scheme: "https".to_owned(),
                host: Some(host.clone()),
                path_prefix: Some(prefix.clone()),
                auto_verify: options.auto_verify,
            });
        }
    }
    links.into_iter().collect()
}

/// `applinks:` entries for the configured hosts.
pub fn associated_domains(options: &LinkOptions) -> Vec<String> {
    options
        .hosts
        .iter()
        .map(|host| format!("applinks:{host}"))
        .collect()
}

/// Appends `additions` to `existing`, skipping case-insensitive duplicates.
pub fn merge_unique(existing: &mut Vec<String>, additions: impl IntoIterator<Item = String>) {
    for value in additions {
        if !existing
            .iter()
            .any(|current| current.eq_ignore_ascii_case(&value))
        {
            existing.push(value);
        }
    }
}

pub fn read_manifest(path: &Path) -> Result<RouteManifest, String> {
    let contents = fs::read_to_string(path)
        .map_err(|error| format!("cannot read route manifest {}: {error}", path.display()))?;
    let manifest: RouteManifest = serde_json::from_str(&contents)
        .map_err(|error| format!("invalid route manifest {}: {error}", path.display()))?;
    if manifest.version != 1 {
        return Err(format!(
            "unsupported route manifest version {} in {}",
            manifest.version,
            path.display()
        ));
    }
    Ok(manifest)
}

/// Compiles `routes.php` (typed helpers + manifest + literal navigation
/// checks). Returns None for apps without routes.php. A wrong route name,
/// parameter or literal type fails here, before any native build starts.
pub fn compile(root: &Path, options: &RoutesOptions) -> Result<Option<RouteManifest>, String> {
    let file = root.join(&options.file);
    if !file.is_file() {
        return Ok(None);
    }
    let manifest_path = root.join(MANIFEST_PATH);
    if std::env::var_os("PAM_NATIVE_SKIP_ROUTES").is_some() {
        return if manifest_path.is_file() {
            read_manifest(&manifest_path).map(Some)
        } else {
            Ok(None)
        };
    }
    let compiler = root.join("vendor/bin/pam-native-routes");
    if !compiler.is_file() {
        return Err(format!(
            "{} exists but {} is missing; run `pam composer install`",
            file.display(),
            compiler.display()
        ));
    }
    // Same host PHP as every other build step (PAM_NATIVE_PHP, PAM_PHP,
    // `pam`, then `php`): build hosts with only the PAM toolchain have no
    // `php` on PATH.
    let output = Command::new(crate::mobile::host_php())
        .arg(&compiler)
        .arg(&file)
        .arg(root.join(&options.output))
        .arg(format!("--namespace={}", options.namespace))
        .arg(format!("--manifest={}", manifest_path.display()))
        .current_dir(root)
        .output()
        .map_err(|error| format!("cannot start the route compiler: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "route compilation failed:\n{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        ));
    }
    read_manifest(&manifest_path).map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest() -> RouteManifest {
        serde_json::from_str(
            r#"{"version":1,"routes":[
                {"name":"login","path":"/login","linkable":true,"kind":1},
                {"name":"tabs","path":null,"linkable":false,"kind":4},
                {"name":"chats","path":"/chats","linkable":true},
                {"name":"chat","path":"/chat/{id}","linkable":true,"aliases":["/group/{id}"]},
                {"name":"chat-topic","path":"/chat/{id}/topic/{topic}","linkable":true},
                {"name":"home","path":"/","linkable":true},
                {"name":"secret","path":"/secret","linkable":false}
            ]}"#,
        )
        .unwrap()
    }

    #[test]
    fn prefixes_keep_the_static_part_of_each_path() {
        assert_eq!(path_prefix("/chat/{id}").as_deref(), Some("/chat/"));
        assert_eq!(path_prefix("/settings").as_deref(), Some("/settings"));
        assert_eq!(path_prefix("/u/{name}/posts").as_deref(), Some("/u/"));
        assert_eq!(path_prefix("/"), None);
        assert_eq!(path_prefix("/{slug}"), None);
    }

    #[test]
    fn links_cover_schemes_and_each_route_prefix_once_per_host() {
        let options = LinkOptions {
            schemes: vec!["example".into()],
            hosts: vec!["app.example.com".into()],
            auto_verify: true,
        };
        let links = derive_links(&options, &manifest());
        let custom: Vec<_> = links
            .iter()
            .filter(|link| link.scheme == "example")
            .collect();
        assert_eq!(custom.len(), 1);
        assert!(custom[0].host.is_none() && !custom[0].auto_verify);
        let prefixes: Vec<_> = links
            .iter()
            .filter(|link| link.scheme == "https")
            .map(|link| link.path_prefix.clone().unwrap())
            .collect();
        assert_eq!(prefixes, vec!["/chat/", "/chats", "/group/", "/login"]);
        assert!(
            links
                .iter()
                .filter(|link| link.scheme == "https")
                .all(|link| link.auto_verify && link.host.as_deref() == Some("app.example.com"))
        );
    }

    #[test]
    fn no_links_without_configuration() {
        assert!(derive_links(&LinkOptions::default(), &manifest()).is_empty());
    }

    #[test]
    fn ios_domains_and_scheme_merge_skip_duplicates() {
        let options = LinkOptions {
            schemes: vec!["example".into()],
            hosts: vec!["a.example.com".into(), "b.example.com".into()],
            auto_verify: false,
        };
        assert_eq!(
            associated_domains(&options),
            vec!["applinks:a.example.com", "applinks:b.example.com"]
        );
        let mut schemes = vec!["EXAMPLE".to_owned()];
        merge_unique(&mut schemes, options.schemes.clone());
        assert_eq!(schemes, vec!["EXAMPLE"]);
        merge_unique(&mut schemes, vec!["other".to_owned()]);
        assert_eq!(schemes, vec!["EXAMPLE", "other"]);
    }

    #[test]
    fn manifest_versions_are_checked() {
        let directory = std::env::temp_dir().join(format!("pam-routes-{}", std::process::id()));
        fs::create_dir_all(&directory).unwrap();
        let path = directory.join("routes.manifest.json");
        fs::write(&path, r#"{"version":2,"routes":[]}"#).unwrap();
        assert!(read_manifest(&path).unwrap_err().contains("unsupported"));
        fs::write(
            &path,
            r#"{"version":1,"routes":[{"name":"a","path":"/a","linkable":true}]}"#,
        )
        .unwrap();
        assert_eq!(read_manifest(&path).unwrap().routes.len(), 1);
        fs::remove_dir_all(&directory).unwrap();
    }

    #[test]
    fn apps_without_routes_php_skip_compilation() {
        let directory =
            std::env::temp_dir().join(format!("pam-routes-none-{}", std::process::id()));
        fs::create_dir_all(&directory).unwrap();
        assert!(
            compile(&directory, &RoutesOptions::default())
                .unwrap()
                .is_none()
        );
        fs::write(directory.join("routes.php"), "<?php\n").unwrap();
        assert!(
            compile(&directory, &RoutesOptions::default())
                .unwrap_err()
                .contains("pam-native-routes is missing")
        );
        fs::remove_dir_all(&directory).unwrap();
    }
}
