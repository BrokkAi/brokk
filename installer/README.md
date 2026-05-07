# Brokk ACP Component Installer

One-line installers for the standalone ACP support components:
[bifrost](https://github.com/BrokkAi/bifrost) (the auth proxy) and
`brokk-acp` (the Rust ACP server).

These scripts do **not** install the Brokk desktop app. To install the app,
download a platform installer from https://github.com/BrokkAi/brokk/releases.

## Quick install

### Linux / macOS

```sh
curl -fsSL https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.sh | bash
```

### Windows (PowerShell)

```powershell
irm https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.ps1 | iex
```

## Supported platforms

The installers cover the intersection of platforms that **both** bifrost and
brokk-acp publish releases for:

| OS      | Architecture | install.sh | install.ps1 |
|---------|-------------|------------|-------------|
| Linux   | x86_64      | yes        | -           |
| macOS   | aarch64     | yes        | -           |
| Windows | x86_64      | -          | yes         |

Other targets (Linux ARM64, Intel macOS, Windows ARM64) are rejected with a
clear error message; once upstream binaries are published the installers
will be extended.

## What it does

1. Detects OS and architecture.
2. Looks up the latest release of each component on GitHub (`/releases`).
3. Downloads the matching asset over HTTPS.
4. For bifrost, verifies the `.sha256` sidecar published alongside the archive.
5. Extracts (bifrost is shipped as a tarball/zip; brokk-acp ships a single
   binary) and installs both binaries to:
   - **Unix:** `~/.local/bin` (override with `--install-dir`)
   - **Windows:** `%LOCALAPPDATA%\brokk\bin` (override with `-InstallDir`)
6. Adds the install directory to `PATH` if it is not already present.
7. Runs `--version` on both binaries to verify the install succeeded.

The scripts are **idempotent**: re-run them at any time to upgrade to the
newest release.

## Options

### `install.sh`

```text
--install-dir DIR        Install location (default: $HOME/.local/bin)
--bifrost-version VER    Pin bifrost version (e.g. v0.2.0). Default: latest.
--acp-version VER        Pin brokk-acp version (e.g. 0.1.0). Default: latest.
--skip-path              Do not modify shell rc files.
--no-verify              Skip running --version on installed binaries.
-h, --help               Show help.
-V, --version            Print installer version.
```

Equivalent environment variables: `BROKK_INSTALL_DIR`, `BIFROST_VERSION`,
`BROKK_ACP_VERSION`.

To pass arguments through `curl | bash`, use the `bash -s --` form:

```sh
curl -fsSL https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.sh | bash -s -- --install-dir ~/bin
```

### `install.ps1`

```text
-InstallDir <path>       Install location (default: %LOCALAPPDATA%\brokk\bin)
-BifrostVersion <ver>    Pin bifrost version (e.g. v0.2.0). Default: latest.
-AcpVersion <ver>        Pin brokk-acp version (e.g. 0.1.0). Default: latest.
-SkipPath                Do not modify the user's PATH.
-NoVerify                Skip running --version on installed binaries.
-ShowVersion             Print installer version.
```

Equivalent environment variables: `$env:BROKK_INSTALL_DIR`,
`$env:BIFROST_VERSION`, `$env:BROKK_ACP_VERSION`.

To pass arguments to the script you need to download it first, since
`irm | iex` cannot forward arguments:

```powershell
irm https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.ps1 -OutFile install.ps1
.\install.ps1 -InstallDir 'C:\tools\brokk'
```

You can also set env vars before piping:

```powershell
$env:BROKK_INSTALL_DIR = 'C:\tools\brokk'
irm https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.ps1 | iex
```

## Windows execution policy

The `irm | iex` form runs the script in the current PowerShell session and is
not subject to script-file execution policy. If you instead save the script
and run it as a file (`.\install.ps1`) on a system with a restrictive policy,
you may need:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

## Security notes

- Both scripts download exclusively over HTTPS. `install.sh` enforces
  `--proto '=https' --tlsv1.2` on curl; `install.ps1` raises the .NET TLS
  protocol baseline to TLS 1.2.
- Bifrost releases ship a `.sha256` sidecar that the installer verifies after
  download. brokk-acp does not currently publish checksums, so for that
  binary the installers rely on TLS for transport integrity.
- The installers only modify the user's own files: a per-user install
  directory and the user-scoped shell rc file (Unix) or user PATH
  environment variable (Windows). They never require sudo / admin and never
  touch system-wide configuration.

## Manual install

If you would rather not run a script, the asset URLs are stable:

- bifrost: `https://github.com/BrokkAi/bifrost/releases/latest`
- brokk-acp: `https://github.com/BrokkAi/brokk/releases` (filter for tags
  prefixed with `brokk-acp-rust-`)
