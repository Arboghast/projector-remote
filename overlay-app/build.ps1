# Builds the overlay app with the bare SDK tools (no Gradle): javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Output: ..\tools\ink-overlay.apk
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = Join-Path $root '..\tools\android-sdk'
$bt = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-30\android.jar'
$out = Join-Path $root 'build'
$apk = Join-Path $root '..\tools\ink-overlay.apk'

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

$sources = Get-ChildItem -Recurse "$root\src" -Filter *.java | ForEach-Object FullName
# Lambdas need the JDK's own java.lang.invoke, so android.jar goes on the classpath rather than the
# bootclasspath; d8 desugars the lambdas for Android.
& javac --release 8 -classpath $androidJar -d "$out\classes" -Xlint:-options @sources
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --min-api 26 --lib $androidJar --output "$out\dex" @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $androidJar --manifest "$root\AndroidManifest.xml"
if ($LASTEXITCODE) { throw 'aapt2 failed' }

Push-Location "$out\dex"
& jar uf "$out\unsigned.apk" classes.dex
Pop-Location

& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

# A local signing key; the app is sideloaded, never published.
$keystore = Join-Path $root 'debug.keystore'
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias ink `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Projector Remote' | Out-Null
}
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --out $apk "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
Write-Output "built $apk"
