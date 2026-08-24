<#
  Builds and installs the debug APK without Gradle.

  Why this exists: the project has no committed Gradle wrapper (the jar is a
  binary this tool does not author), and no standalone Gradle distribution
  was present on this machine, only Android Studio's bundled copy, which
  Studio does not expose as a plain gradle.bat.

  This script is a stand-in for that missing wrapper, not a replacement for
  Gradle long-term: it calls the same SDK command-line tools Gradle would
  (aapt2, javac, d8, apksigner, zipalign) directly, in the right order, for
  this specific single-module project. It has no dependency resolution, no
  incremental compilation, and no resource merging beyond one package - it
  will not scale past what this app currently is. Once a real Gradle
  distribution is available, prefer `gradle wrapper && ./gradlew assembleDebug`
  and retire this script.
#>

param([switch]$Install)

$ErrorActionPreference = "Stop"

$sdk       = "C:\Users\swaro\AppData\Local\Android\Sdk"
$buildTools = "$sdk\build-tools\36.0.0"
$platform  = "$sdk\platforms\android-35\android.jar"
$jdk       = "C:\Program Files\Android\Android Studio4\jbr"
$java      = "$jdk\bin\java.exe"
$javac     = "$jdk\bin\javac.exe"
$keytool   = "$jdk\bin\keytool.exe"

$root      = Split-Path -Parent $MyInvocation.MyCommand.Path   # android/
$app       = "$root\app"
$out       = "$app\build-direct"
$assets    = "$app\src\main\assets"
$manifest  = "$app\src\main\AndroidManifest.xml"
$resDir    = "$app\src\main\res"
$srcDir    = "$app\src\main\java"
$apkUnsigned = "$out\app.unsigned.apk"
$apkAligned  = "$out\app.aligned.apk"
$apkFinal    = "$out\app-debug.apk"

foreach ($tool in @($buildTools, $platform, $java, $javac)) {
    if (-not (Test-Path $tool)) { throw "Required tool/path missing: $tool" }
}

# Minutes since a fixed epoch: always increasing, small enough that adb
# install never rejects it as a "downgrade" against a previous direct or
# Gradle build, and stable enough not to blow past Play's versionCode ceiling
# if this script is ever run a very large number of times.
$versionCode = [int](([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) / 60)

Write-Host "== Cleaning build-direct ==" -ForegroundColor Cyan
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\gen","$out\classes","$out\dex" | Out-Null

Write-Host "== Syncing web app into assets ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $assets | Out-Null
Copy-Item "$root\..\index.html" "$assets\index.html" -Force

Write-Host "== Compiling resources (aapt2 compile) ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "$out\compiled-res" | Out-Null
& "$buildTools\aapt2.exe" compile --dir $resDir -o "$out\compiled-res" -v
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== Linking resources + generating R.java (aapt2 link) ==" -ForegroundColor Cyan

# aapt2, unlike Gradle, has no idea that build.gradle's `namespace` supplies
# the package name — it wants a literal package= attribute on <manifest>.
# Adding one to the committed manifest would break the real Gradle build,
# since recent AGP errors when both namespace and a manifest package are
# set. So the attribute is injected into a throwaway copy instead.
$manifestDirect = "$out\AndroidManifest.direct.xml"
(Get-Content $manifest -Raw) `
    -replace '<manifest\s+xmlns:android="([^"]+)">', '<manifest xmlns:android="$1" package="com.wordbeat.tts">' |
    Set-Content -Encoding UTF8 $manifestDirect

$flatFiles = Get-ChildItem "$out\compiled-res\*.flat" | ForEach-Object { $_.FullName }
& "$buildTools\aapt2.exe" link `
    -o $apkUnsigned `
    --manifest $manifestDirect `
    -I $platform `
    --java "$out\gen" `
    --auto-add-overlay `
    --min-sdk-version 26 `
    --target-sdk-version 35 `
    --version-code $versionCode `
    --version-name "1.0-direct-$versionCode" `
    -A $assets `
    @flatFiles
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== Compiling Java sources ==" -ForegroundColor Cyan
$javaFiles = Get-ChildItem -Recurse -Path $srcDir,"$out\gen" -Filter "*.java" | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 17 -target 17 `
    -classpath $platform `
    -d "$out\classes" `
    @javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== Dexing (d8) ==" -ForegroundColor Cyan
$classFiles = Get-ChildItem -Recurse -Path "$out\classes" -Filter "*.class" | ForEach-Object { $_.FullName }
& "$buildTools\d8.bat" --output "$out\dex" --min-api 26 @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== Assembling APK (classes.dex into the linked package) ==" -ForegroundColor Cyan
Copy-Item $apkUnsigned "$out\app.with-dex.apk" -Force
Push-Location "$out\dex"
try {
    & "$java" -cp "$buildTools\lib\apksigner.jar" 2>$null | Out-Null   # no-op probe, ignore
} catch {}
Pop-Location

# aapt2 does not embed the dex itself; add it with the JDK's jar tool, which
# manipulates the zip without touching aapt2's existing entries.
$jar = "$jdk\bin\jar.exe"
Push-Location "$out\dex"
& $jar uf "$out\app.with-dex.apk" classes.dex
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "embedding classes.dex failed" }
Pop-Location

Write-Host "== Aligning (zipalign) ==" -ForegroundColor Cyan
& "$buildTools\zipalign.exe" -f -p 4 "$out\app.with-dex.apk" $apkAligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== Signing with the debug keystore (apksigner) ==" -ForegroundColor Cyan
$debugKeystore = "$env:USERPROFILE\.android\debug.keystore"
if (-not (Test-Path $debugKeystore)) {
    Write-Host "  no debug.keystore found; generating one" -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.android" | Out-Null
    & $keytool -genkeypair -v -keystore $debugKeystore -storepass android -alias androiddebugkey `
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}
& "$buildTools\apksigner.bat" sign `
    --ks $debugKeystore --ks-pass pass:android --key-pass pass:android `
    --out $apkFinal $apkAligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "== Built: $apkFinal ==" -ForegroundColor Green

if ($Install) {
    $adb = "$sdk\platform-tools\adb.exe"
    Write-Host "== Installing on connected device ==" -ForegroundColor Cyan
    & $adb install -r $apkFinal
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}
