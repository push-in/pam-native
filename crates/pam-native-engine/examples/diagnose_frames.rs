use std::{env, fs, process::ExitCode};

use pam_native_engine::Engine;

fn main() -> ExitCode {
    let mut args = env::args_os().skip(1);
    let Some(width) = args.next() else {
        eprintln!("usage: diagnose_frames <width> <height> <render-frame>...");
        return ExitCode::from(2);
    };
    let Some(height) = args.next() else {
        eprintln!("usage: diagnose_frames <width> <height> <render-frame>...");
        return ExitCode::from(2);
    };
    let width = width
        .to_string_lossy()
        .parse::<f32>()
        .expect("width must be a number");
    let height = height
        .to_string_lossy()
        .parse::<f32>()
        .expect("height must be a number");

    let text_scale = env::var("PAM_TEXT_SCALE")
        .ok()
        .map(|value| {
            value
                .parse::<f32>()
                .expect("PAM_TEXT_SCALE must be a number")
        })
        .unwrap_or(1.0);
    let mut engine = Engine::new();
    engine
        .set_viewport(width, height)
        .expect("viewport must be finite and positive");
    engine
        .set_text_scale(text_scale)
        .expect("text scale must be finite and positive");
    let mut count = 0_usize;
    for path in args {
        count += 1;
        let frame = fs::read(&path).unwrap_or_else(|error| {
            panic!("failed to read {path:?}: {error}");
        });
        match engine.commit(&frame) {
            Ok(batch) => println!(
                "frame {count}: valid ({} input bytes, {} mutation bytes)",
                frame.len(),
                batch.len(),
            ),
            Err(error) => {
                eprintln!("frame {count}: invalid ({} bytes): {error:?}", frame.len());
                return ExitCode::FAILURE;
            }
        }
    }
    if count == 0 {
        eprintln!("at least one render frame is required");
        return ExitCode::from(2);
    }

    ExitCode::SUCCESS
}
