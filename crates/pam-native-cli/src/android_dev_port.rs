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

#[cfg(test)]
mod tests {
    use super::parse;

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
