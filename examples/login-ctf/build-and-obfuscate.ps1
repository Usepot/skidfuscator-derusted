[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$exampleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $exampleRoot "..\..")).Path
$buildRoot = Join-Path $exampleRoot "build"
$classesRoot = Join-Path $buildRoot "classes"
$sourceFile = Join-Path $exampleRoot "src\main\java\ctf\login\LoginChallenge.java"
$configFile = Join-Path $exampleRoot "skidfuscator.hocon"
$originalJar = Join-Path $buildRoot "login-ctf-original.jar"
$obfuscatedJar = Join-Path $buildRoot "login-ctf-obfuscated.jar"
$obfuscatorJar = Join-Path $repoRoot "dev.skidfuscator.client.standalone\build\libs\client-standalone-all.jar"
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

foreach ($command in @("java", "javac", "jar")) {
    if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command '$command' was not found on PATH."
    }
}

$resolvedBuildRoot = [System.IO.Path]::GetFullPath($buildRoot)
$resolvedExampleRoot = [System.IO.Path]::GetFullPath($exampleRoot) + [System.IO.Path]::DirectorySeparatorChar
if (-not ($resolvedBuildRoot + [System.IO.Path]::DirectorySeparatorChar).StartsWith(
        $resolvedExampleRoot,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean a build directory outside the example root: $resolvedBuildRoot"
}

if (Test-Path -LiteralPath $resolvedBuildRoot) {
    Remove-Item -LiteralPath $resolvedBuildRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null

Write-Host "Building the Skidfuscator CLI..."
Push-Location $repoRoot
try {
    & $gradleWrapper --no-daemon :client-standalone:shadowJar
    if ($LASTEXITCODE -ne 0) {
        throw "Skidfuscator CLI build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $obfuscatorJar -PathType Leaf)) {
    throw "Expected Skidfuscator CLI was not produced: $obfuscatorJar"
}

Write-Host "Compiling the Java 8 login fixture..."
& javac --release 8 -encoding UTF-8 -d $classesRoot $sourceFile
if ($LASTEXITCODE -ne 0) {
    throw "Fixture compilation failed with exit code $LASTEXITCODE."
}

& jar cfe $originalJar ctf.login.LoginChallenge -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    throw "Fixture packaging failed with exit code $LASTEXITCODE."
}

Write-Host "Obfuscating the fixture..."
Push-Location $exampleRoot
try {
    & java -jar $obfuscatorJar obfuscate $originalJar `
        -o $obfuscatedJar `
        -cfg $configFile `
        -notrack
    if ($LASTEXITCODE -ne 0) {
        throw "Skidfuscator failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

Write-Host "Original:   $originalJar"
Write-Host "Obfuscated: $obfuscatedJar"
