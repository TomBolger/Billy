param(
    [string]$ApkPath = "companion-android\app\build\outputs\apk\debug\app-debug.apk",
    [string]$PackageName = "com.tombo.billyassistant.companion"
)

$ErrorActionPreference = "Stop"

function Run-Adb {
    & adb @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: adb $($args -join ' ')"
    }
}

$resolvedApk = Resolve-Path -LiteralPath $ApkPath
$backupRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("billy-companion-backup-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null

$prefs = @(
    "billy_companion_settings.xml",
    "billy_google_auth.xml"
)

Write-Host "Backing up Billy Companion preferences to $backupRoot"
foreach ($pref in $prefs) {
    $remote = "/data/data/$PackageName/shared_prefs/$pref"
    $local = Join-Path $backupRoot $pref
    & adb exec-out run-as $PackageName cat $remote > $local
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $local) -or (Get-Item -LiteralPath $local).Length -eq 0) {
        Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
        Write-Host "Skipped $pref; it was not present or could not be read."
    } else {
        Write-Host "Backed up $pref"
    }
}

Write-Host "Uninstalling old Billy Companion package."
Run-Adb uninstall $PackageName

Write-Host "Installing $resolvedApk"
Run-Adb install $resolvedApk

foreach ($pref in $prefs) {
    $local = Join-Path $backupRoot $pref
    if (-not (Test-Path -LiteralPath $local)) {
        continue
    }
    $tmp = "/data/local/tmp/$pref"
    Run-Adb push $local $tmp
    Run-Adb shell run-as $PackageName sh -c "mkdir -p /data/data/$PackageName/shared_prefs && cat $tmp > /data/data/$PackageName/shared_prefs/$pref && chmod 660 /data/data/$PackageName/shared_prefs/$pref"
    Run-Adb shell rm $tmp
    Write-Host "Restored $pref"
}

Write-Host "Launching Billy Companion."
Run-Adb shell monkey -p $PackageName 1
Write-Host "Done. Local preference backup remains at $backupRoot"
