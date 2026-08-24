use std::env;
use std::path::PathBuf;
use std::process::Command;

// Stamps the binary with the git revision it was built from so `usboss-client
// version` can report something more precise than the crate version alone.
// Builds without git (source tarballs, sandboxes) fall back to "unknown", and
// both values can be overridden with USBOSS_GIT_DESCRIBE / USBOSS_BUILD_DATE.
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=USBOSS_GIT_DESCRIBE");
    println!("cargo:rerun-if-env-changed=USBOSS_BUILD_DATE");
    watch_git_refs();

    let describe = env_override("USBOSS_GIT_DESCRIBE")
        .or_else(|| git(&["describe", "--tags", "--always", "--dirty"]))
        .unwrap_or_else(|| "unknown".to_string());
    let date = env_override("USBOSS_BUILD_DATE")
        .or_else(|| git(&["log", "-1", "--date=format:%Y-%m-%d", "--format=%cd"]))
        .unwrap_or_else(|| "unknown".to_string());

    println!("cargo:rustc-env=USBOSS_GIT_DESCRIBE={describe}");
    println!("cargo:rustc-env=USBOSS_BUILD_DATE={date}");
}

fn env_override(key: &str) -> Option<String> {
    env::var(key)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

// Rebuild when HEAD moves so the stamp cannot go stale on an otherwise
// unchanged source tree. The index is deliberately not watched: it churns on
// routine git commands and would rebuild the crate far too often.
fn watch_git_refs() {
    let git_dir = match git(&["rev-parse", "--absolute-git-dir"]) {
        Some(dir) => PathBuf::from(dir),
        None => return,
    };
    let mut candidates = vec![git_dir.join("HEAD"), git_dir.join("packed-refs")];
    if let Some(head_ref) = git(&["rev-parse", "--symbolic-full-name", "HEAD"]) {
        candidates.push(git_dir.join(head_ref));
    }
    for path in candidates {
        if path.exists() {
            println!("cargo:rerun-if-changed={}", path.display());
        }
    }
}

fn git(args: &[&str]) -> Option<String> {
    let output = Command::new("git").args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}
