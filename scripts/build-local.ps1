param([string]$JavaHome = "$PSScriptRoot\..\.local\tools\jdk-17", [string]$Gradle = "$PSScriptRoot\..\.local\tools\gradle-8.9\bin\gradle.bat", [switch]$Release)
$ErrorActionPreference='Stop'
$env:JAVA_HOME=[IO.Path]::GetFullPath($JavaHome)
$env:GRADLE_USER_HOME=[IO.Path]::GetFullPath("$PSScriptRoot\..\.local\gradle-cache")
$env:ANDROID_HOME=[IO.Path]::GetFullPath("$PSScriptRoot\..\.local\android-sdk")
$sdkProperty=$env:ANDROID_HOME.Replace('\','/')
$sdkProperty=-join ($sdkProperty.ToCharArray() | ForEach-Object { if([int]$_ -gt 127){'\u{0:x4}' -f [int]$_}else{[string]$_} })
[IO.File]::WriteAllText("$PSScriptRoot\..\local.properties", "sdk.dir=$sdkProperty`n",[Text.Encoding]::ASCII)
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
if($Release){
    $privatePath=[IO.Path]::GetFullPath("$PSScriptRoot\..\private")
    New-Item -ItemType Directory -Force $privatePath | Out-Null
    $passwordPath=Join-Path $privatePath 'signing-password.txt'
    if(!(Test-Path $passwordPath)){
        $randomBytes=New-Object byte[] 32
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($randomBytes)
        [IO.File]::WriteAllText($passwordPath,[Convert]::ToBase64String($randomBytes))
    }
    $env:FUGUANG_SIGNING_PASSWORD=[IO.File]::ReadAllText($passwordPath)
    $env:FUGUANG_KEYSTORE=Join-Path $privatePath 'fuguang-release.jks'
    if(!(Test-Path $env:FUGUANG_KEYSTORE)){
        & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $env:FUGUANG_KEYSTORE -storepass:env FUGUANG_SIGNING_PASSWORD -keypass:env FUGUANG_SIGNING_PASSWORD -alias fuguang -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Fuguang, O=Fuguang, C=CN'
        if($LASTEXITCODE -ne 0){throw 'Signing key creation failed'}
    }
    & $Gradle --no-daemon assembleRelease lintRelease
}else { & $Gradle --no-daemon assembleDebug lintDebug }
if($LASTEXITCODE -ne 0){throw 'Android build failed'}
New-Item -ItemType Directory -Force "$PSScriptRoot\..\dist" | Out-Null
if($Release){Copy-Item "$PSScriptRoot\..\app\build\outputs\apk\release\app-release.apk" "$PSScriptRoot\..\dist\Fuguang-3D-Album-2.1.1-test.apk"}
else { Copy-Item "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk" "$PSScriptRoot\..\dist\Fuguang-3D-Album-2.1.1-test-debug.apk" }
