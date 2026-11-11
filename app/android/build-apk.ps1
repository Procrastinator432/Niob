$ErrorActionPreference = "Stop"

$sdk = "C:\Users\brand\AppData\Local\Android\Sdk"
$buildTools = "$sdk\build-tools\37.0.0"
$androidJar = "$sdk\platforms\android-34\android.jar"
$jdkBin = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot\bin"

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

Write-Host "4. Converting classes to Dalvik Executable (classes.dex) with d8..."
$classFiles = Get-ChildItem -Path "$buildDir\classes" -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& "$buildTools\d8.bat" --min-api 31 --lib "$androidJar" --output "$buildDir\dex" $classFiles

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
$legacyApk = "$appDir\JadeScientificCalculator-Android12-MaterialYou.apk"
& "$buildTools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $finalApk $alignedApk
Copy-Item -Path $finalApk -Destination $legacyApk -Force
if (Test-Path "$finalApk.idsig") {
    Copy-Item -Path "$finalApk.idsig" -Destination "$legacyApk.idsig" -Force
}

Remove-Item -Recurse -Force $buildDir
Write-Host "SUCCESS: Built signed Android 12 APK at $finalApk (and $legacyApk)"
