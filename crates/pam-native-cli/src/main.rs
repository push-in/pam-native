use std::ffi::OsString;
use std::process::ExitCode;

mod app_icon;
mod appearance;
mod bundle_paths;
mod dev_event;
mod mobile;

fn main() -> ExitCode {
    match mobile::run(std::env::args_os().skip(1).collect::<Vec<OsString>>()) {
        Ok(code) => ExitCode::from(code),
        Err(error) => {
            eprintln!("pam-native: {error}");
            ExitCode::from(1)
        }
    }
}
