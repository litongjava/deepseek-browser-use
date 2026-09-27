# Verify the browser.json.skipNull switch both ways.
# Round 1: app.properties gets browser.json.skipNull=false -> responses should carry null again.
# Round 2: back to default (true)                          -> responses should have no null.
$ErrorActionPreference = 'Stop'
$repo = 'E:\code\java\project-litongjava\deepseek-browser-use'
$props = Join-Path $repo 'playwright-server\src\main\resources\app.properties'
$original = Get-Content $props -Raw -Encoding UTF8

function Wait-Health {
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $r = Invoke-WebRequest -Uri http://localhost:10049/playwright/health -UseBasicParsing -TimeoutSec 3
      if ($r.StatusCode -eq 200) { return $true }
    } catch { Start-Sleep -Seconds 3 }
  }
  return $false
}

function Restart-Server {
  & (Join-Path $repo 'scripts\run\stop-server.cmd') *> $null
  Start-Sleep -Seconds 3
  Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', (Join-Path $repo 'scripts\run\start-server.cmd') -WindowStyle Hidden
  if (-not (Wait-Health)) { throw 'server did not come up' }
  Start-Sleep -Seconds 3
}

function Probe {
  (Invoke-WebRequest -Uri http://localhost:10049/playwright/health -UseBasicParsing).Content
}

try {
  Set-Content -Path $props -Value ($original.TrimEnd() + "`nbrowser.json.skipNull=false`n") -Encoding UTF8 -NoNewline
  '== round 1: app.properties =='
  Get-Content $props -Encoding UTF8
  Restart-Server
  '== switch OFF: response =='
  Probe

  Set-Content -Path $props -Value $original -Encoding UTF8 -NoNewline
  '== round 2: app.properties (restored) =='
  Get-Content $props -Encoding UTF8
  Restart-Server
  '== default ON: response =='
  Probe
} finally {
  Set-Content -Path $props -Value $original -Encoding UTF8 -NoNewline
  '== cleanup: app.properties restored =='
  Get-Content $props -Encoding UTF8
}
