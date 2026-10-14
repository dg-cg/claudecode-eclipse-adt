<#
.SYNOPSIS
    Builds the Claude Code ADT plug-in against a local Eclipse installation: no Maven, no
    network. Optionally installs it into that Eclipse's dropins folder.

.DESCRIPTION
    Compiles com.anthropic.claudecode.eclipse/src with the JDK Eclipse ships (JustJ) or a JDK
    you name, against every plug-in of the Eclipse installation, and packs the bundle jar
    with the plug-in's plugin.xml, icons, resources and native libraries. The bundle version
    is the manifest's, with ".qualifier" replaced by the build time, so every build is newer
    than the last.

    The prebuilt native core (native/) is packed as it is; this script does not build Rust.

    Resolvers for languages whose plug-ins the installation lacks (CDT, PyDev) are left out
    of the build; the plug-in looks them up by name and skips the missing ones.

.PARAMETER EclipseHome
    The Eclipse installation to compile against and install into. Default C:\ADT\eclipse.

.PARAMETER JavaHome
    A JDK (21 or later) to compile with. Default: JAVA_HOME, else the JustJ JDK in EclipseHome.

.PARAMETER Install
    Copy the jar into EclipseHome\dropins, replacing an earlier build of this plug-in there.
    Restart Eclipse afterwards (the first time, or when it does not pick the jar up, with -clean).

.PARAMETER UpdateSite
    Also build an offline p2 update site, zipped: build-local\ClaudeCodeADT-<version>.zip.
    Install it in any Eclipse with Help > Install New Software > Add... > Archive..., which
    needs neither the network nor write access to the Eclipse installation (p2 installs into
    the user's profile when the installation is read-only). The site carries the plug-in, its
    feature, and the bundles it needs beyond the platform (Terminal, Gson), in case the target
    Eclipse lacks them. It is published by EclipseHome's own p2 publisher, run headless in a
    configuration of its own, so a running Eclipse is not disturbed.

.EXAMPLE
    .\build-local.ps1 -Install

.EXAMPLE
    .\build-local.ps1 -UpdateSite
#>
param(
    [string]$EclipseHome = 'C:\ADT\eclipse',
    [string]$JavaHome,
    [switch]$Install,
    [switch]$UpdateSite
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$bundleDir = Join-Path $root 'com.anthropic.claudecode.eclipse'
$out = Join-Path $root 'build-local'
$classes = Join-Path $out 'classes'
$stage = Join-Path $out 'stage'
$plugins = Join-Path $EclipseHome 'plugins'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

if (-not (Test-Path $plugins)) { throw "No Eclipse plug-ins at $plugins; pass -EclipseHome." }

# ── JDK ─────────────────────────────────────────────────────────────────────────────
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    $justj = Get-ChildItem $plugins -Directory -Filter 'org.eclipse.justj.openjdk.hotspot.jre.full.*' |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($justj) { $JavaHome = Join-Path $justj.FullName 'jre' }
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "No javac found; pass -JavaHome with a JDK 21 or later." }
Step "JDK: $JavaHome"

# ── Manifest: symbolic name and version ──────────────────────────────────────────────
$manifestText = [IO.File]::ReadAllText((Join-Path $bundleDir 'META-INF\MANIFEST.MF'), $utf8)
$bsn = ([regex]'(?m)^Bundle-SymbolicName:\s*([^;\r\n]+)').Match($manifestText).Groups[1].Value.Trim()
$version = ([regex]'(?m)^Bundle-Version:\s*(\S+)').Match($manifestText).Groups[1].Value.Trim()
$qualifier = Get-Date -Format 'yyyyMMddHHmm'
$version = $version -replace '\.qualifier$', ".$qualifier"
$manifestText = ([regex]'(?m)^Bundle-Version:\s*\S+').Replace($manifestText, "Bundle-Version: $version")
Step "Bundle: ${bsn}_$version"

# ── Class path: every plug-in of the installation ─────────────────────────────────────
$cp = New-Object System.Collections.Generic.List[string]
foreach ($item in Get-ChildItem $plugins) {
    if ($item.PSIsContainer) {
        $cp.Add($item.FullName)
        foreach ($inner in Get-ChildItem $item.FullName -Filter *.jar -Recurse -Depth 2 -ErrorAction SilentlyContinue) {
            $cp.Add($inner.FullName)
        }
    } elseif ($item.Extension -eq '.jar' -and $item.Name -notlike "$bsn`_*") {
        $cp.Add($item.FullName)
    }
}

# ── Sources ─────────────────────────────────────────────────────────────────────────
function Has-Bundle($id) { [bool](Get-ChildItem $plugins -Filter "$id`_*" -ErrorAction SilentlyContinue) }
$skip = @()
if (-not ((Has-Bundle 'org.eclipse.cdt.core') -and (Has-Bundle 'org.eclipse.cdt.ui'))) {
    $skip += 'CppIdentifierEntityResolver.java'
}
if (-not ((Has-Bundle 'org.python.pydev') -and (Has-Bundle 'org.python.pydev.core'))) {
    $skip += 'PythonIdentifierEntityResolver.java'
}
# Sources that reach into one windowing system's SWT internals, by the SWT fragment they need.
# Off that system they are compiled by ecj with -proceedOnError: the methods that use the
# missing classes become stubs that throw, which is safe because the classes only call them
# after checking SWT.getPlatform().
$platformOnly = @{ 'TerminalScrollbar.java' = 'org.eclipse.swt.gtk.linux.*' }
$lenient = @($platformOnly.Keys | Where-Object { -not (Get-ChildItem $plugins -Filter $platformOnly[$_] -ErrorAction SilentlyContinue) })
$allSources = Get-ChildItem (Join-Path $bundleDir 'src') -Recurse -Filter *.java |
    Where-Object { $skip -notcontains $_.Name }
$sources = @($allSources | Where-Object { $lenient -notcontains $_.Name })
$lenientSources = @($allSources | Where-Object { $lenient -contains $_.Name })
if ($skip) { Step "Left out (plug-ins not installed): $($skip -join ', ')" }

# ── Compile ─────────────────────────────────────────────────────────────────────────
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $classes | Out-Null
function Quote($p) { '"' + ($p -replace '\\', '/') + '"' }

if ($lenientSources.Count -gt 0) {
    $ecjJar = Get-ChildItem $plugins -Filter 'org.eclipse.jdt.core.compiler.batch_*.jar' |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ecjJar) { throw "No org.eclipse.jdt.core.compiler.batch in $plugins to compile $($lenient -join ', ') with." }
    Step "Compiling $($lenient -join ', ') with ecj (SWT fragment for that platform not installed)"
    $ecjArgs = Join-Path $out 'ecj.args'
    $ecjLines = @(
        '-d', (Quote $classes),
        '-21',
        '-encoding', 'UTF-8',
        '-proceedOnError',
        '-nowarn',
        '-log', (Quote (Join-Path $out 'ecj.log')),
        '-cp', (Quote ($cp -join ';'))
    ) + ($lenientSources | ForEach-Object { Quote $_.FullName })
    [IO.File]::WriteAllLines($ecjArgs, $ecjLines, $utf8)
    # The expected errors go to build-local\ecj.log; ecj prints them as well, so its output is
    # dropped (with errors not stopping the script while it runs: stderr of a native command
    # counts as one in Windows PowerShell).
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JavaHome 'bin\java.exe') -cp $ecjJar.FullName org.eclipse.jdt.internal.compiler.batch.Main "@$ecjArgs" 2>&1 | Out-Null
    $ErrorActionPreference = $previous
    foreach ($s in $lenientSources) {
        $rel = $s.FullName.Substring((Join-Path $bundleDir 'src').Length + 1) -replace '\.java$', '.class'
        if (-not (Test-Path (Join-Path $classes $rel))) { throw "ecj produced no $rel" }
    }
    $cp.Insert(0, $classes)
}

$argFile = Join-Path $out 'javac.args'
$lines = @(
    '-d', (Quote $classes),
    '--release', '21',
    '-encoding', 'UTF-8',
    '-proc:none',
    '-nowarn',
    '-Xmaxerrs', '200',
    '-cp', (Quote ($cp -join ';'))
) + ($sources | ForEach-Object { Quote $_.FullName })
[IO.File]::WriteAllLines($argFile, $lines, $utf8)
Step "Compiling $($sources.Count) sources against $($cp.Count) class path entries"
& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

# ── Native library matches the Java side ─────────────────────────────────────────────
# Every native method of NativeCore must be exported by the bundled library. One that is
# missing fails with UnsatisfiedLinkError, and a library from another source state can also
# take different arguments, which crashes the JVM with no message. The libraries committed
# with the sources have lagged behind before; the release jars carry ones built for them.
$nativeCore = Join-Path $bundleDir 'src\com\anthropic\claudecode\eclipse\NativeCore.java'
$declared = [regex]::Matches([IO.File]::ReadAllText($nativeCore, $utf8), '(?m)^\s*(?:public\s+|private\s+|static\s+)+native\s+[\w<>\[\],\s]+?\s(\w+)\s*\(') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
foreach ($dll in Get-ChildItem (Join-Path $bundleDir 'native\windows') -Recurse -Filter *.dll) {
    $text = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($dll.FullName))
    $exported = [regex]::Matches($text, 'Java_com_anthropic_claudecode_eclipse_NativeCore_(\w+)') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $missing = @($declared | Where-Object { $exported -notcontains $_ })
    if ($missing.Count -gt 0) {
        throw "$($dll.FullName) does not match NativeCore.java: it lacks $($missing -join ', '). " +
              "Use native libraries built from this source state (or from the matching release jar)."
    }
}
Step "Native library exports all $($declared.Count) native methods"

# ── Stage and pack ───────────────────────────────────────────────────────────────────
Step 'Packing'
Copy-Item -Recurse $classes $stage
foreach ($name in 'plugin.xml', 'about.html', 'icons', 'resources', 'native') {
    $src = Join-Path $bundleDir $name
    if (Test-Path $src) { Copy-Item -Recurse $src (Join-Path $stage $name) }
}
$manifest = Join-Path $out 'MANIFEST.MF'
if (-not $manifestText.EndsWith("`n")) { $manifestText += "`r`n" }
[IO.File]::WriteAllText($manifest, $manifestText, $utf8)
$jarFile = Join-Path $out "${bsn}_$version.jar"
& $jar --create --file $jarFile --manifest $manifest -C $stage .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }
$size = [math]::Round((Get-Item $jarFile).Length / 1MB, 1)
Step "Built $jarFile ($size MB)"

# ── Offline update site ──────────────────────────────────────────────────────────────
if ($UpdateSite) {
    $siteIn = Join-Path $out 'site-input'
    $repo = Join-Path $out 'repository'
    $p2Work = Join-Path $out 'p2-work'
    New-Item -ItemType Directory -Force (Join-Path $siteIn 'plugins'), (Join-Path $siteIn 'features'), $repo | Out-Null
    Copy-Item $jarFile (Join-Path $siteIn 'plugins')

    # The bundles the plug-in needs beyond the platform, from this installation.
    foreach ($dep in 'org.eclipse.terminal.control', 'org.eclipse.terminal.connector.process',
                     'org.eclipse.terminal.connector.local', 'com.google.gson') {
        $depJar = Get-ChildItem $plugins -Filter "$dep`_*.jar" | Sort-Object Name -Descending | Select-Object -First 1
        if ($depJar) { Copy-Item $depJar.FullName (Join-Path $siteIn 'plugins') }
        else { Write-Warning "$dep not found in $plugins; the site will not carry it." }
    }

    # The feature, with the version of this build and the exact version of the plug-in.
    $featureDir = Join-Path $root 'com.anthropic.claudecode.eclipse.feature'
    $featureXml = [IO.File]::ReadAllText((Join-Path $featureDir 'feature.xml'), $utf8)
    $featureId = ([regex]'<feature[^>]*?\sid="([^"]+)"').Match($featureXml).Groups[1].Value
    $featureXml = $featureXml -replace '(<feature[^>]*?\sversion=")[^"]+(")', "`${1}$version`${2}"
    $featureXml = ([regex]"(<plugin\s+id=""$([regex]::Escape($bsn))""\s+version="")[^""]+("")").Replace($featureXml, "`${1}$version`${2}")
    $featureStage = Join-Path $out 'feature-stage'
    New-Item -ItemType Directory -Force $featureStage | Out-Null
    [IO.File]::WriteAllText((Join-Path $featureStage 'feature.xml'), $featureXml, $utf8)
    & $jar --create --file (Join-Path $siteIn "features\${featureId}_$version.jar") -C $featureStage .
    if ($LASTEXITCODE -ne 0) { throw "jar (feature) failed (exit $LASTEXITCODE)" }

    # A configuration of its own for the headless publisher: the installation's, without
    # the dropins reconciler, which must not touch the installation's profile from here.
    $config = Join-Path $p2Work 'configuration'
    New-Item -ItemType Directory -Force (Join-Path $config 'org.eclipse.equinox.simpleconfigurator') | Out-Null
    Copy-Item (Join-Path $EclipseHome 'configuration\config.ini') $config
    $bundlesInfo = Get-Content (Join-Path $EclipseHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info') |
        Where-Object { $_ -notmatch '^org\.eclipse\.equinox\.p2\.reconciler\.dropins,' }
    [IO.File]::WriteAllLines((Join-Path $config 'org.eclipse.equinox.simpleconfigurator\bundles.info'), $bundlesInfo, $utf8)
    $launcher = Get-ChildItem $plugins -Filter 'org.eclipse.equinox.launcher_*.jar' | Sort-Object Name -Descending | Select-Object -First 1
    $repoUri = ([Uri]$repo).AbsoluteUri
    function Run-Publisher([string[]]$appArgs) {
        $all = @('-jar', $launcher.FullName, '-install', $EclipseHome, '-configuration', $config,
                 '-data', (Join-Path $p2Work 'workspace'), '-nosplash', '-consoleLog') + $appArgs
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $output = & (Join-Path $JavaHome 'bin\java.exe') @all 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
        $ErrorActionPreference = $previous
        $output | Out-File -Append -Encoding utf8 (Join-Path $out 'p2-publisher.log')
        if ($code -ne 0) { throw "p2 publisher failed (exit $code); see build-local\p2-publisher.log" }
    }

    Step 'Publishing the update site'
    Run-Publisher @('-application', 'org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher',
        '-metadataRepository', $repoUri, '-artifactRepository', $repoUri,
        '-metadataRepositoryName', 'Claude Code ADT', '-artifactRepositoryName', 'Claude Code ADT',
        '-source', $siteIn, '-compress', '-publishArtifacts')

    # The category, so that the feature shows under "Claude Code ADT" in Install New Software.
    $categoryXml = [IO.File]::ReadAllText((Join-Path $root 'com.anthropic.claudecode.eclipse.site\category.xml'), $utf8)
    $categoryXml = $categoryXml -replace "(<feature\s+id=""$([regex]::Escape($featureId))"")", "`$1 version=""$version"" url=""features/${featureId}_$version.jar"""
    $categoryFile = Join-Path $out 'category.xml'
    [IO.File]::WriteAllText($categoryFile, $categoryXml, $utf8)
    Run-Publisher @('-application', 'org.eclipse.equinox.p2.publisher.CategoryPublisher',
        '-metadataRepository', $repoUri, '-categoryDefinition', ([Uri]$categoryFile).AbsoluteUri,
        '-categoryQualifier', 'claudecode.adt', '-compress')

    foreach ($must in 'content.jar', 'artifacts.jar') {
        if (-not (Test-Path (Join-Path $repo $must))) { throw "The update site has no $must; see build-local\p2-publisher.log" }
    }
    # Zipped with the JDK's jar tool: Compress-Archive in Windows PowerShell 5.1 writes the
    # entry names with backslashes (plugins\x.jar), under which p2 finds no artifact.
    $zip = Join-Path $out "ClaudeCodeADT-$version.zip"
    if (Test-Path $zip) { Remove-Item -Force $zip }
    & $jar --create --no-manifest --file $zip -C $repo .
    if ($LASTEXITCODE -ne 0) { throw "jar (update site zip) failed (exit $LASTEXITCODE)" }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $check = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $bad = @($check.Entries | Where-Object { $_.FullName.Contains('\') })
        if ($bad.Count -gt 0) { throw "The update site zip has entry names with backslashes: $($bad[0].FullName)" }
    } finally { $check.Dispose() }
    $zipSize = [math]::Round((Get-Item $zip).Length / 1MB, 1)
    Step "Update site: $zip ($zipSize MB)"
}

# ── Install ─────────────────────────────────────────────────────────────────────────
if ($Install) {
    $dropins = Join-Path $EclipseHome 'dropins'
    New-Item -ItemType Directory -Force $dropins | Out-Null
    Get-ChildItem $dropins -Filter "$bsn`_*.jar" | ForEach-Object {
        Step "Removing earlier build $($_.Name)"
        Remove-Item -Force $_.FullName
    }
    Copy-Item $jarFile $dropins
    Step "Installed into $dropins. Restart Eclipse (with -clean if the plug-in does not appear)."
}
