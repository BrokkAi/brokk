//! Minimal read-only ZIP parser, scoped to what session-zip readers
//! need: locate a named entry, decompress it (deflate or stored), and
//! return up to a configurable byte cap.
//!
//! Why not pull `zip` or `rc-zip-sync`:
//!   - `zip 8` depends on `typed-path 0.12.3`, which activates
//!     `#![feature(wasip2)]` and therefore requires nightly on
//!     `wasm32-wasip2`.
//!   - `rc-zip-sync` requires `std::fs::File: ReadAt` via
//!     `positioned-io`, which is not implemented for `File` on the
//!     wasi std crate at the time of writing.
//!
//! Both libraries are perfectly fine on native and the host's
//! `session.rs` continues to use `zip` there. This module exists so
//! the sandbox path can read the same archive format without the
//! transitive dependency landmines.
//!
//! Threat model: this code runs inside the wasm sandbox, so it must
//! never trust any field of the input. Every offset is checked
//! against the buffer length before slicing. Every length is bounded
//! by an explicit cap before allocating. A malformed archive returns
//! a structured error; it never panics or unwinds out of the parser.
//!
//! Supported entries: `Stored` (compression method 0) and `Deflated`
//! (method 8). Session zips are always written with `Deflated`
//! (see `session.rs::write_new_session_zip`), so this covers every
//! file the sandbox is ever asked to read.

use std::io::Cursor;

/// Local file header signature (PK\x03\x04). Marks each entry's
/// payload boundary in the archive body.
const LOCAL_FILE_HEADER_SIG: u32 = 0x04034b50;
/// Central directory entry signature (PK\x01\x02). The central
/// directory is the authoritative index of entries; we walk it
/// linearly to locate entries by name.
const CENTRAL_DIR_SIG: u32 = 0x02014b50;
/// End-of-central-directory record signature (PK\x05\x06). Found at
/// the tail of every well-formed zip; tells us where the central
/// directory begins.
const END_OF_CENTRAL_DIR_SIG: u32 = 0x06054b50;
/// Compression methods used by `session.rs`. Other methods (BZIP2,
/// LZMA, ZSTD, etc.) are rejected with a clear error.
const METHOD_STORED: u16 = 0;
const METHOD_DEFLATED: u16 = 8;

/// Single failure type for the reader. Stays minimal -- the sandbox
/// only needs to distinguish "entry not found" (Ok(None)) from
/// "everything else" (this error), since the host's existing call
/// sites tolerate both.
#[derive(Debug)]
pub enum ZipReadError {
    /// Buffer was too small or did not contain a valid EOCD record.
    NotAZip,
    /// A header signature did not match the expected magic.
    BadSignature {
        offset: usize,
        expected: u32,
        actual: u32,
    },
    /// An offset or length parsed from a header would exceed the
    /// archive buffer.
    OffsetOutOfBounds { offset: usize, len: usize },
    /// Entry uses a compression method other than Stored/Deflated.
    UnsupportedCompression(u16),
    /// Decompressed entry exceeded the caller's byte cap.
    EntryTooLarge { name: String, cap: u64, actual: u64 },
    /// `miniz_oxide` returned a decode error.
    Inflate(String),
    /// Decompressed payload was not valid UTF-8 (caller asked for
    /// text). Held as `String` so the host gets a stable display
    /// without dragging `std::str::Utf8Error` into the wire format.
    NotUtf8(String),
}

impl std::fmt::Display for ZipReadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotAZip => write!(f, "not a zip archive (missing EOCD record)"),
            Self::BadSignature {
                offset,
                expected,
                actual,
            } => write!(
                f,
                "bad signature at offset {offset}: expected 0x{expected:08x}, got 0x{actual:08x}"
            ),
            Self::OffsetOutOfBounds { offset, len } => {
                write!(f, "offset {offset}+{len} exceeds archive bounds")
            }
            Self::UnsupportedCompression(m) => write!(
                f,
                "unsupported compression method {m} (only Stored and Deflated are supported)"
            ),
            Self::EntryTooLarge { name, cap, actual } => write!(
                f,
                "entry '{name}' decompresses to {actual} bytes, exceeds cap of {cap}"
            ),
            Self::Inflate(s) => write!(f, "deflate decode error: {s}"),
            Self::NotUtf8(s) => write!(f, "{s}"),
        }
    }
}

impl std::error::Error for ZipReadError {}

/// Locate `entry_name` in `archive`, decompress it, return its bytes
/// as a UTF-8 string. Returns `Ok(None)` when the name is absent so
/// the caller can keep the "no entry here, move on" code path.
pub fn read_entry_text(
    archive: &[u8],
    entry_name: &str,
    max_bytes: u64,
) -> Result<Option<String>, ZipReadError> {
    let Some(bytes) = read_entry_bytes(archive, entry_name, max_bytes)? else {
        return Ok(None);
    };
    String::from_utf8(bytes).map(Some).map_err(|e| {
        ZipReadError::NotUtf8(format!(
            "entry '{entry_name}' is not valid UTF-8 ({} bytes): {e}",
            e.as_bytes().len()
        ))
    })
}

/// Iterate the central directory, decompress every entry whose name
/// starts with `prefix`, and return them as a map. `max_entry_bytes`
/// caps each entry; `max_total_bytes` caps the sum of decompressed
/// payloads so a swarm of small bomb entries cannot collectively
/// blow the wasm memory limit. Used by the history reader to fetch
/// `content/*.txt` in one round-trip instead of N.
pub fn read_entries_with_prefix(
    archive: &[u8],
    prefix: &str,
    max_entry_bytes: u64,
    max_total_bytes: u64,
) -> Result<std::collections::HashMap<String, String>, ZipReadError> {
    let names = list_entry_names(archive)?;
    let mut out: std::collections::HashMap<String, String> = std::collections::HashMap::new();
    let mut total: u64 = 0;
    for name in names {
        if !name.starts_with(prefix) {
            continue;
        }
        let Some(content) = read_entry_text(archive, &name, max_entry_bytes)? else {
            continue;
        };
        let len = content.len() as u64;
        total = total.checked_add(len).ok_or(ZipReadError::EntryTooLarge {
            name: name.clone(),
            cap: max_total_bytes,
            actual: u64::MAX,
        })?;
        if total > max_total_bytes {
            return Err(ZipReadError::EntryTooLarge {
                name,
                cap: max_total_bytes,
                actual: total,
            });
        }
        out.insert(name, content);
    }
    Ok(out)
}

/// Walk the central directory and collect every entry name. Names
/// are returned in archive order. UTF-8 names are required; non-UTF-8
/// names are skipped with no error (they are valid in the spec but
/// session zips never produce them). Used by `read_entries_with_prefix`
/// and exposed publicly so callers that want to stream entries
/// one-at-a-time (e.g. the session-zip rewrite path) can iterate
/// names without decompressing.
pub fn list_entry_names(archive: &[u8]) -> Result<Vec<String>, ZipReadError> {
    let eocd = find_eocd(archive)?;
    let cd_offset = read_u32_le(archive, eocd + 16)? as usize;
    let cd_size = read_u32_le(archive, eocd + 12)? as usize;
    let cd_end = cd_offset
        .checked_add(cd_size)
        .ok_or(ZipReadError::OffsetOutOfBounds {
            offset: cd_offset,
            len: cd_size,
        })?;
    if cd_end > archive.len() {
        return Err(ZipReadError::OffsetOutOfBounds {
            offset: cd_offset,
            len: cd_size,
        });
    }
    let mut names = Vec::new();
    let mut cursor = cd_offset;
    while cursor + 46 <= cd_end {
        let sig = read_u32_le(archive, cursor)?;
        if sig != CENTRAL_DIR_SIG {
            return Err(ZipReadError::BadSignature {
                offset: cursor,
                expected: CENTRAL_DIR_SIG,
                actual: sig,
            });
        }
        let name_len = read_u16_le(archive, cursor + 28)? as usize;
        let extra_len = read_u16_le(archive, cursor + 30)? as usize;
        let comment_len = read_u16_le(archive, cursor + 32)? as usize;
        let name_start = cursor + 46;
        let name_end = name_start
            .checked_add(name_len)
            .ok_or(ZipReadError::OffsetOutOfBounds {
                offset: name_start,
                len: name_len,
            })?;
        if name_end > cd_end {
            return Err(ZipReadError::OffsetOutOfBounds {
                offset: name_start,
                len: name_len,
            });
        }
        if let Ok(name) = std::str::from_utf8(&archive[name_start..name_end]) {
            names.push(name.to_string());
        }
        cursor = name_end + extra_len + comment_len;
        if cursor > cd_end {
            return Err(ZipReadError::OffsetOutOfBounds {
                offset: cursor,
                len: 0,
            });
        }
    }
    Ok(names)
}

/// Variant returning raw bytes. Useful for binary entries; the
/// session zips currently store only text but the same primitive
/// covers future needs.
pub fn read_entry_bytes(
    archive: &[u8],
    entry_name: &str,
    max_bytes: u64,
) -> Result<Option<Vec<u8>>, ZipReadError> {
    let eocd = find_eocd(archive)?;
    let cd_offset = read_u32_le(archive, eocd + 16)? as usize;
    let cd_size = read_u32_le(archive, eocd + 12)? as usize;
    let cd_end = cd_offset
        .checked_add(cd_size)
        .ok_or(ZipReadError::OffsetOutOfBounds {
            offset: cd_offset,
            len: cd_size,
        })?;
    if cd_end > archive.len() {
        return Err(ZipReadError::OffsetOutOfBounds {
            offset: cd_offset,
            len: cd_size,
        });
    }

    let mut cursor = cd_offset;
    while cursor + 46 <= cd_end {
        let sig = read_u32_le(archive, cursor)?;
        if sig != CENTRAL_DIR_SIG {
            return Err(ZipReadError::BadSignature {
                offset: cursor,
                expected: CENTRAL_DIR_SIG,
                actual: sig,
            });
        }
        let method = read_u16_le(archive, cursor + 10)?;
        let compressed_size = read_u32_le(archive, cursor + 20)? as u64;
        let uncompressed_size = read_u32_le(archive, cursor + 24)? as u64;
        let name_len = read_u16_le(archive, cursor + 28)? as usize;
        let extra_len = read_u16_le(archive, cursor + 30)? as usize;
        let comment_len = read_u16_le(archive, cursor + 32)? as usize;
        let local_header_off = read_u32_le(archive, cursor + 42)? as usize;
        let name_start = cursor + 46;
        let name_end = name_start
            .checked_add(name_len)
            .ok_or(ZipReadError::OffsetOutOfBounds {
                offset: name_start,
                len: name_len,
            })?;
        if name_end > cd_end {
            return Err(ZipReadError::OffsetOutOfBounds {
                offset: name_start,
                len: name_len,
            });
        }
        let entry = &archive[name_start..name_end];

        if entry == entry_name.as_bytes() {
            if uncompressed_size > max_bytes {
                return Err(ZipReadError::EntryTooLarge {
                    name: entry_name.to_string(),
                    cap: max_bytes,
                    actual: uncompressed_size,
                });
            }
            return Ok(Some(decompress_entry(
                archive,
                local_header_off,
                method,
                compressed_size,
                uncompressed_size,
                max_bytes,
                entry_name,
            )?));
        }

        cursor = name_end + extra_len + comment_len;
        // Defensive: bail if the header was malformed enough to push
        // us past the central directory end.
        if cursor > cd_end {
            return Err(ZipReadError::OffsetOutOfBounds {
                offset: cursor,
                len: 0,
            });
        }
        // Suppress unused warning when local_header_off is not the
        // matching entry; the check above already validates it on
        // the match path.
        let _ = local_header_off;
    }

    Ok(None)
}

fn decompress_entry(
    archive: &[u8],
    local_header_off: usize,
    method: u16,
    compressed_size: u64,
    uncompressed_size: u64,
    max_bytes: u64,
    entry_name: &str,
) -> Result<Vec<u8>, ZipReadError> {
    // Local file header layout: 4 sig + 26 fixed bytes + name_len + extra_len.
    // We trust the central directory for the byte counts but re-read
    // the local header's name_len/extra_len to find the payload start
    // (zip allows the local header's name/extra blocks to differ from
    // the central directory's, even though it normally won't).
    if local_header_off + 30 > archive.len() {
        return Err(ZipReadError::OffsetOutOfBounds {
            offset: local_header_off,
            len: 30,
        });
    }
    let sig = read_u32_le(archive, local_header_off)?;
    if sig != LOCAL_FILE_HEADER_SIG {
        return Err(ZipReadError::BadSignature {
            offset: local_header_off,
            expected: LOCAL_FILE_HEADER_SIG,
            actual: sig,
        });
    }
    let name_len = read_u16_le(archive, local_header_off + 26)? as usize;
    let extra_len = read_u16_le(archive, local_header_off + 28)? as usize;
    let payload_start = local_header_off + 30 + name_len + extra_len;
    let compressed_size_us = compressed_size as usize;
    let payload_end =
        payload_start
            .checked_add(compressed_size_us)
            .ok_or(ZipReadError::OffsetOutOfBounds {
                offset: payload_start,
                len: compressed_size_us,
            })?;
    if payload_end > archive.len() {
        return Err(ZipReadError::OffsetOutOfBounds {
            offset: payload_start,
            len: compressed_size_us,
        });
    }
    let compressed = &archive[payload_start..payload_end];

    match method {
        METHOD_STORED => {
            if compressed.len() as u64 > max_bytes {
                return Err(ZipReadError::EntryTooLarge {
                    name: entry_name.to_string(),
                    cap: max_bytes,
                    actual: compressed.len() as u64,
                });
            }
            Ok(compressed.to_vec())
        }
        METHOD_DEFLATED => {
            // `miniz_oxide` allocates the output buffer up front. Cap
            // the requested size at `max_bytes` so we cannot be
            // tricked into a multi-GB allocation by a lying CD entry.
            let cap = max_bytes.min(uncompressed_size) as usize;
            let mut out = Vec::with_capacity(cap);
            let mut decoder =
                miniz_oxide::inflate::stream::InflateState::new_boxed(miniz_oxide::DataFormat::Raw);
            let mut input = compressed;
            let mut buf = vec![0u8; 64 * 1024];
            loop {
                let result = miniz_oxide::inflate::stream::inflate(
                    &mut decoder,
                    input,
                    &mut buf,
                    miniz_oxide::MZFlush::None,
                );
                if result.status.is_err() {
                    return Err(ZipReadError::Inflate(format!(
                        "{:?} after {} bytes",
                        result.status, result.bytes_consumed
                    )));
                }
                out.extend_from_slice(&buf[..result.bytes_written]);
                if out.len() as u64 > max_bytes {
                    return Err(ZipReadError::EntryTooLarge {
                        name: entry_name.to_string(),
                        cap: max_bytes,
                        actual: out.len() as u64,
                    });
                }
                input = &input[result.bytes_consumed..];
                match result.status {
                    Ok(miniz_oxide::MZStatus::StreamEnd) => break,
                    Ok(miniz_oxide::MZStatus::Ok) | Ok(miniz_oxide::MZStatus::NeedDict) => {
                        if input.is_empty() && result.bytes_written == 0 {
                            return Err(ZipReadError::Inflate(
                                "stream ended without StreamEnd marker".into(),
                            ));
                        }
                    }
                    Err(e) => return Err(ZipReadError::Inflate(format!("{e:?}"))),
                }
            }
            Ok(out)
        }
        other => Err(ZipReadError::UnsupportedCompression(other)),
    }
}

/// Walk back from the archive tail looking for the EOCD signature.
/// The comment field may be up to 64 KiB so we scan that far at most;
/// session zips never carry a comment so the signature is almost
/// always at `archive.len() - 22`.
fn find_eocd(archive: &[u8]) -> Result<usize, ZipReadError> {
    if archive.len() < 22 {
        return Err(ZipReadError::NotAZip);
    }
    let max_back = archive.len().min(22 + 65_535);
    let scan_start = archive.len() - max_back;
    let scan_end = archive.len() - 22;
    let mut i = scan_end;
    loop {
        if read_u32_le(archive, i)? == END_OF_CENTRAL_DIR_SIG {
            return Ok(i);
        }
        if i == scan_start {
            return Err(ZipReadError::NotAZip);
        }
        i -= 1;
    }
}

fn read_u16_le(buf: &[u8], at: usize) -> Result<u16, ZipReadError> {
    let end = at
        .checked_add(2)
        .ok_or(ZipReadError::OffsetOutOfBounds { offset: at, len: 2 })?;
    if end > buf.len() {
        return Err(ZipReadError::OffsetOutOfBounds { offset: at, len: 2 });
    }
    Ok(u16::from_le_bytes([buf[at], buf[at + 1]]))
}

fn read_u32_le(buf: &[u8], at: usize) -> Result<u32, ZipReadError> {
    let end = at
        .checked_add(4)
        .ok_or(ZipReadError::OffsetOutOfBounds { offset: at, len: 4 })?;
    if end > buf.len() {
        return Err(ZipReadError::OffsetOutOfBounds { offset: at, len: 4 });
    }
    Ok(u32::from_le_bytes([
        buf[at],
        buf[at + 1],
        buf[at + 2],
        buf[at + 3],
    ]))
}

// The Cursor import is retained for callers that pipe the archive
// through `std::io::Cursor`; the parser itself works directly on `&[u8]`.
#[allow(dead_code)]
fn _phantom_cursor(buf: &[u8]) -> Cursor<&[u8]> {
    Cursor::new(buf)
}
