$ErrorActionPreference = "Stop"

function Resolve-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    throw "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
}

function Resolve-JdkBin {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin')
    }
    $javacCmd = Get-Command 'javac.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javacCmd) { return (Split-Path -Parent $javacCmd.Source) }
    $adoptium = Get-ChildItem -Path 'C:\Program Files\Eclipse Adoptium\jdk-*\bin\javac.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($adoptium) { return $adoptium.DirectoryName }
    throw "JDK not found. Set JAVA_HOME or add javac.exe to PATH."
}

$sdk = Resolve-AndroidSdk
$buildToolsDir = Get-ChildItem -Path "$sdk\build-tools" -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'aapt2.exe') } |
    Sort-Object { try { [version]($_.Name -replace '-.*$', '') } catch { [version]'0.0' } } -Descending |
    Select-Object -First 1
if (-not $buildToolsDir) { throw "No Android build-tools found under $sdk\build-tools" }
$buildTools = $buildToolsDir.FullName
$androidJar = "$sdk\platforms\android-34\android.jar"
if (-not (Test-Path -LiteralPath $androidJar)) {
    $platformDir = Get-ChildItem -Path "$sdk\platforms\android-*" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $platformDir) { throw "No Android platform android.jar found under $sdk\platforms" }
    $androidJar = Join-Path $platformDir.FullName 'android.jar'
}
$jdkBin = Resolve-JdkBin

$androidDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appDir = Split-Path -Parent $androidDir
$buildDir = "$androidDir\build"

if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Path "$buildDir\compiled_res", "$buildDir\gen", "$buildDir\classes", "$buildDir\dex" -Force | Out-Null

Write-Host "1. Compiling resources with aapt2..."
& "$buildTools\aapt2.exe" compile --dir "$androidDir\res" -o "$buildDir\compiled_res\res.zip"

Write-Host "2. Linking resources & AndroidManifest.xml (minSdk=31 Android 12, targetSdk=34)..."
& "$buildTools\aapt2.exe" link `
    -o "$buildDir\unsigned.apk" `
    -I "$androidJar" `
    --manifest "$androidDir\AndroidManifest.xml" `
    --java "$buildDir\gen" `
    "$buildDir\compiled_res\res.zip"

Write-Host "3. Compiling Java sources for Android 12+ ART..."
$javaFiles = Get-ChildItem -Path "$androidDir\src", "$buildDir\gen" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& "$jdkBin\javac.exe" --release 8 -encoding UTF-8 -cp "$androidJar" -d "$buildDir\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed ($LASTEXITCODE)" }

Write-Host "4. Converting classes to Dalvik Executable (classes.dex) with d8..."
# Pass one archive to D8 to stay within the Windows command-line length limit.
& "$jdkBin\jar.exe" -cf "$buildDir\classes.jar" -C "$buildDir\classes" .
if ($LASTEXITCODE -ne 0) { throw "Packaging Java classes failed ($LASTEXITCODE)" }
& "$buildTools\d8.bat" --min-api 31 --lib "$androidJar" --output "$buildDir\dex" "$buildDir\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "D8 failed ($LASTEXITCODE)" }

Write-Host "5. Packaging classes.dex into APK..."
Copy-Item "$buildDir\unsigned.apk" "$buildDir\unaligned.apk"
& "$jdkBin\jar.exe" -uf "$buildDir\unaligned.apk" -C "$buildDir\dex" classes.dex

Write-Host "6. Aligning APK with zipalign..."
$alignedApk = "$buildDir\aligned.apk"
& "$buildTools\zipalign.exe" -f -p 4 "$buildDir\unaligned.apk" $alignedApk

Write-Host "7. Signing APK with apksigner (v2/v3 Android 12+ signature scheme)..."
$keystore = "$env:USERPROFILE\.android\debug.keystore"
if (-not (Test-Path $keystore)) {
    $ErrorActionPreference = "Continue"
    & "$jdkBin\keytool.exe" -genkeypair -keystore $keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" *> $null
    $ErrorActionPreference = "Stop"
}
$finalApk = "$appDir\Niob-Android12-MaterialYou.apk"
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $finalApk $alignedApk

Remove-Item -Recurse -Force $buildDir
Write-Host "SUCCESS: Built signed Android 12 APK at $finalApk"
