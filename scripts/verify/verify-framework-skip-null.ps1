# Verify the framework-level switch (tio.json.skipNull) end to end, both ways.
# Round 1: app.properties writes tio.json.skipNull=false -> responses carry null fields again.
# Round 2: app.properties writes tio.json.skipNull=true  -> responses have no null fields.
# Round 3: restore the original app.properties.
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
  Set-Content -Path $props -Value "server.port=10049`ntio.json.skipNull=false`n" -Encoding UTF8 -NoNewline
  '== round 1: tio.json.skipNull=false =='
  Restart-Server
  'response: ' + (Probe)

  Set-Content -Path $props -Value "server.port=10049`ntio.json.skipNull=true`n" -Encoding UTF8 -NoNewline
  '== round 2: tio.json.skipNull=true =='
  Restart-Server
  'response: ' + (Probe)
} finally {
  Set-Content -Path $props -Value $original -Encoding UTF8 -NoNewline
  '== round 3: restored app.properties =='
  Restart-Server
  'response: ' + (Probe)
}
