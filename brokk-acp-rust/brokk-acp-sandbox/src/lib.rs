//! Pure-parsing logic for `brokk-acp-rust`, exported both as a Rust
//! library (linked directly into the native binary) and -- via the
//! companion binary in `src/bin/sandbox.rs` -- as a `wasm32-wasip2`
//! component that the native binary spawns under wasmtime for
//! sandboxed parsing of untrusted inputs.
//!
//! Everything here is dependency-light, has no fs/network/process
//! access, and runs on every target Rust supports. The only inputs
//! are owned strings or byte slices; the only outputs are `Serialize`
//! data structures. Each function is a candidate for the wasm
//! sandbox because the failure modes we care about are:
//!
//!   - YAML bombs / billion-laughs against `serde_yaml`
//!   - Malformed frontmatter that triggers panics in third-party crates
//!   - Future regex/zip parsers that can blow CPU or memory
//!
//! Adding a new parser to this crate is the standard path for getting
//! "wasm-by-default with native fallback" coverage in `brokk-acp-rust`.

pub mod search;
pub mod skills;
pub mod zip_reader;

pub use search::{SearchError, SearchMatch, SearchOutcome, search as search_file_contents};
pub use skills::{ParsedFrontmatter, parse_frontmatter, split_frontmatter};
pub use zip_reader::{
    ZipReadError, list_entry_names as list_zip_entry_names,
    read_entries_with_prefix as read_zip_entries_with_prefix,
    read_entry_bytes as read_zip_entry_bytes, read_entry_text as read_zip_entry_text,
};
