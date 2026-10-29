//! Builds libaom and dav1d from the pinned submodules in `vendor/`, links them
//! statically, and generates bindings for the parts of their C APIs the
//! crate uses.
//!
//! Static linking means the finished library depends on nothing but the OS,
//! so it can be bundled into the voice jar the same way the iroh natives are
//! bundled into the core.
//!
//! Needs cmake, meson and ninja (pinned in the repository's `.mise.toml`),
//! plus nasm on x86_64.
//!
//! With `MCNET_VIDEO_BINDINGS_ONLY=1` the codecs are neither built nor
//! linked and the bindings come straight from the headers in the source
//! trees. That is enough for `cargo check --target ...`, which is how the
//! Windows and Linux capture backends are type-checked from a Mac.

use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=wrapper.h");
    println!("cargo:rerun-if-changed=vendor/aom");
    println!("cargo:rerun-if-changed=vendor/dav1d");
    println!("cargo:rerun-if-env-changed=MCNET_VIDEO_BINDINGS_ONLY");

    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    for (dir, file) in [
        ("vendor/aom", "CMakeLists.txt"),
        ("vendor/dav1d", "meson.build"),
    ] {
        assert!(
            Path::new(dir).join(file).exists(),
            "{dir} is empty; run `git submodule update --init` from the repository root"
        );
    }

    let bindings_only = env::var_os("MCNET_VIDEO_BINDINGS_ONLY").is_some();
    let mut bindings = bindgen::Builder::default().header("wrapper.h");

    if bindings_only {
        // The headers are read with the host's clang target, because the
        // libc headers for the real target are not available here. The C
        // types involved have the same layout on every 64-bit target.
        bindings = bindings
            .clang_arg(format!("--target={}", env::var("HOST").unwrap()))
            .clang_arg("-Ivendor/aom")
            .clang_arg("-Ivendor/dav1d/include");
    } else {
        let aom = build_aom();
        let dav1d = build_dav1d(&out);
        for prefix in [&aom, &dav1d] {
            println!(
                "cargo:rustc-link-search=native={}",
                prefix.join("lib").display()
            );
        }
        link_static("aom");
        link_static("dav1d");
        if env::var("CARGO_CFG_TARGET_OS").unwrap() == "linux" {
            println!("cargo:rustc-link-lib=m");
        }
        bindings = bindings
            .clang_arg(format!("-I{}", aom.join("include").display()))
            .clang_arg(format!("-I{}", dav1d.join("include").display()));
    }

    let bindings = bindings
        .allowlist_function("aom_codec_.*")
        .allowlist_function("aom_img_.*")
        .allowlist_type("aom_.*")
        .allowlist_type("aome_.*")
        .allowlist_var("AOM_.*")
        .allowlist_var("AV1E_.*")
        .allowlist_var("AOME_.*")
        .allowlist_function("dav1d_.*")
        .allowlist_type("Dav1d.*")
        .allowlist_var("DAV1D_.*")
        .allowlist_var("EAGAIN")
        // Constants keep their C names (AOME_SET_CPUUSED, not
        // aome_enc_control_id_AOME_SET_CPUUSED), so the Rust reads like
        // libaom's own documentation.
        .prepend_enum_name(false)
        .rustified_enum("aom_codec_err_t")
        .rustified_enum("aom_codec_cx_pkt_kind")
        .derive_default(true)
        .layout_tests(false)
        .generate()
        .expect("could not generate codec bindings");

    bindings
        .write_to_file(out.join("codecs.rs"))
        .expect("could not write codec bindings");
}

/// The encoder only, in realtime-only mode. dav1d does the decoding, and
/// realtime is the only usage a call has, so leaving the rest out roughly
/// halves the build and the binary.
fn build_aom() -> PathBuf {
    cmake::Config::new("vendor/aom")
        .profile("Release")
        .define("BUILD_SHARED_LIBS", "OFF")
        .define("CONFIG_AV1_DECODER", "0")
        .define("CONFIG_REALTIME_ONLY", "1")
        .define("ENABLE_APPS", "OFF")
        .define("ENABLE_DOCS", "OFF")
        .define("ENABLE_EXAMPLES", "OFF")
        .define("ENABLE_TESTS", "OFF")
        .define("ENABLE_TESTDATA", "OFF")
        .define("ENABLE_TOOLS", "OFF")
        // yasm is the default and is unmaintained; nasm is current.
        .define("ENABLE_NASM", "ON")
        .build()
}

/// 8-bit only, since webcams are 8-bit and the decoder rejects anything
/// else. Built in OUT_DIR so cargo's incremental rebuilds reuse it.
fn build_dav1d(out: &Path) -> PathBuf {
    let build = out.join("dav1d-build");
    let prefix = out.join("dav1d");
    if !build.join("build.ninja").exists() {
        let source = Path::new("vendor/dav1d").canonicalize().unwrap();
        meson(&[
            "setup".as_ref(),
            build.as_os_str(),
            source.as_os_str(),
            format!("--prefix={}", prefix.display()).as_ref(),
            "--libdir=lib".as_ref(),
            "--buildtype=release".as_ref(),
            "--default-library=static".as_ref(),
            "-Dbitdepths=8".as_ref(),
            "-Denable_tools=false".as_ref(),
            "-Denable_examples=false".as_ref(),
            "-Denable_tests=false".as_ref(),
            "-Denable_docs=false".as_ref(),
        ]);
    }
    meson(&["install".as_ref(), "-C".as_ref(), build.as_os_str()]);
    prefix
}

fn meson(args: &[&std::ffi::OsStr]) {
    let meson = env::var("MESON").unwrap_or_else(|_| "meson".into());
    let status = Command::new(&meson)
        .args(args)
        .status()
        .unwrap_or_else(|e| {
            panic!("could not run {meson} ({e}); install meson and ninja, see .mise.toml")
        });
    assert!(status.success(), "meson {args:?} failed");
}

/// Meson names a static library `libfoo.a` even with MSVC, where rustc
/// would otherwise look for `foo.lib`.
fn link_static(name: &str) {
    if env::var("CARGO_CFG_TARGET_ENV").unwrap() == "msvc" {
        println!("cargo:rustc-link-lib=static:+verbatim=lib{name}.a");
    } else {
        println!("cargo:rustc-link-lib=static={name}");
    }
}
