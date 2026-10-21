# Free My TV: build and install on a Google TV / Android TV from Windows, without WSL.
# Does what build.sh does (aapt2 -> javac -> d8 -> zipalign -> apksigner) with the Windows tools, then installs over adb.
# Run:  powershell -ExecutionPolicy Bypass -File install-windows.ps1 -Ip <TV's IP address>
param([Parameter(Mandatory = $true)][string]$Ip)
$ErrorActionPreference = "Continue"   # native tools write warnings to stderr; failures are checked by exit code
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Root = $PSScriptRoot
Set-Location $Root

function Step($t) { Write-Host ""; Write-Host "==> $t" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" } }

# --- 1. JDK 17+
Step "Looking for Java (JDK 17+)"
function Find-Jdk {
  $roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
             "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")
  if ($env:JAVA_HOME) { $roots = @((Split-Path $env:JAVA_HOME)) + $roots }
  foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    foreach ($d in Get-ChildItem $r -Directory | Sort-Object Name -Descending) {
      if ((Test-Path "$($d.FullName)\bin\javac.exe") -and ($d.Name -match '(\d+)') -and ([int]$Matches[1] -ge 17)) { return $d.FullName }
    }
  }
  return $null
}
$Jdk = Find-Jdk
if (-not $Jdk) {
  Write-Host "No JDK found, installing Temurin JDK 17 with winget..."
  winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-source-agreements --accept-package-agreements
  $Jdk = Find-Jdk
  if (-not $Jdk) { throw "Still no JDK 17 after installing. Install it from https://adoptium.net and run this script again." }
}
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"
Write-Host "JDK: $Jdk"

# --- 2. Android SDK (command-line tools, build-tools, platform, adb)
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$SdkManager = "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
# The newest installed build-tools and platform, as build.sh picks them.
function Newest($dir, $pattern) {
  Get-ChildItem $dir -Directory -ErrorAction SilentlyContinue | Where-Object Name -match $pattern |
    Sort-Object { [version](($_.Name -replace '^android-', '') -replace '^(\d+)$', '$1.0') } -Descending | Select-Object -First 1
}
$bt = Newest "$Sdk\build-tools" '^\d+\.\d+\.\d+$'
$platform = Newest "$Sdk\platforms" '^android-\d+(\.\d+)?$'
$Adb = "$Sdk\platform-tools\adb.exe"
# d8 8.2 (build-tools 34) crashes on some of our classes (DreamView$1), so a fresh SDK gets build-tools 36.
if (-not ($bt -and [version]$bt.Name -ge [version]"35.0.0" -and $platform -and (Test-Path $Adb))) {
  if (-not (Test-Path $SdkManager)) {
    Step "Downloading the Android command-line tools"
    New-Item -ItemType Directory -Force "$Sdk\cmdline-tools" | Out-Null
    $zip = "$env:TEMP\cmdline-tools.zip"
    Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip -ErrorAction Stop
    $tmp = "$env:TEMP\cmdline-tools-unzip"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip $tmp -ErrorAction Stop
    Move-Item "$tmp\cmdline-tools" "$Sdk\cmdline-tools\latest"
  }
  Step "Installing build-tools 36, platform android-34 and adb (a couple of minutes)"
  ("y`n" * 20) | & $SdkManager --sdk_root="$Sdk" --licenses | Out-Null
  & $SdkManager --sdk_root="$Sdk" "build-tools;36.0.0" "platforms;android-34" "platform-tools"
  Check "sdkmanager"
  $bt = Newest "$Sdk\build-tools" '^\d+\.\d+\.\d+$'
  $platform = Newest "$Sdk\platforms" '^android-\d+(\.\d+)?$'
}
$BT = $bt.FullName
$AJ = "$($platform.FullName)\android.jar"
Write-Host "Android SDK: $Sdk (build-tools $($bt.Name), $($platform.Name))"

# --- 3. Settings: config.properties -> Config.java
Step "Reading config.properties"
if (-not (Test-Path config.properties)) {
  Copy-Item config.example.properties config.properties
  Write-Host "Created config.properties: add your TMDB key and your city there (optional), then run this again."
}
$props = @{}
foreach ($line in Get-Content config.properties -Encoding UTF8) {
  if ($line -match '^\s*([A-Za-z_]+)\s*=(.*)$') { $props[$Matches[1]] = $Matches[2].Trim().Replace('"', '').Replace('\', '') }
}
function Prop($k, $def) { if ($props.ContainsKey($k) -and $props[$k] -ne '') { $props[$k] } else { $def } }
$lat = Prop latitude "40.7128"; $lon = Prop longitude "-74.0060"
foreach ($n in @($lat, $lon)) { if ($n -notmatch '^-?\d+(\.\d+)?$') { throw "config.properties: latitude and longitude must be numbers" } }
$assist = if ((Prop disable_assistant "false") -eq "true") { "true" } else { "false" }
$crypto = if ((Prop show_crypto "true") -eq "true") { "true" } else { "false" }
Write-Host ("City: {0} ({1}, {2}), region {3}, TMDB key: {4}" -f (Prop city ''), $lat, $lon, (Prop tmdb_region US), $(if ((Prop tmdb_key '') -ne '') { 'yes' } else { 'no (no picks)' }))

if (Test-Path out) { Remove-Item out -Recurse -Force }
New-Item -ItemType Directory -Force out\gen\io\freemytv, out\classes | Out-Null
$config = @"
package io.freemytv;

/** Generated by install-windows.ps1 from config.properties: edit that file, not this one. */
final class Config {
    private Config() {}

    static final String TMDB_KEY = "$(Prop tmdb_key '')";
    static final String TMDB_REGION = "$(Prop tmdb_region 'US')";
    static final String LATITUDE = "$lat";
    static final String LONGITUDE = "$lon";
    static final String CITY = "$(Prop city '')";
    static final String LANGUAGE = "$(Prop language 'auto')";
    static final String CURRENCY = "$(Prop currency 'USD')";
    static final String TEMPERATURE = "$(Prop temperature 'auto')";
    static final boolean DISABLE_ASSISTANT = $assist;
    static final boolean SHOW_CRYPTO = $crypto;
}
"@
[IO.File]::WriteAllText("$Root\out\gen\io\freemytv\Config.java", $config, (New-Object Text.UTF8Encoding $false))

# --- 4. Build
Step "Building FreeMyTV.apk"
& "$BT\aapt2.exe" compile --dir app\res -o out\res.zip; Check "aapt2 compile"
& "$BT\aapt2.exe" link -o out\base.apk -I $AJ --manifest app\AndroidManifest.xml --java out\gen out\res.zip `
  --min-sdk-version 28 --target-sdk-version 33 -A app\assets -0 ttf; Check "aapt2 link"

$sources = Get-ChildItem app\src, out\gen -Recurse -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines("$Root\out\sources.txt", $sources)
& "$Jdk\bin\javac.exe" -nowarn "-Xlint:-options" -source 8 -target 8 -bootclasspath "$AJ;$BT\core-lambda-stubs.jar" `
  -classpath $AJ -encoding UTF-8 -d out\classes "@out\sources.txt"; Check "javac"
& "$Jdk\bin\jar.exe" cfM out\classes.jar -C out\classes .; Check "jar"
& "$BT\d8.bat" --release --min-api 28 --lib $AJ --output out out\classes.jar; Check "d8"

# Windows aapt2 writes asset paths with "\" (Android can't find them): rewrite them with "/" and add classes.dex.
@'
import java.nio.file.*;
import java.util.zip.*;

class ApkFix {
    public static void main(String[] a) throws Exception {
        Path apk = Paths.get(a[0]), tmp = Paths.get(a[0] + ".tmp");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(apk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                ZipEntry n = new ZipEntry(e.getName().replace('\\', '/'));
                if (e.getMethod() == ZipEntry.STORED) {
                    n.setMethod(ZipEntry.STORED); n.setSize(data.length); n.setCompressedSize(data.length);
                    CRC32 crc = new CRC32(); crc.update(data); n.setCrc(crc.getValue());
                }
                out.putNextEntry(n); out.write(data); out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("classes.dex"));
            out.write(Files.readAllBytes(Paths.get(a[1])));
            out.closeEntry();
        }
        Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
    }
}
'@ | Set-Content out\ApkFix.java -Encoding ASCII
& "$Jdk\bin\java.exe" out\ApkFix.java out\base.apk out\classes.dex; Check "ApkFix"
& "$BT\zipalign.exe" -f -p 4 out\base.apk out\aligned.apk; Check "zipalign"

# Your own signing key, made once: updates need the same key, so keep freemytv.keystore.
if (-not (Test-Path freemytv.keystore)) {
  & "$Jdk\bin\keytool.exe" -genkeypair -keystore freemytv.keystore -alias freemytv -keyalg RSA -keysize 2048 -validity 20000 `
    -storepass freemytv -keypass freemytv -dname "CN=Free My TV" 2>$null | Out-Null; Check "keytool"
}
& "$BT\apksigner.bat" sign --ks freemytv.keystore --ks-pass pass:freemytv --key-pass pass:freemytv --out FreeMyTV.apk out\aligned.apk
Check "apksigner"
Write-Host ("Built FreeMyTV.apk ({0:N1} MB)" -f ((Get-Item FreeMyTV.apk).Length / 1MB)) -ForegroundColor Green

# --- 5. Install on the TV
Step "Connecting to the TV at $Ip"
& $Adb start-server | Out-Null
& $Adb connect "$($Ip):5555"
Write-Host ""
Write-Host "The TV asks: Allow USB debugging?" -ForegroundColor Yellow
Write-Host "Tick Always allow from this computer and press OK. Waiting up to 2 minutes..." -ForegroundColor Yellow
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  $state = (& $Adb -s "$($Ip):5555" get-state 2>$null)
  if ($state -eq "device") { $ok = $true; break }
  if ($i % 10 -eq 9) { & $Adb connect "$($Ip):5555" | Out-Null }
  Start-Sleep 2
}
if (-not $ok) { throw "The TV didn't answer. Check that USB debugging is on and that the TV and this computer are on the same network, then run this again." }

Step "Installing Free My TV"
& $Adb -s "$($Ip):5555" install -r FreeMyTV.apk; Check "adb install"
$brand = ((& $Adb -s "$($Ip):5555" shell getprop ro.product.brand) | Out-String).Trim().ToLower()
Write-Host "TV brand: $brand"
# TCL's TclAppBoot blocks background jobs by default, so the switch (and the watchdog) would never run.
if ($brand -match "tcl") {
  & $Adb -s "$($Ip):5555" shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
}
Write-Host ""
Write-Host "Done: Free My TV is installed. On the TV: Free My TV > Home Screen > Free My TV." -ForegroundColor Green
