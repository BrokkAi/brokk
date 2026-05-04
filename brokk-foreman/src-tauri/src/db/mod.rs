//! SQLite state for foreman. Schema lives next to this file as `schema.sql`;
//! migration runner and concrete queries land in a follow-up.

#![allow(dead_code)]

pub const BASELINE_SCHEMA: &str = include_str!("schema.sql");
