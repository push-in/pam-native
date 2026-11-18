//! Host port selection. ADB maps the device's stable port to this host port.

pub(super) fn resolve(default: u16) -> Result<u16, String> {
    match std::env::var("PAM_NATIVE_ANDROID_DEV_PORT") {
        Ok(value) => parse(Some(&value), default),
        Err(std::env::VarError::NotPresent) => Ok(default),
        Err(_) => Err("PAM_NATIVE_ANDROID_DEV_PORT must be valid UTF-8".to_owned()),
    }
}

fn parse(value: Option<&str>, default: u16) -> Result<u16, String> {
    let Some(value) = value else {
        return Ok(default);
    };
    value
        .parse::<u16>()
        .ok()
        .filter(|port| *port >= 1024)
        .ok_or_else(|| {
            "PAM_NATIVE_ANDROID_DEV_PORT must be an integer from 1024 through 65535".to_owned()
        })
}

/// The device-side `pam dev` port of an Android package: 39100-39899 from an
/// FNV-1a hash of the installed package name (HotReloadClient computes the
/// same). Each app talks to its own `adb reverse` mapping, so two apps on one
/// phone never reach each other's server (a second `pam dev` used to take over
/// another app's device port 39100 and push it the wrong bundle).
pub fn device_port(package: &str) -> u16 {
    let mut hash: u32 = 0x811c_9dc5;
    for byte in package.bytes() {
        hash ^= u32::from(byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    39_100 + (hash % 800) as u16
}

#[cfg(test)]
mod tests {
    use super::{device_port, parse};

    #[test]
    fn device_ports_are_stable_per_package_and_differ_between_apps() {
        // Same values as HotReloadClient.devicePort (Kotlin).
        assert_eq!(
            device_port("dev.pam.biotest.debug"),
            device_port("dev.pam.biotest.debug")
        );
        assert_ne!(
            device_port("dev.pam.biotest.debug"),
            device_port("com.pushin.pushinpay.pam.debug")
        );
        for package in ["a", "dev.pam.nativeapp", "com.example.app.debug"] {
            assert!((39_100..39_900).contains(&device_port(package)));
        }
        assert_eq!(
            device_port("dev.pam.nativeapp"),
            39_100 + (fnv("dev.pam.nativeapp") % 800) as u16
        );
    }

    fn fnv(text: &str) -> u32 {
        text.bytes().fold(0x811c_9dc5u32, |hash, byte| {
            (hash ^ u32::from(byte)).wrapping_mul(0x0100_0193)
        })
    }

    #[test]
    fn alternate_host_port_keeps_default_and_rejects_invalid_values() {
        assert_eq!(parse(None, 39100), Ok(39100));
        assert_eq!(parse(Some("39102"), 39100), Ok(39102));
        assert_eq!(parse(Some("1024"), 39100), Ok(1024));
        assert_eq!(parse(Some("65535"), 39100), Ok(65535));
        for value in ["", "0", "1023", "65536", "-1", "39102.5", "abc"] {
            assert!(parse(Some(value), 39100).is_err(), "accepted {value}");
        }
    }
}
