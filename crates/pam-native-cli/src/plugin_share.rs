//! Content a plugin receives from other applications' share sheets.
//!
//! A plugin declares `share` in `pam-native.plugin.json` with a `configKey`
//! and default MIME types. The application narrows or widens them under
//! `plugins.<configKey>` in `pam-native.json`. At prepare time the resolved
//! list becomes Android `SEND`/`SEND_MULTIPLE` intent filters and the
//! `NSExtensionActivationRule` of the plugin's iOS Share Extension.

use serde::Deserialize;

use super::valid_mime_type;

const ANY: &str = "*/*";
const MAX_TYPES: usize = 16;
const TEXT_URL_MAX: u32 = 8;
const FILE_MAX: u32 = 32;
const IMAGE_MAX: u32 = 32;
const MOVIE_MAX: u32 = 8;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct PluginShare {
    pub(super) config_key: String,
    #[serde(default = "default_accept")]
    accept: Vec<String>,
    #[serde(default)]
    multiple: ShareMultiple,
}

/// `true` (every accepted type), `false` (single items only) or the accepted
/// MIME types that may arrive several at once.
#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(untagged)]
enum ShareMultiple {
    All(bool),
    Types(Vec<String>),
}

impl Default for ShareMultiple {
    fn default() -> Self {
        Self::All(true)
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ShareConfig {
    #[serde(default)]
    accept: Option<Vec<String>>,
    #[serde(default)]
    multiple: Option<ShareMultiple>,
}

fn default_accept() -> Vec<String> {
    vec![ANY.to_owned()]
}

/// One accepted MIME type and whether several items of it may arrive at once.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct ShareType {
    pub(super) mime: String,
    pub(super) multiple: bool,
}

pub(super) fn valid_config_key(value: &str) -> bool {
    let mut characters = value.chars();
    characters
        .next()
        .is_some_and(|character| character.is_ascii_lowercase())
        && characters.all(|character| character.is_ascii_alphanumeric())
        && value.len() <= 64
}

/// Resolves the plugin defaults against the application's configuration.
pub(super) fn resolve(
    package: &str,
    share: &PluginShare,
    config: Option<&serde_json::Value>,
) -> Result<Vec<ShareType>, String> {
    let label = format!("plugins.{}", share.config_key);
    let config = config
        .map(|value| {
            serde_json::from_value::<ShareConfig>(value.clone()).map_err(|error| {
                format!("invalid pam-native.json {label} for plugin {package}: {error}")
            })
        })
        .transpose()?;
    let (accept, multiple) = match config {
        Some(config) => (
            config.accept.unwrap_or_else(|| share.accept.clone()),
            config.multiple.unwrap_or_else(|| share.multiple.clone()),
        ),
        None => (share.accept.clone(), share.multiple.clone()),
    };
    if accept.is_empty() || accept.len() > MAX_TYPES {
        return Err(format!(
            "{label} accept for plugin {package} must list 1 to {MAX_TYPES} MIME types"
        ));
    }
    let mut types: Vec<ShareType> = Vec::with_capacity(accept.len());
    for mime in accept {
        if !valid_mime_type(&mime) || mime != mime.to_ascii_lowercase() {
            return Err(format!(
                "{label} accept for plugin {package} has invalid MIME type {mime:?}; use lowercase type/subtype"
            ));
        }
        if types.iter().any(|existing| existing.mime == mime) {
            return Err(format!(
                "{label} accept for plugin {package} lists {mime:?} twice"
            ));
        }
        types.push(ShareType {
            mime,
            multiple: false,
        });
    }
    match multiple {
        ShareMultiple::All(all) => {
            for item in &mut types {
                item.multiple = all;
            }
        }
        ShareMultiple::Types(multiple) => {
            for mime in multiple {
                let item = types
                    .iter_mut()
                    .find(|item| item.mime == mime)
                    .ok_or_else(|| {
                        format!(
                            "{label} multiple for plugin {package} lists {mime:?}, which is not in accept"
                        )
                    })?;
                item.multiple = true;
            }
        }
    }
    Ok(types)
}

/// Merges share types, keeping the first-seen order and widening `multiple`.
pub(super) fn merge(lists: impl IntoIterator<Item = ShareType>) -> Vec<ShareType> {
    let mut merged: Vec<ShareType> = Vec::new();
    for item in lists {
        match merged
            .iter_mut()
            .find(|existing| existing.mime == item.mime)
        {
            Some(existing) => existing.multiple |= item.multiple,
            None => merged.push(item),
        }
    }
    merged
}

/// Android intent filters for the launcher activity, one per MIME type.
pub(super) fn android_intent_filters(types: &[ShareType], indent: &str) -> String {
    let mut filters = String::new();
    for item in types {
        filters.push_str(indent);
        filters.push_str("<intent-filter>\n");
        filters.push_str(indent);
        filters.push_str("    <action android:name=\"android.intent.action.SEND\" />\n");
        if item.multiple {
            filters.push_str(indent);
            filters
                .push_str("    <action android:name=\"android.intent.action.SEND_MULTIPLE\" />\n");
        }
        filters.push_str(indent);
        filters.push_str("    <category android:name=\"android.intent.category.DEFAULT\" />\n");
        filters.push_str(indent);
        filters.push_str("    <data android:mimeType=\"");
        filters.push_str(&item.mime);
        filters.push_str("\" />\n");
        filters.push_str(indent);
        filters.push_str("</intent-filter>\n");
    }
    filters
}

enum IosShareClass {
    Any,
    Text { uti: &'static str },
    Image,
    Movie,
    Specific { uti: &'static str },
}

fn ios_class(mime: &str) -> Option<IosShareClass> {
    Some(match mime {
        ANY => IosShareClass::Any,
        "text/plain" => IosShareClass::Text {
            uti: "public.plain-text",
        },
        "text/*" => IosShareClass::Text { uti: "public.text" },
        "image/*" => IosShareClass::Image,
        "video/*" => IosShareClass::Movie,
        _ => IosShareClass::Specific {
            uti: ios_type_identifier(mime)?,
        },
    })
}

/// Uniform Type Identifier for a MIME type that the activation dictionary
/// cannot express on its own.
fn ios_type_identifier(mime: &str) -> Option<&'static str> {
    Some(match mime {
        "application/*" | "application/octet-stream" => "public.data",
        "audio/*" => "public.audio",
        "font/*" => "public.font",
        "application/pdf" => "com.adobe.pdf",
        "application/json" => "public.json",
        "application/zip" => "public.zip-archive",
        "application/xml" | "text/xml" => "public.xml",
        "application/rtf" | "text/rtf" => "public.rtf",
        "application/msword" => "com.microsoft.word.doc",
        "application/vnd.ms-excel" => "com.microsoft.excel.xls",
        "application/vnd.ms-powerpoint" => "com.microsoft.powerpoint.ppt",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => {
            "org.openxmlformats.wordprocessingml.document"
        }
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => {
            "org.openxmlformats.spreadsheetml.sheet"
        }
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" => {
            "org.openxmlformats.presentationml.presentation"
        }
        "text/html" => "public.html",
        "text/csv" => "public.comma-separated-values-text",
        "text/vcard" | "text/x-vcard" => "public.vcard",
        "text/calendar" => "com.apple.ical.ics",
        "text/markdown" => "net.daringfireball.markdown",
        "image/jpeg" => "public.jpeg",
        "image/png" => "public.png",
        "image/gif" => "com.compuserve.gif",
        "image/heic" => "public.heic",
        "image/heif" => "public.heif",
        "image/webp" => "org.webmproject.webp",
        "image/tiff" => "public.tiff",
        "image/bmp" => "com.microsoft.bmp",
        "image/svg+xml" => "public.svg-image",
        "video/mp4" => "public.mpeg-4",
        "video/quicktime" => "com.apple.quicktime-movie",
        "video/3gpp" => "public.3gpp",
        "video/mpeg" => "public.mpeg",
        "video/x-m4v" => "com.apple.m4v-video",
        "audio/mpeg" => "public.mp3",
        "audio/mp4" | "audio/m4a" | "audio/x-m4a" => "public.mpeg-4-audio",
        "audio/aac" => "public.aac-audio",
        "audio/wav" | "audio/x-wav" => "com.microsoft.waveform-audio",
        "audio/aiff" | "audio/x-aiff" => "public.aiff-audio",
        _ => return None,
    })
}

/// `NSExtensionActivationRule` for a Share Extension accepting `types`.
///
/// Wildcards, `text/plain` (with web URLs, which Android delivers as
/// `text/plain`), `image/*` and `video/*` use the activation dictionary.
/// Specific file types use a `SUBQUERY` predicate over their type
/// identifiers, unless `*/*` already accepts everything.
pub(super) fn ios_activation_rule(types: &[ShareType]) -> Result<serde_json::Value, String> {
    let accepts_any = types.iter().any(|item| item.mime == ANY);
    let mut classes = Vec::with_capacity(types.len());
    for item in types {
        match ios_class(&item.mime) {
            Some(class) => classes.push((class, item.multiple)),
            // `*/*` already accepts a type iOS cannot name.
            None if accepts_any => {}
            None => {
                return Err(format!(
                    "share MIME type {:?} has no known iOS type identifier; use a wildcard such as \"application/*\"",
                    item.mime
                ));
            }
        }
    }
    let has_specific = classes
        .iter()
        .any(|(class, _)| matches!(class, IosShareClass::Specific { .. }));
    if has_specific && !accepts_any {
        return Ok(serde_json::Value::String(ios_activation_predicate(
            &classes,
        )));
    }

    let mut rule = serde_json::Map::new();
    let mut raise = |key: &str, count: u32| {
        let current = rule
            .get(key)
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        if u64::from(count) > current {
            rule.insert(key.to_owned(), serde_json::Value::from(count));
        }
    };
    let count = |multiple: bool, maximum: u32| if multiple { maximum } else { 1 };
    let mut text = false;
    for (class, multiple) in &classes {
        match class {
            IosShareClass::Any => {
                text = true;
                raise(
                    "NSExtensionActivationSupportsWebURLWithMaxCount",
                    count(*multiple, TEXT_URL_MAX),
                );
                raise(
                    "NSExtensionActivationSupportsFileWithMaxCount",
                    count(*multiple, FILE_MAX),
                );
                raise(
                    "NSExtensionActivationSupportsImageWithMaxCount",
                    count(*multiple, IMAGE_MAX),
                );
                raise(
                    "NSExtensionActivationSupportsMovieWithMaxCount",
                    count(*multiple, MOVIE_MAX),
                );
            }
            IosShareClass::Text { .. } => {
                text = true;
                raise(
                    "NSExtensionActivationSupportsWebURLWithMaxCount",
                    count(*multiple, TEXT_URL_MAX),
                );
            }
            IosShareClass::Image => raise(
                "NSExtensionActivationSupportsImageWithMaxCount",
                count(*multiple, IMAGE_MAX),
            ),
            IosShareClass::Movie => raise(
                "NSExtensionActivationSupportsMovieWithMaxCount",
                count(*multiple, MOVIE_MAX),
            ),
            IosShareClass::Specific { .. } => {}
        }
    }
    if text {
        rule.insert(
            "NSExtensionActivationSupportsText".to_owned(),
            serde_json::Value::Bool(true),
        );
    }
    Ok(serde_json::Value::Object(rule))
}

fn ios_activation_predicate(classes: &[(IosShareClass, bool)]) -> String {
    let mut identifiers: Vec<&str> = Vec::new();
    let mut maximum = 1;
    for (class, multiple) in classes {
        let (class_identifiers, class_maximum): (&[&'static str], u32) = match class {
            IosShareClass::Any => unreachable!("*/* uses the activation dictionary"),
            IosShareClass::Text { uti } => (
                match *uti {
                    "public.text" => &["public.text", "public.url"],
                    _ => &["public.plain-text", "public.url"],
                },
                TEXT_URL_MAX,
            ),
            IosShareClass::Image => (&["public.image"], IMAGE_MAX),
            IosShareClass::Movie => (&["public.movie"], MOVIE_MAX),
            IosShareClass::Specific { uti } => (std::slice::from_ref(uti), FILE_MAX),
        };
        for identifier in class_identifiers {
            if !identifiers.contains(identifier) {
                identifiers.push(identifier);
            }
        }
        if *multiple {
            maximum = maximum.max(class_maximum);
        }
    }
    let conforms = identifiers
        .iter()
        .map(|identifier| {
            format!("ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO \"{identifier}\"")
        })
        .collect::<Vec<_>>()
        .join(" OR ");
    format!(
        "SUBQUERY(extensionItems, $extensionItem, $extensionItem.attachments.@count >= 1 AND $extensionItem.attachments.@count <= {maximum} AND SUBQUERY($extensionItem.attachments, $attachment, {conforms}).@count == $extensionItem.attachments.@count).@count >= 1"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn share(json: &str) -> PluginShare {
        serde_json::from_str(json).expect("plugin share")
    }

    fn types(entries: &[(&str, bool)]) -> Vec<ShareType> {
        entries
            .iter()
            .map(|(mime, multiple)| ShareType {
                mime: (*mime).to_owned(),
                multiple: *multiple,
            })
            .collect()
    }

    #[test]
    fn defaults_accept_everything_several_at_once() {
        let share = share(r#"{"configKey":"shareExtension"}"#);
        assert_eq!(
            resolve("vendor/share", &share, None).expect("resolve"),
            types(&[("*/*", true)])
        );
    }

    #[test]
    fn application_config_overrides_plugin_defaults() {
        let share = share(r#"{"configKey":"shareExtension"}"#);
        let config = serde_json::json!({
            "accept": ["text/plain", "image/*", "video/*"],
            "multiple": ["image/*", "video/*"]
        });
        assert_eq!(
            resolve("vendor/share", &share, Some(&config)).expect("resolve"),
            types(&[("text/plain", false), ("image/*", true), ("video/*", true)])
        );
        let single = serde_json::json!({"accept": ["image/*"], "multiple": false});
        assert_eq!(
            resolve("vendor/share", &share, Some(&single)).expect("resolve"),
            types(&[("image/*", false)])
        );
        let only_multiple = serde_json::json!({"multiple": false});
        assert_eq!(
            resolve("vendor/share", &share, Some(&only_multiple)).expect("resolve"),
            types(&[("*/*", false)])
        );
    }

    #[test]
    fn invalid_application_config_is_rejected() {
        let share = share(r#"{"configKey":"shareExtension"}"#);
        for config in [
            serde_json::json!({"accept": []}),
            serde_json::json!({"accept": ["image"]}),
            serde_json::json!({"accept": ["Image/*"]}),
            serde_json::json!({"accept": ["image/*", "image/*"]}),
            serde_json::json!({"accept": ["image/*"], "multiple": ["video/*"]}),
            serde_json::json!({"accept": ["image/*"], "unknown": true}),
            serde_json::json!(["image/*"]),
        ] {
            assert!(
                resolve("vendor/share", &share, Some(&config)).is_err(),
                "{config}"
            );
        }
        assert!(valid_config_key("shareExtension"));
        assert!(!valid_config_key("Share"));
        assert!(!valid_config_key("share-extension"));
        assert!(!valid_config_key(""));
    }

    #[test]
    fn merge_keeps_order_and_widens_multiple() {
        assert_eq!(
            merge(types(&[
                ("image/*", true),
                ("text/plain", false),
                ("image/*", false),
                ("text/plain", true),
            ])),
            types(&[("image/*", true), ("text/plain", true)])
        );
    }

    #[test]
    fn android_filters_follow_the_accepted_types() {
        let filters =
            android_intent_filters(&types(&[("text/plain", false), ("image/*", true)]), "    ");
        assert_eq!(
            filters,
            "    <intent-filter>\n\
             \x20       <action android:name=\"android.intent.action.SEND\" />\n\
             \x20       <category android:name=\"android.intent.category.DEFAULT\" />\n\
             \x20       <data android:mimeType=\"text/plain\" />\n\
             \x20   </intent-filter>\n\
             \x20   <intent-filter>\n\
             \x20       <action android:name=\"android.intent.action.SEND\" />\n\
             \x20       <action android:name=\"android.intent.action.SEND_MULTIPLE\" />\n\
             \x20       <category android:name=\"android.intent.category.DEFAULT\" />\n\
             \x20       <data android:mimeType=\"image/*\" />\n\
             \x20   </intent-filter>\n"
        );
    }

    #[test]
    fn default_ios_rule_matches_the_legacy_share_extension() {
        assert_eq!(
            ios_activation_rule(&types(&[("*/*", true)])).expect("rule"),
            serde_json::json!({
                "NSExtensionActivationSupportsText": true,
                "NSExtensionActivationSupportsWebURLWithMaxCount": 8,
                "NSExtensionActivationSupportsFileWithMaxCount": 32,
                "NSExtensionActivationSupportsImageWithMaxCount": 32,
                "NSExtensionActivationSupportsMovieWithMaxCount": 8
            })
        );
        assert_eq!(
            ios_activation_rule(&types(&[("*/*", true), ("application/x-unknown", true)]))
                .expect("everything is accepted"),
            ios_activation_rule(&types(&[("*/*", true)])).expect("rule")
        );
    }

    #[test]
    fn media_and_text_ios_rule_has_no_files() {
        assert_eq!(
            ios_activation_rule(&types(&[
                ("text/plain", false),
                ("image/*", true),
                ("video/*", true),
            ]))
            .expect("rule"),
            serde_json::json!({
                "NSExtensionActivationSupportsText": true,
                "NSExtensionActivationSupportsWebURLWithMaxCount": 1,
                "NSExtensionActivationSupportsImageWithMaxCount": 32,
                "NSExtensionActivationSupportsMovieWithMaxCount": 8
            })
        );
        assert_eq!(
            ios_activation_rule(&types(&[("image/*", false)])).expect("rule"),
            serde_json::json!({"NSExtensionActivationSupportsImageWithMaxCount": 1})
        );
    }

    #[test]
    fn specific_file_types_use_a_type_identifier_predicate() {
        let rule = ios_activation_rule(&types(&[
            ("application/pdf", true),
            ("image/*", false),
            ("text/plain", false),
        ]))
        .expect("rule");
        assert_eq!(
            rule,
            serde_json::Value::String(
                "SUBQUERY(extensionItems, $extensionItem, $extensionItem.attachments.@count >= 1 AND $extensionItem.attachments.@count <= 32 AND SUBQUERY($extensionItem.attachments, $attachment, ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO \"com.adobe.pdf\" OR ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO \"public.image\" OR ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO \"public.plain-text\" OR ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO \"public.url\").@count == $extensionItem.attachments.@count).@count >= 1"
                    .to_owned()
            )
        );
        assert!(ios_activation_rule(&types(&[("application/x-unknown", false)])).is_err());
    }
}
