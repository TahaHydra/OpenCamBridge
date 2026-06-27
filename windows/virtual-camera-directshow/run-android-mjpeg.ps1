cd $PSScriptRoot\rust-feeder
cargo run --release -- --source mjpeg --url http://127.0.0.1:8080/stream.mjpeg
