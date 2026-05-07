<#
.SYNOPSIS
    Installer for bifrost (auth proxy) and brokk-acp (Rust ACP server) on Windows.

.DESCRIPTION
    Downloads release binaries from GitHub for the BrokkAi/bifrost and
    BrokkAi/brokk repositories, installs them under a per-user directory, and
    ensures that directory is on the user's PATH. Idempotent: safe to re-run
    for upgrades.

.PARAMETER InstallDir
    Install location. Default: $env:LOCALAPPDATA\brokk\bin
    Also configurable via $env:BROKK_INSTALL_DIR.

.PARAMETER BifrostVersion
    Pin bifrost version (e.g. v0.2.0). Default: latest.
    Also configurable via $env:BIFROST_VERSION.

.PARAMETER AcpVersion
    Pin brokk-acp version (e.g. 0.1.0). Default: latest.
    Also configurable via $env:BROKK_ACP_VERSION.

.PARAMETER SkipPath
    Do not modify the user's PATH.

.PARAMETER NoVerify
    Skip running --version on installed binaries.

.PARAMETER ShowVersion
    Print the installer version and exit.

.EXAMPLE
    irm https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.ps1 | iex

.EXAMPLE
    # With options, download then run:
    irm https://raw.githubusercontent.com/BrokkAi/brokk/master/installer/install.ps1 -OutFile install.ps1
    .\install.ps1 -InstallDir C:\tools\brokk

.NOTES
    Supported platform: Windows x86_64 (AMD64). brokk-acp does not currently
    publish ARM64 Windows builds.
#>

[CmdletBinding()]
param(
    [string]$InstallDir,
    [string]$BifrostVersion,
    [string]$AcpVersion,
    [switch]$SkipPath,
    [switch]$NoVerify,
    [switch]$ShowVersion
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$InstallerVersion = '0.1.0'
$BifrostRepo = 'BrokkAi/bifrost'
$BrokkRepo = 'BrokkAi/brokk'
$AcpTagPrefix = 'brokk-acp-rust-'

if ($ShowVersion) {
    Write-Output $InstallerVersion
    exit 0
}

# Force TLS 1.2+ for older PowerShell defaults (PS 5.1 may default to TLS 1.0).
try {
    [Net.ServicePointManager]::SecurityProtocol = `
        [Net.ServicePointManager]::SecurityProtocol -bor `
        [Net.SecurityProtocolType]::Tls12
} catch {
    Write-Verbose "Could not set TLS 1.2: $_"
}

# ---- helpers --------------------------------------------------------------

function Write-Info($msg)  { Write-Host $msg }
function Write-Step($msg)  { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Warn2($msg) { Write-Warning $msg }
function Die($msg) {
    # Avoid Write-Error here: with $ErrorActionPreference='Stop' it throws and
    # produces a noisy stack trace under `irm | iex`. Plain colored text + exit
    # gives a clean "error: ..." line.
    Write-Host "error: $msg" -ForegroundColor Red
    exit 1
}

# ---- platform detection --------------------------------------------------

function Get-Architecture {
    # Prefer the .NET RuntimeInformation when available (PS Core / 5.1 with
    # recent .NET). Falls back to PROCESSOR_ARCHITECTURE/PROCESSOR_ARCHITEW6432
    # so a 32-bit PowerShell on a 64-bit OS still reports AMD64.
    try {
        $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
        switch ($arch) {
            'X64'   { return 'x86_64' }
            'Arm64' { return 'aarch64' }
            'X86'   { return 'x86' }
            default { return $arch.ToString().ToLower() }
        }
    } catch {
        $envArch = $env:PROCESSOR_ARCHITEW6432
        if (-not $envArch) { $envArch = $env:PROCESSOR_ARCHITECTURE }
        switch ($envArch) {
            'AMD64' { return 'x86_64' }
            'ARM64' { return 'aarch64' }
            'x86'   { return 'x86' }
            default { return $envArch.ToLower() }
        }
    }
}

function Test-SupportedPlatform($arch) {
    if ($arch -ne 'x86_64') {
        Die "Unsupported Windows architecture: $arch. brokk-acp publishes only x86_64 (AMD64) Windows builds."
    }
}

# ---- HTTP -----------------------------------------------------------------

function Invoke-Download {
    param(
        [Parameter(Mandatory)] [string] $Url,
        [Parameter(Mandatory)] [string] $Destination
    )
    # -UseBasicParsing is harmless on PS7 and required on PS5.1 to avoid the
    # IE-engine dependency.
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing -MaximumRedirection 5
}

function Invoke-DownloadJson {
    param([Parameter(Mandatory)] [string] $Url)
    $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -MaximumRedirection 5
    return ($resp.Content | ConvertFrom-Json)
}

# ---- release lookups -----------------------------------------------------

function Get-LatestTag($repo) {
    $url = "https://api.github.com/repos/$repo/releases/latest"
    try {
        $rel = Invoke-DownloadJson $url
    } catch {
        Die "Could not fetch latest release for ${repo}: $_"
    }
    if (-not $rel.tag_name) {
        Die "Could not parse tag_name from $repo latest release."
    }
    return $rel.tag_name
}

function Get-LatestPrefixedTag($repo, $prefix) {
    $url = "https://api.github.com/repos/$repo/releases?per_page=100"
    try {
        $releases = Invoke-DownloadJson $url
    } catch {
        Die "Could not fetch releases for ${repo}: $_"
    }
    foreach ($rel in $releases) {
        if ($rel.tag_name -and $rel.tag_name.StartsWith($prefix)) {
            return $rel.tag_name
        }
    }
    Die "No release found for $repo with tag prefix '$prefix'. Pin a version with -AcpVersion or `$env:BROKK_ACP_VERSION."
}

function Get-ReleaseByTag($repo, $tag) {
    $url = "https://api.github.com/repos/$repo/releases/tags/$tag"
    try {
        return Invoke-DownloadJson $url
    } catch {
        Die "Could not fetch release $tag for ${repo}: $_"
    }
}

function Find-AssetUrl {
    param(
        [Parameter(Mandatory)] $Release,
        [Parameter(Mandatory)] [string] $AssetName,
        [switch] $Optional
    )
    $asset = $Release.assets | Where-Object { $_.name -eq $AssetName } | Select-Object -First 1
    if (-not $asset) {
        if ($Optional) { return $null }
        Die "Release $($Release.tag_name) is missing expected asset: $AssetName"
    }
    if (-not $asset.browser_download_url) {
        if ($Optional) { return $null }
        Die "Release $($Release.tag_name) asset $AssetName has no browser_download_url."
    }
    return $asset.browser_download_url
}

# ---- install helpers -----------------------------------------------------

function Test-Sha256 {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Expected
    )
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower()
    $expectedLower = $Expected.ToLower()
    if ($actual -ne $expectedLower) {
        Die "Checksum mismatch for $(Split-Path $Path -Leaf) (expected $expectedLower, got $actual)"
    }
}

function Install-File {
    param(
        [Parameter(Mandatory)] [string] $Source,
        [Parameter(Mandatory)] [string] $Destination
    )
    # Atomic enough on Windows: Copy-Item then ACLs inherit from parent dir.
    Copy-Item -Path $Source -Destination $Destination -Force
}

# ---- bifrost install ----------------------------------------------------

function Install-Bifrost {
    param(
        [Parameter(Mandatory)] [string] $Arch,
        [Parameter(Mandatory)] [string] $InstallDir,
        [Parameter(Mandatory)] [string] $Version
    )
    if ($Arch -ne 'x86_64') {
        Die "Internal: no bifrost target for Windows $Arch"
    }
    $target = 'x86_64-pc-windows-msvc'
    $archiveName = "bifrost-$Version-$target.zip"
    $release = Get-ReleaseByTag $BifrostRepo $Version
    $url = Find-AssetUrl -Release $release -AssetName $archiveName

    Write-Step "Downloading bifrost $Version ($target)"
    $tmp = New-Item -ItemType Directory -Path (Join-Path ([System.IO.Path]::GetTempPath()) ("brokk-installer-" + [guid]::NewGuid())) -Force
    try {
        $archivePath = Join-Path $tmp.FullName $archiveName
        Invoke-Download -Url $url -Destination $archivePath

        $shaUrl = Find-AssetUrl -Release $release -AssetName "$archiveName.sha256" -Optional
        $shaPath = "$archivePath.sha256"
        $shaOk = $false
        if ($shaUrl) {
            try {
                Invoke-Download -Url $shaUrl -Destination $shaPath
                $shaOk = $true
            } catch {
                Write-Warn2 "Could not download sha256 sidecar for bifrost $Version; skipping checksum verification."
            }
        } else {
            Write-Warn2 "No sha256 sidecar for bifrost $Version; skipping checksum verification."
        }
        if ($shaOk) {
            $expected = (Get-Content -Path $shaPath -Raw).Trim().Split(' ')[0]
            Test-Sha256 -Path $archivePath -Expected $expected
        }

        $extractDir = Join-Path $tmp.FullName 'extract'
        New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
        Expand-Archive -Path $archivePath -DestinationPath $extractDir -Force

        # Tarball/zip layout varies (binary at root vs inside subdir).
        $exe = Get-ChildItem -Path $extractDir -Recurse -Filter 'bifrost.exe' -File | Select-Object -First 1
        if (-not $exe) {
            Die "Could not find 'bifrost.exe' inside $archiveName"
        }
        Install-File -Source $exe.FullName -Destination (Join-Path $InstallDir 'bifrost.exe')
        Write-Info "    installed: $(Join-Path $InstallDir 'bifrost.exe')"
    } finally {
        Remove-Item -Path $tmp.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ---- brokk-acp install --------------------------------------------------

function Install-BrokkAcp {
    param(
        [Parameter(Mandatory)] [string] $Arch,
        [Parameter(Mandatory)] [string] $InstallDir,
        [Parameter(Mandatory)] [string] $Version
    )
    if ($Arch -ne 'x86_64') {
        Die "Internal: no brokk-acp asset for Windows $Arch"
    }
    $assetName = 'brokk-acp-windows-x86_64.exe'
    $release = Get-ReleaseByTag $BrokkRepo "$AcpTagPrefix$Version"
    $url = Find-AssetUrl -Release $release -AssetName $assetName

    Write-Step "Downloading brokk-acp $Version (windows-x86_64)"
    $tmp = New-Item -ItemType Directory -Path (Join-Path ([System.IO.Path]::GetTempPath()) ("brokk-installer-" + [guid]::NewGuid())) -Force
    try {
        $downloaded = Join-Path $tmp.FullName $assetName
        Invoke-Download -Url $url -Destination $downloaded
        Install-File -Source $downloaded -Destination (Join-Path $InstallDir 'brokk-acp.exe')
        Write-Info "    installed: $(Join-Path $InstallDir 'brokk-acp.exe')"
    } finally {
        Remove-Item -Path $tmp.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ---- PATH helper --------------------------------------------------------

function Add-ToUserPath {
    param([Parameter(Mandatory)] [string] $Directory)

    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    if (-not $userPath) { $userPath = '' }

    # Idempotency: case-insensitive substring match against existing entries.
    $entries = $userPath.Split(';') | Where-Object { $_ -ne '' }
    foreach ($entry in $entries) {
        if ([string]::Equals($entry.TrimEnd('\'), $Directory.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
            Write-Info "PATH already contains $Directory"
            return
        }
    }

    $newPath = if ($userPath) { "$userPath;$Directory" } else { $Directory }
    [Environment]::SetEnvironmentVariable('PATH', $newPath, 'User')

    # Update the current process so verification/--version calls see the dir.
    if ($env:PATH -notlike "*$Directory*") {
        $env:PATH = "$env:PATH;$Directory"
    }

    Write-Info "Added $Directory to user PATH (open a new terminal for it to take effect)."
}

# ---- main ---------------------------------------------------------------

if (-not $InstallDir) {
    if ($env:BROKK_INSTALL_DIR) {
        $InstallDir = $env:BROKK_INSTALL_DIR
    } else {
        $InstallDir = Join-Path $env:LOCALAPPDATA 'brokk\bin'
    }
}
if (-not $BifrostVersion -and $env:BIFROST_VERSION) { $BifrostVersion = $env:BIFROST_VERSION }
if (-not $AcpVersion -and $env:BROKK_ACP_VERSION)   { $AcpVersion = $env:BROKK_ACP_VERSION }

$arch = Get-Architecture
Test-SupportedPlatform $arch
Write-Info "Detected platform:  windows $arch"

if (-not $BifrostVersion) {
    $BifrostVersion = Get-LatestTag $BifrostRepo
}
if (-not $AcpVersion) {
    $tag = Get-LatestPrefixedTag $BrokkRepo $AcpTagPrefix
    $AcpVersion = $tag.Substring($AcpTagPrefix.Length)
}

Write-Info "bifrost version:    $BifrostVersion"
Write-Info "brokk-acp version:  $AcpVersion"
Write-Info "Install directory:  $InstallDir"
Write-Info ""

if (-not (Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
}

Install-Bifrost  -Arch $arch -InstallDir $InstallDir -Version $BifrostVersion
Install-BrokkAcp -Arch $arch -InstallDir $InstallDir -Version $AcpVersion

if (-not $SkipPath) {
    Add-ToUserPath -Directory $InstallDir
}

if (-not $NoVerify) {
    Write-Info ""
    Write-Step "Verifying installs"
    # Native exes do not throw on non-zero exit unless $PSNativeCommandUseErrorActionPreference
    # is on (PS 7.4+), so check $LASTEXITCODE explicitly. The try/catch covers
    # the launch-failed case (file missing, not executable, etc.).
    try {
        & (Join-Path $InstallDir 'bifrost.exe') --version
        if ($LASTEXITCODE -ne 0) { Write-Warn2 "bifrost --version exited with code $LASTEXITCODE" }
    } catch {
        Write-Warn2 "bifrost --version failed: $_"
    }
    try {
        & (Join-Path $InstallDir 'brokk-acp.exe') --version
        if ($LASTEXITCODE -ne 0) { Write-Warn2 "brokk-acp --version exited with code $LASTEXITCODE" }
    } catch {
        Write-Warn2 "brokk-acp --version failed: $_"
    }
}

Write-Info ""
Write-Info "Done. Binaries installed to: $InstallDir"
