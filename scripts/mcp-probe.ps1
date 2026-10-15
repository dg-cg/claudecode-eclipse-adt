<#
.SYNOPSIS
    Calls one tool of the running plug-in's MCP server, the way the Claude CLI does, and
    prints every SSE event with the time it arrived.

.DESCRIPTION
    For testing a tool against the real Eclipse without a Claude session: a reply that is
    late or never comes shows. The server is found through the IDE lock files in
    ~/.claude/ide (the one whose ideName is "Eclipse (ADT)"), unless -Port is given.

    The call goes straight to the server: no permission card is shown. Tools that change
    something (editEditor, saveDocument, adtCommand activate) do change it.

.EXAMPLE
    .\scripts\mcp-probe.ps1 -Tool getOpenEditors

.EXAMPLE
    .\scripts\mcp-probe.ps1 -Tool adtCommand -ArgsJson '{"action":"checkSyntax","file_path":"ZMY_REPORT"}'
#>
param(
    [int]$Port = 0,
    [string]$Tool = 'getOpenEditors',
    [string]$ArgsJson = '{}',
    [int]$TimeoutSeconds = 90,
    [string]$IdeName = 'Eclipse (ADT)',
    # How much of each event to print; the reply to a big call can be long.
    [int]$MaxChars = 3000
)

if ($Port -eq 0) {
    $dir = if ($env:CLAUDE_CONFIG_DIR) { Join-Path $env:CLAUDE_CONFIG_DIR 'ide' } else { Join-Path $env:USERPROFILE '.claude\ide' }
    foreach ($lock in Get-ChildItem $dir -Filter *.lock -ErrorAction SilentlyContinue) {
        $j = Get-Content $lock.FullName -Raw | ConvertFrom-Json
        if ($j.ideName -eq $IdeName) { $Port = [int]$j.port }
    }
    if ($Port -eq 0) { throw "No lock file with ideName '$IdeName' in $dir; is Eclipse running? Pass -Port." }
}

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds + 30)
$base = "http://127.0.0.1:$Port"
$sw = [Diagnostics.Stopwatch]::StartNew()
function T { '{0,7:N1}s' -f $sw.Elapsed.TotalSeconds }

$req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$base/sse")
$resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
"$(T) GET $base/sse -> $([int]$resp.StatusCode)"
$reader = New-Object IO.StreamReader($resp.Content.ReadAsStreamAsync().Result)

function Read-Event([int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $type = 'message'; $data = ''
    while ((Get-Date) -lt $deadline) {
        $task = $reader.ReadLineAsync()
        while (-not $task.Wait(500)) { if ((Get-Date) -ge $deadline) { return $null } }
        $line = $task.Result
        if ($null -eq $line) { return $null }
        if ($line -eq '') { if ($data) { return @{ type = $type; data = $data } }; continue }
        if ($line.StartsWith(':')) { continue }   # keep-alive
        if ($line.StartsWith('event:')) { $type = $line.Substring(6).Trim() }
        elseif ($line.StartsWith('data:')) { $data += $line.Substring(5).Trim() }
    }
    return $null
}

$ep = Read-Event 10
"$(T) endpoint: $($ep.data)"
function Post($json) {
    $content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
    $r = $client.PostAsync("$base$($ep.data)", $content).Result
    "$(T) POST -> $([int]$r.StatusCode)"
}
Post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
$e = Read-Event 10; "$(T) [$($e.type)] $($e.data.Substring(0, [Math]::Min(200, $e.data.Length)))"
Post '{"jsonrpc":"2.0","method":"notifications/initialized"}'
Post ('{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"' + $Tool + '","arguments":' + $ArgsJson + '}}')
while ($true) {
    $e = Read-Event $TimeoutSeconds
    if ($null -eq $e) { "$(T) NO REPLY within $TimeoutSeconds s"; break }
    "$(T) [$($e.type)] $($e.data.Substring(0, [Math]::Min($MaxChars, $e.data.Length)))"
    if ($e.data -match '"id"\s*:\s*2\b') { break }
}
$client.Dispose()
