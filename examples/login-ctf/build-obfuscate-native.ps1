[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SkidLLVMPath,
    [ValidateSet("AOT", "VM")][string]$Backend = "AOT"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$exampleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $exampleRoot "..\..")).Path
$buildRoot = Join-Path $exampleRoot "build"
$originalJar = Join-Path $buildRoot "login-ctf-original.jar"
$nativeJar = Join-Path $buildRoot "login-ctf-native.jar"
$nativeArtifacts = Join-Path $buildRoot "native-artifacts"
$configFile = Join-Path $exampleRoot ("skidfuscator-native-{0}.hocon" -f $Backend.ToLowerInvariant())
$obfuscatorJar = Join-Path $repoRoot "dev.skidfuscator.client.standalone\build\libs\client-standalone-all.jar"

$SkidLLVMPath = [System.IO.Path]::GetFullPath($SkidLLVMPath)
if (-not (Test-Path -LiteralPath $SkidLLVMPath -PathType Container)) {
    throw "SkidLLVM installation does not exist: $SkidLLVMPath"
}
foreach ($required in @("skidllvm-toolchain.manifest")) {
    if (-not (Test-Path -LiteralPath (Join-Path $SkidLLVMPath $required) -PathType Leaf)) {
        throw "Authenticated SkidLLVM installation is missing $required."
    }
}

$architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
$target = if ($architecture -eq "arm64") { "windows-aarch64" } else { "windows-x86_64" }

& (Join-Path $exampleRoot "build-and-obfuscate.ps1")

Write-Host "Compiling and embedding the $Backend method for $target..."
Push-Location $exampleRoot
try {
    & java -jar $obfuscatorJar obfuscate $originalJar `
        -o $nativeJar `
        -cfg $configFile `
        -notrack `
        --native-toolchain-delivery EXTERNAL `
        --native-toolchain-path $SkidLLVMPath `
        --native-targets $target `
        --native-artifact-dir $nativeArtifacts
    if ($LASTEXITCODE -ne 0) {
        throw "Native Skidfuscator run failed with exit code $LASTEXITCODE."
    }

    & (Join-Path $exampleRoot "verify.ps1") -CandidateJar $nativeJar -RequireNoStaticFlag
    $platformJar = Join-Path $nativeArtifacts ("login-ctf-native-{0}.jar" -f $target)
    & (Join-Path $exampleRoot "verify.ps1") -CandidateJar $platformJar -RequireNoStaticFlag
} finally {
    Pop-Location
}

Write-Host "Native: $nativeJar"
