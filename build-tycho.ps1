<#
.SYNOPSIS
    Runs the real Tycho build, as the upstream CI does (.github/workflows/java-ci.yml).

.DESCRIPTION
    Needs Maven 3.9.x and a JDK 21 or later, and network access to Maven Central and to the
    p2 repositories of claudecode.target (download.eclipse.org, PyDev's SourceForge site).
    The first run downloads the target platform into the local Maven repository; later runs
    take about a minute.

    Maven 3.10 does not work: with it tycho-surefire cannot resolve the test dependencies
    ("Invalid Collect Request"), which is why upstream pins 3.9.16.

    Like the CI, the Rust sources are copied into com.anthropic.claudecode.eclipse/src/core
    first: build.properties packs that folder into the plug-in, and Tycho fails when an
    entry of bin.includes is missing. The folder is git-ignored.

    Results:
      com.anthropic.claudecode.eclipse\target\com.anthropic.claudecode.eclipse.adt-<version>.jar
      com.anthropic.claudecode.eclipse.site\target\com.anthropic.claudecode.eclipse.adt.site-<version>.zip

.PARAMETER Goals
    The Maven goals and options. Default: clean verify -DskipTests

.EXAMPLE
    .\build-tycho.ps1

.EXAMPLE
    .\build-tycho.ps1 -Goals 'clean','integration-test'
#>
param(
    [string]$MavenHome = 'C:\maven\apache-maven-3.9.16',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-23',
    [string[]]$Goals = @('clean', 'verify', '-DskipTests')
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$mvn = Join-Path $MavenHome 'bin\mvn.cmd'
if (-not (Test-Path $mvn)) { throw "No Maven at $MavenHome; pass -MavenHome (Maven 3.9.x, not 3.10)." }
if (-not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) { throw "No JDK at $JavaHome; pass -JavaHome (21 or later)." }

$core = Join-Path $root 'com.anthropic.claudecode.eclipse\src\core'
if (Test-Path $core) { Remove-Item -Recurse -Force $core }
New-Item -ItemType Directory -Force $core | Out-Null
Copy-Item -Recurse (Join-Path $root 'claude-eclipse-core\src\*') $core

$env:JAVA_HOME = $JavaHome
Push-Location $root
try {
    & $mvn -B -ntp @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}
