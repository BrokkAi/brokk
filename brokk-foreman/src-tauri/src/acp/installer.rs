//! Install flow for registry-distributed agents.
//!
//! - `binary`: download the archive matching `Platform::current()` to
//!   `<install_dir>/<id>/<version>.<ext>`, extract to
//!   `<install_dir>/<id>/<version>/`, return that extracted path so the
//!   caller can persist it via `agents::set_installed_path`.
//! - `npx` / `uvx`: nothing to fetch ahead of time; we only verify the
//!   launcher binary (`npx`/`uvx`) exists at install-button time so the
//!   user gets fast feedback if it's missing.
//!
//! Archive formats supported: `.tar.gz`/`.tgz` and `.zip`. Installer-style
//! formats (`.dmg`/`.pkg`/`.deb`/`.rpm`/`.msi`/`.appimage`) are rejected
//! per the registry spec — those install system-wide, not under our
//! sandboxed agents directory.

use std::path::{Path, PathBuf};

use thiserror::Error;
use tokio::fs;

use crate::acp::registry::{Distribution, Platform, RegistryAgent};

#[derive(Debug, Error)]
pub enum InstallError {
    #[error("agent {0:?} has no binary target for the running host platform")]
    NoBinaryForHost(String),
    #[error("agent {0:?} declared neither binary nor npx nor uvx distribution")]
    NoDistribution(String),
    #[error("rejected installer-style archive {0:?} (use a portable tar.gz or zip)")]
    UnsupportedArchive(String),
    #[error("unrecognized archive extension for {0:?}; expected .tar.gz/.tgz or .zip")]
    UnknownArchiveFormat(String),
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("zip extraction error: {0}")]
    Zip(#[from] zip::result::ZipError),
    #[error("npx is not on PATH; install Node.js >=18 to use npx-distributed agents")]
    NpxMissing,
    #[error("uvx is not on PATH; install uv (Astral) to use uvx-distributed agents")]
    UvxMissing,
    #[error("registry CDN returned {0}")]
    Status(reqwest::StatusCode),
}

/// Concrete result of an install. The caller writes `installed_path` into
/// the agents table (only meaningful for `Binary`).
#[derive(Debug, Clone)]
pub enum InstallOutcome {
    Binary { installed_path: PathBuf },
    Npx,
    Uvx,
}

/// Install (or "verify availability of") an agent's preferred distribution
/// for the host. Resolution order matches `agents::launch_spec`: binary,
/// then npx, then uvx — install the first one we can satisfy.
pub async fn install(
    agent: &RegistryAgent,
    install_root: &Path,
) -> Result<InstallOutcome, InstallError> {
    let dist = &agent.distribution;

    if let Some(target) = host_binary_target(dist) {
        let archive_url = target.archive.clone();
        ensure_supported_archive(&archive_url)?;
        let dest_dir = install_root
            .join(&agent.id)
            .join(version_or_unknown(&agent.version));
        fs::create_dir_all(&dest_dir).await?;
        let archive_bytes = download(&archive_url).await?;
        extract(&archive_url, &archive_bytes, &dest_dir).await?;
        return Ok(InstallOutcome::Binary {
            installed_path: dest_dir,
        });
    }

    if dist.npx.is_some() {
        which::which("npx").map_err(|_| InstallError::NpxMissing)?;
        return Ok(InstallOutcome::Npx);
    }

    if dist.uvx.is_some() {
        which::which("uvx").map_err(|_| InstallError::UvxMissing)?;
        return Ok(InstallOutcome::Uvx);
    }

    if dist.binary.is_some() {
        Err(InstallError::NoBinaryForHost(agent.id.clone()))
    } else {
        Err(InstallError::NoDistribution(agent.id.clone()))
    }
}

fn host_binary_target(dist: &Distribution) -> Option<&crate::acp::registry::BinaryTarget> {
    let host = Platform::current()?;
    dist.binary.as_ref()?.get(&host)
}

fn version_or_unknown(v: &str) -> &str {
    if v.is_empty() { "unknown" } else { v }
}

fn ensure_supported_archive(url: &str) -> Result<(), InstallError> {
    let lower = url.to_ascii_lowercase();
    for bad in [".dmg", ".pkg", ".deb", ".rpm", ".msi", ".appimage"] {
        if lower.ends_with(bad) {
            return Err(InstallError::UnsupportedArchive(url.into()));
        }
    }
    if lower.ends_with(".tar.gz") || lower.ends_with(".tgz") || lower.ends_with(".zip") {
        Ok(())
    } else {
        Err(InstallError::UnknownArchiveFormat(url.into()))
    }
}

async fn download(url: &str) -> Result<Vec<u8>, InstallError> {
    let resp = reqwest::Client::builder()
        .user_agent("brokk-foreman/0.1")
        .build()?
        .get(url)
        .send()
        .await?;
    if !resp.status().is_success() {
        return Err(InstallError::Status(resp.status()));
    }
    let bytes = resp.bytes().await?;
    Ok(bytes.to_vec())
}

async fn extract(url: &str, bytes: &[u8], dest: &Path) -> Result<(), InstallError> {
    let lower = url.to_ascii_lowercase();
    let dest_owned = dest.to_path_buf();
    let bytes_owned = bytes.to_vec();
    let lower_owned = lower.clone();

    // Archive extraction is sync (flate2/tar, zip) and CPU/disk heavy. Run
    // it on the blocking pool so it doesn't stall the runtime.
    tokio::task::spawn_blocking(move || -> Result<(), InstallError> {
        if lower_owned.ends_with(".tar.gz") || lower_owned.ends_with(".tgz") {
            let gz = flate2::read::GzDecoder::new(&bytes_owned[..]);
            let mut archive = tar::Archive::new(gz);
            archive.unpack(&dest_owned)?;
            return Ok(());
        }
        if lower_owned.ends_with(".zip") {
            let reader = std::io::Cursor::new(bytes_owned);
            let mut zip = zip::ZipArchive::new(reader)?;
            zip.extract(&dest_owned)?;
            return Ok(());
        }
        Err(InstallError::UnknownArchiveFormat(lower_owned))
    })
    .await
    .map_err(|e| InstallError::Io(std::io::Error::other(e)))??;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_installer_archives() {
        assert!(matches!(
            ensure_supported_archive("https://x/y.dmg"),
            Err(InstallError::UnsupportedArchive(_))
        ));
        assert!(matches!(
            ensure_supported_archive("https://x/y.msi"),
            Err(InstallError::UnsupportedArchive(_))
        ));
    }

    #[test]
    fn rejects_unknown_archive_formats() {
        assert!(matches!(
            ensure_supported_archive("https://x/y.rar"),
            Err(InstallError::UnknownArchiveFormat(_))
        ));
    }

    #[test]
    fn accepts_tar_gz_and_zip() {
        ensure_supported_archive("https://x/y.tar.gz").unwrap();
        ensure_supported_archive("https://x/y.tgz").unwrap();
        ensure_supported_archive("https://x/y.zip").unwrap();
    }
}
