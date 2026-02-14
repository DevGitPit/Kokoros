// This build script adds the necessary system library search paths
// for espeak-rs-sys on Linux systems.
// espeak-rs-sys's build script doesn't add the standard system library paths,
// which causes linker errors when trying to link against libsonic and libpcaudio.

fn main() {
    // Only add these paths on Linux (but not Android)
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();

    if target_os == "android" {
        println!("cargo:rustc-link-lib=log");
    }

    if target_os == "linux" && target_arch == "x86_64" {
        // Add standard system library search paths for host Linux
        println!("cargo:rustc-link-search=/usr/lib");
        println!("cargo:rustc-link-search=/usr/lib/x86_64-linux-gnu");
        println!("cargo:rustc-link-search=/usr/lib64");

        // Link against required system libraries
        println!("cargo:rustc-link-lib=dylib=sonic");
        println!("cargo:rustc-link-lib=dylib=pcaudio");
    }

    // macOS specific settings
    if cfg!(target_os = "macos") {
        println!("cargo:rustc-link-lib=c++");
    }
}
