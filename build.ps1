[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WorkRoot,
    [string]$SigningKey = $env:WATCH7_SIGNING_KEY,
    [string]$SigningPassword = $env:WATCH7_SIGNING_PASSWORD
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
[IO.Directory]::CreateDirectory($WorkRoot) | Out-Null
$env:TEMP = $WorkRoot
$env:TMP = $WorkRoot
$env:TMPDIR = $WorkRoot
$javaHome = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source -Parent) -Parent
$env:JAVA_HOME = $javaHome

function Run-Native([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe exited with $LASTEXITCODE" }
}
function Fetch([string]$Name, [string]$Url, [string]$Hash) {
    $path = Join-Path $WorkRoot $Name
    if (-not (Test-Path -LiteralPath $path)) { Invoke-WebRequest -Uri $Url -OutFile $path }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $Hash) {
        throw "Checksum mismatch: $Name"
    }
    return $path
}
$platformZip = Fetch 'platform-36_r02.zip' 'https://dl.google.com/android/repository/platform-36_r02.zip' '37607369A28C5B640B3A7998868D45898EBCB777565A0E85F9ACF36F29631D2E'
$toolsZip = Fetch 'build-tools_r36_windows.zip' 'https://dl.google.com/android/repository/build-tools_r36_windows.zip' 'AA1095CB14D83E483818A748A2C06FAAEB8E601561B06A356A119A1B2CA280D3'
$apiAar = Fetch 'api-101.0.1.aar' 'https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar' '31EF4BD9D8E783354C0706EC29E5BD421011E73D20DBB01E08E9CF2658BA6047'
$platformDir = Join-Path $WorkRoot 'sdk-platform'
$toolsDir = Join-Path $WorkRoot 'sdk-tools'
$apiDir = Join-Path $WorkRoot 'api'
if (-not (Test-Path -LiteralPath (Join-Path $platformDir 'android-36\android.jar'))) {
    [IO.Compression.ZipFile]::ExtractToDirectory($platformZip, $platformDir, $true)
}
if (-not (Test-Path -LiteralPath (Join-Path $toolsDir 'android-16\aapt2.exe'))) {
    [IO.Compression.ZipFile]::ExtractToDirectory($toolsZip, $toolsDir, $true)
}
if (-not (Test-Path -LiteralPath (Join-Path $apiDir 'classes.jar'))) {
    [IO.Compression.ZipFile]::ExtractToDirectory($apiAar, $apiDir, $true)
}
$androidJar = Join-Path $platformDir 'android-36\android.jar'
$tools = Join-Path $toolsDir 'android-16'
$apiJar = Join-Path $apiDir 'classes.jar'
$buildDir = Join-Path $WorkRoot ('build-' + (Get-Date -Format 'HHmmssfff'))
$classes = Join-Path $buildDir 'classes'
$dex = Join-Path $buildDir 'dex'
$artifacts = Join-Path $WorkRoot 'artifacts'
$signing = Join-Path $WorkRoot 'signing'
foreach ($path in @($classes, $dex, $artifacts, $signing)) { [IO.Directory]::CreateDirectory($path) | Out-Null }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse -File | ForEach-Object FullName)
$compileArgs = @('-encoding', 'UTF-8', '--release', '17', '-classpath', "$androidJar;$apiJar", '-d', $classes) + $sources
Run-Native (Join-Path $javaHome 'bin\javac.exe') $compileArgs
$classFiles = @(Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse -File | ForEach-Object FullName)
$dexArgs = @('--release', '--min-api', '35', '--lib', $androidJar, '--classpath', $apiJar, '--output', $dex) + $classFiles
Run-Native (Join-Path $tools 'd8.bat') $dexArgs
$unsigned = Join-Path $buildDir 'unsigned.apk'
$resources = Join-Path $buildDir 'resources.zip'
Run-Native (Join-Path $tools 'aapt2.exe') @('compile', '--dir', (Join-Path $PSScriptRoot 'res'), '-o', $resources)
Run-Native (Join-Path $tools 'aapt2.exe') @('link', '-o', $unsigned, '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), '-I', $androidJar, $resources)
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dex 'classes.dex'), 'classes.dex') | Out-Null
    $metadata = Join-Path $PSScriptRoot 'metadata'
    foreach ($file in Get-ChildItem -LiteralPath $metadata -Recurse -File) {
        $entry = [IO.Path]::GetRelativePath($metadata, $file.FullName).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entry) | Out-Null
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $buildDir 'aligned.apk'
Run-Native (Join-Path $tools 'zipalign.exe') @('-f', '-p', '4', $unsigned, $aligned)
$externalKey = -not [string]::IsNullOrWhiteSpace($SigningKey)
$key = if ($externalKey) { [IO.Path]::GetFullPath($SigningKey) } else { Join-Path $signing 'watch7-local.p12' }
if ($externalKey -and (-not (Test-Path -LiteralPath $key) -or [string]::IsNullOrWhiteSpace($SigningPassword))) {
    throw 'External signing requires an existing key and WATCH7_SIGNING_PASSWORD.'
}
$env:WATCH7_BUILD_KEY_PASSWORD = if ([string]::IsNullOrWhiteSpace($SigningPassword)) { 'android' } else { $SigningPassword }
if (-not (Test-Path -LiteralPath $key)) {
    Run-Native (Join-Path $javaHome 'bin\keytool.exe') @('-genkeypair', '-keystore', $key, '-storetype', 'PKCS12', '-storepass:env', 'WATCH7_BUILD_KEY_PASSWORD', '-keypass:env', 'WATCH7_BUILD_KEY_PASSWORD', '-alias', 'watch7-local', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-dname', 'CN=Local Watch7 Guard')
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml') -Raw
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
if ($version -notmatch '^[0-9A-Za-z._-]+$') { throw 'Invalid manifest versionName.' }
$apk = Join-Path $artifacts "watch7-guard-$version.apk"
Run-Native (Join-Path $tools 'apksigner.bat') @('sign', '--ks', $key, '--ks-key-alias', 'watch7-local', '--ks-pass', 'env:WATCH7_BUILD_KEY_PASSWORD', '--key-pass', 'env:WATCH7_BUILD_KEY_PASSWORD', '--out', $apk, $aligned)
Run-Native (Join-Path $tools 'apksigner.bat') @('verify', '--verbose', '--print-certs', $apk)
$hash = Get-FileHash -LiteralPath $apk -Algorithm SHA256
[IO.File]::WriteAllText((Join-Path $artifacts 'SHA256SUMS.txt'), $hash.Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($apk) + "`n", [Text.UTF8Encoding]::new($false))
Remove-Item Env:WATCH7_BUILD_KEY_PASSWORD
Write-Output "APK: $apk"
Write-Output "SHA256: $($hash.Hash)"