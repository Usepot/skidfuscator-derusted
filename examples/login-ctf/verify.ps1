[CmdletBinding()]
param(
    [string]$CandidateJar = "",
    [switch]$RequireNoStaticFlag
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$exampleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path $exampleRoot "build"
$originalJar = Join-Path $buildRoot "login-ctf-original.jar"
$obfuscatedJar = if ([string]::IsNullOrWhiteSpace($CandidateJar)) {
    Join-Path $buildRoot "login-ctf-obfuscated.jar"
} elseif ([System.IO.Path]::IsPathRooted($CandidateJar)) {
    [System.IO.Path]::GetFullPath($CandidateJar)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $exampleRoot $CandidateJar))
}
$flag = "SKID{maple_ir_obfuscation_verified}"

$javaVersionLine = (& java -version 2>&1 | Select-Object -First 1).ToString()
$javaMajor = 8
if ($javaVersionLine -match 'version "(?<major>\d+)(?:\.(?<minor>\d+))?') {
    $javaMajor = [int]$Matches.major
    if ($javaMajor -eq 1 -and $Matches.minor) {
        $javaMajor = [int]$Matches.minor
    }
}
$javaNativeAccessOption = if ($javaMajor -ge 17) { "--enable-native-access=ALL-UNNAMED " } else { "" }

function Invoke-Jar {
    param(
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$InputText
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = (Get-Command java).Source
    $escapedJar = $JarPath.Replace('"', '\"')
    $startInfo.Arguments = "$javaNativeAccessOption-jar `"$escapedJar`""
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Failed to start $JarPath"
    }

    $process.StandardInput.Write($InputText)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
        Stdout = $stdout
        Stderr = $stderr
    }
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if ($Expected -ne $Actual) {
        throw "$Message Expected '$Expected' but got '$Actual'."
    }
}

function Find-ByteSequence {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]]$Data,
        [Parameter(Mandatory = $true)][byte[]]$Pattern
    )
    if ($Pattern.Length -eq 0 -or $Data.Length -lt $Pattern.Length) {
        return -1
    }
    for ($offset = 0; $offset -le $Data.Length - $Pattern.Length; $offset++) {
        $matches = $true
        for ($index = 0; $index -lt $Pattern.Length; $index++) {
            if ($Data[$offset + $index] -ne $Pattern[$index]) {
                $matches = $false
                break
            }
        }
        if ($matches) {
            return $offset
        }
    }
    return -1
}

function Assert-NoStaticFlag {
    param([Parameter(Mandatory = $true)][string]$JarPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $patterns = @(
        [System.Text.Encoding]::UTF8.GetBytes($flag),
        [System.Text.Encoding]::Unicode.GetBytes($flag),
        [System.Text.Encoding]::BigEndianUnicode.GetBytes($flag)
    )
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($entry in $archive.Entries) {
            $stream = $entry.Open()
            $buffer = New-Object System.IO.MemoryStream
            try {
                $stream.CopyTo($buffer)
                $data = $buffer.ToArray()
            } finally {
                $buffer.Dispose()
                $stream.Dispose()
            }
            foreach ($pattern in $patterns) {
                $offset = Find-ByteSequence -Data $data -Pattern $pattern
                if ($offset -ge 0) {
                    throw "Static flag leaked in $($entry.FullName) at offset 0x$($offset.ToString('x'))."
                }
            }
        }
    } finally {
        $archive.Dispose()
    }
    Write-Host "PASS: protected flag is absent from decompressed JAR entries"
}

foreach ($jar in @($originalJar, $obfuscatedJar)) {
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Missing JAR: $jar. Run build-and-obfuscate.ps1 first."
    }
}

$cases = @(
    [PSCustomObject]@{
        Name = "valid credentials"
        InputText = "maple`nRedMaple!2026`n"
        ExitCode = 0
        ContainsFlag = $true
    },
    [PSCustomObject]@{
        Name = "wrong password"
        InputText = "maple`nwrong`n"
        ExitCode = 1
        ContainsFlag = $false
    },
    [PSCustomObject]@{
        Name = "case-sensitive username"
        InputText = "Maple`nRedMaple!2026`n"
        ExitCode = 1
        ContainsFlag = $false
    },
    [PSCustomObject]@{
        Name = "end of input"
        InputText = ""
        ExitCode = 2
        ContainsFlag = $false
    }
)

foreach ($case in $cases) {
    $original = Invoke-Jar -JarPath $originalJar -InputText $case.InputText
    $obfuscated = Invoke-Jar -JarPath $obfuscatedJar -InputText $case.InputText

    Assert-Equal $case.ExitCode $original.ExitCode "$($case.Name): original exit code."
    Assert-Equal $case.ExitCode $obfuscated.ExitCode "$($case.Name): obfuscated exit code."
    Assert-Equal $original.Stdout $obfuscated.Stdout "$($case.Name): stdout differs."
    Assert-Equal $original.Stderr $obfuscated.Stderr "$($case.Name): stderr differs."

    $hasFlag = $obfuscated.Stdout.Contains($flag)
    Assert-Equal $case.ContainsFlag $hasFlag "$($case.Name): unexpected flag visibility."
    Write-Host "PASS: $($case.Name)"
}

if ($RequireNoStaticFlag) {
    Assert-NoStaticFlag -JarPath $obfuscatedJar
}

$originalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $originalJar).Hash
$obfuscatedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $obfuscatedJar).Hash
if ($originalHash -eq $obfuscatedHash) {
    throw "The original and obfuscated JARs have the same SHA-256 digest."
}

Write-Host "Original SHA-256:   $originalHash"
Write-Host "Obfuscated SHA-256: $obfuscatedHash"
Write-Host "All login CTF checks passed."
