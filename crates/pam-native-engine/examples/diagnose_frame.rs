use std::{env, fs, process::ExitCode};

use pam_native_engine::Engine;

fn main() -> ExitCode {
    let Some(path) = env::args_os().nth(1) else {
        eprintln!("usage: diagnose_frame <render-frame>");
        return ExitCode::from(2);
    };
    let frame = match fs::read(&path) {
        Ok(frame) => frame,
        Err(error) => {
            eprintln!("failed to read {path:?}: {error}");
            return ExitCode::from(2);
        }
    };

    let mut engine = Engine::new();
    let mut args = env::args().skip(2);
    if let (Some(width), Some(height)) = (args.next(), args.next()) {
        let width = width.parse::<f32>().expect("width must be a number");
        let height = height.parse::<f32>().expect("height must be a number");
        engine
            .set_viewport(width, height)
            .expect("viewport must be finite and positive");
    }

    match engine.commit(&frame) {
        Ok(batch) => {
            println!(
                "valid render frame: {} input bytes, {} mutation bytes",
                frame.len(),
                batch.len(),
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("invalid render frame ({} bytes): {error:?}", frame.len(),);
            ExitCode::FAILURE
        }
    }
}
