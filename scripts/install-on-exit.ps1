<#
.SYNOPSIS
    Waits until Eclipse has exited, then replaces the Claude Code ADT jar in dropins.

.DESCRIPTION
    Eclipse locks the jar of a loaded plug-in, so a new build cannot be installed into
    dropins while it runs. Start this as a process of its own, so that it outlives a Claude
    session running inside that Eclipse:

        Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile',
            '-ExecutionPolicy','Bypass','-File','scripts\install-on-exit.ps1'

    then close Eclipse; the jar is in place before it is started again. The outcome is
    appended to build-local\install-on-exit.log.

    Only for an installation through dropins. One made from the update site is updated
    through Install New Software instead.
#>
param(
    [string]$Jar,
    [string]$EclipseHome = 'C:\ADT\eclipse',
    [string]$Log,
    [int]$WaitMinutes = 60
)
$root = Split-Path $PSScriptRoot -Parent
if (-not $Jar) {
    $Jar = (Get-ChildItem (Join-Path $root 'build-local') -Filter 'com.anthropic.claudecode.eclipse.adt_*.jar' |
        Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not $Log) { $Log = Join-Path $root 'build-local\install-on-exit.log' }
if (-not $Jar -or -not (Test-Path $Jar)) {
    "$(Get-Date -Format HH:mm:ss) no jar to install; run build-local.ps1 first" | Out-File $Log -Append
    exit 1
}
$dropins = Join-Path $EclipseHome 'dropins'

$deadline = (Get-Date).AddMinutes($WaitMinutes)
while ((Get-Process eclipse -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
}
if (Get-Process eclipse -ErrorAction SilentlyContinue) {
    "$(Get-Date -Format HH:mm:ss) Eclipse still running after $WaitMinutes minutes; nothing installed." | Out-File $Log -Append
    exit 1
}
Start-Sleep -Seconds 1
try {
    Get-ChildItem $dropins -Filter 'com.anthropic.claudecode.eclipse.adt_*.jar' | Remove-Item -Force -ErrorAction Stop
    Copy-Item $Jar $dropins -ErrorAction Stop
    "$(Get-Date -Format HH:mm:ss) installed $(Split-Path $Jar -Leaf)" | Out-File $Log -Append
} catch {
    "$(Get-Date -Format HH:mm:ss) FAILED: $($_.Exception.Message)" | Out-File $Log -Append
}
