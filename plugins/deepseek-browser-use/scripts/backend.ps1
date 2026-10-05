<# Windows backend bootstrap. All mutable paths derive from the configured repository.
   Never reset a checkout, kill an unowned process, or replace a running artifact. #>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$ConfigPath,
  [ValidateSet('status','prepare','start','update','restart')][string]$Action = 'start'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$repo = [IO.Path]::GetFullPath($config.repoDir)
$runtime = Join-Path $repo '.dsb-backend'
$statePath = Join-Path $runtime 'state.json'
$resultPath = $config.resultPath
$endpoint = [Uri]$config.baseUrl
if ($endpoint.Scheme -ne 'http' -or $endpoint.Host -notin @('127.0.0.1','localhost') -or $endpoint.AbsolutePath -ne '/' -or $endpoint.Query -or $endpoint.Fragment -or $endpoint.UserInfo) {
  throw 'Managed backend requires an http://127.0.0.1:PORT or http://localhost:PORT root URL. Disable backendAutoStart for remote services.'
}
if ($repo -eq [IO.Path]::GetPathRoot($repo)) { throw 'Repository directory cannot be a drive root' }
$baseUrl = $endpoint.GetLeftPart([UriPartial]::Authority)
$sources = @('https://gitee.com/ppnt/deepseek-browser-use.git', 'https://github.com/litongjava/deepseek-browser-use.git')
if ($config.repository -eq 'github') { $sources = @($sources[1], $sources[0]) }
$lock = $null

function Native([string]$File, [string[]]$Arguments) {
  Write-Host ('> ' + $File + ' ' + ($Arguments -join ' '))
  $oldPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $output = @(& $File @Arguments 2>&1 | ForEach-Object { [string]$_ })
  $code = $LASTEXITCODE
  $ErrorActionPreference = $oldPreference
  foreach ($line in $output) { Write-Host $line }
  if ($code -ne 0) { throw "Command failed ($code): $File $($Arguments -join ' ')" }
  return ($output -join "`n")
}
function Refresh-Path {
  $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User') + ';' + $env:Path
}
function Find-Tool([string]$Name) {
  $found = Get-Command $Name -ErrorAction SilentlyContinue
  if ($found) { return $found.Source }
  return $null
}
function Install-Winget([string]$Id) {
  if (-not $config.installDependencies) { throw "Missing dependency $Id. Enable backendInstallDependencies or install it yourself." }
  $winget = Find-Tool 'winget.exe'
  if (-not $winget) { throw "Install Microsoft App Installer (winget), or install $Id manually and reopen Harness." }
  $null = Native $winget @('install','--id',$Id,'--exact','--source','winget','--silent','--accept-package-agreements','--accept-source-agreements','--disable-interactivity')
  Refresh-Path
}
function Find-Jdk {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin/java.exe') }
  $found = Find-Tool 'java.exe'; if ($found) { $candidates += $found }
  foreach ($folder in @((Join-Path $env:ProgramFiles 'Microsoft'), (Join-Path $env:ProgramFiles 'Eclipse Adoptium'))) {
    if (Test-Path -LiteralPath $folder) { $candidates += @(Get-ChildItem -LiteralPath $folder -Directory | ForEach-Object { Join-Path $_.FullName 'bin/java.exe' }) }
  }
  foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $candidate)) { continue }
    try {
      $version = Native $candidate @('-version')
      if ($version -match 'version "([0-9]+)' -and [int]$Matches[1] -ge 21) {
        $jdkHome = Split-Path (Split-Path $candidate -Parent) -Parent
        if (Test-Path -LiteralPath (Join-Path $jdkHome 'bin/javac.exe')) { return @{ java=$candidate; home=$jdkHome } }
      }
    } catch { Write-Host "Ignoring unusable Java candidate: $candidate" }
  }
  return $null
}
function Ensure-Tools {
  $git = Find-Tool 'git.exe'
  if (-not $git) { Install-Winget 'Git.Git'; $git = Find-Tool 'git.exe' }
  if (-not $git) { throw 'Git installed but is not discoverable. Reopen Harness to refresh PATH.' }
  $jdk = Find-Jdk
  if (-not $jdk) { Install-Winget 'Microsoft.OpenJDK.21'; $jdk = Find-Jdk }
  if (-not $jdk) { throw 'A Java 21+ JDK with javac is required. Reopen Harness after installation.' }
  $env:JAVA_HOME = $jdk.home
  $env:Path = (Join-Path $jdk.home 'bin') + ';' + $env:Path
  $maven = Find-Tool 'mvn.cmd'
  if (-not $maven) {
    $toolsDir = Join-Path (Split-Path $repo -Parent) '.dsb-tools'
    $mavenVersion = '3.9.16'
    $maven = Join-Path $toolsDir "apache-maven-$mavenVersion/bin/mvn.cmd"
    if (-not (Test-Path -LiteralPath $maven)) {
      if (-not $config.installDependencies) { throw 'Maven is missing. Install Maven or enable backendInstallDependencies.' }
      New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
      $fileName = "apache-maven-$mavenVersion-bin.zip"
      $archive = Join-Path $toolsDir $fileName
      $rootUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries"
      Write-Host "Downloading Apache Maven $mavenVersion"
      Invoke-WebRequest -UseBasicParsing -Uri "$rootUrl/$fileName" -OutFile $archive -TimeoutSec 180
      $checksum = (Invoke-WebRequest -UseBasicParsing -Uri "$rootUrl/$fileName.sha512" -TimeoutSec 60).Content
      if ($checksum -is [byte[]]) { $checksum = [Text.Encoding]::ASCII.GetString($checksum) }
      if ([string]$checksum -notmatch '\b([a-fA-F0-9]{128})\b') { throw 'Invalid Maven SHA-512 checksum response' }
      if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $Matches[1]) { throw 'Maven SHA-512 mismatch; archive was not executed' }
      Expand-Archive -LiteralPath $archive -DestinationPath $toolsDir -Force
    }
  }
  $null = Native $git @('--version')
  $null = Native $maven @('--version')
  return @{ git=$git; java=$jdk.java; maven=$maven }
}
function Health {
  try {
    $response = Invoke-RestMethod -Uri "$baseUrl/playwright/health" -TimeoutSec 2
    return ($response.ok -eq $true -and $response.data.name -eq 'playwright-server')
  } catch { return $false }
}
function State {
  if (Test-Path -LiteralPath $statePath) { return Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
  return $null
}
function Owned-Process($state) {
  if (-not $state -or -not $state.pid -or -not $state.token) { return $null }
  if ($state.baseUrl -ne $baseUrl) { return $null }
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.pid)" -ErrorAction SilentlyContinue
  if ($process -and $process.CommandLine -and $process.CommandLine.Contains("-Ddsb.managed.instance=$($state.token)") -and $process.CommandLine.Contains($runtime)) { return $process }
  return $null
}
function Assert-OwnedListener($state) {
  if (-not (Owned-Process $state)) { throw 'Backend process identity could not be verified' }
  $listeners = @(Get-NetTCPConnection -LocalPort $endpoint.Port -State Listen -ErrorAction SilentlyContinue)
  if (-not $listeners.Count -or @($listeners | Where-Object { $_.OwningProcess -ne [int]$state.pid }).Count) { throw 'Listening port is not exclusively owned by the recorded backend process' }
}
function Save-Result($value) {
  $value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resultPath -Encoding UTF8
}
function Ensure-Repository($tools, [bool]$update) {
  if (-not (Test-Path -LiteralPath $repo)) {
    $parent = Split-Path $repo -Parent
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $cloned = $false
    foreach ($source in $sources) {
      $staging = Join-Path $parent ('.dsb-clone-' + [Guid]::NewGuid().ToString('N'))
      try {
        $null = Native $tools.git @('clone','--origin','origin','--',$source,$staging)
      } catch { Write-Host "Clone failed; partial directory retained at $staging. Trying next source."; continue }
      # Resolve and check both paths before moving this newly-created clone.
      $resolvedStaging = [IO.Path]::GetFullPath($staging)
      if ((Split-Path $resolvedStaging -Parent) -ne $parent -or (Split-Path $repo -Parent) -ne $parent) { throw 'Clone path escaped its parent' }
      Move-Item -LiteralPath $resolvedStaging -Destination $repo
      $cloned = $true
      break
    }
    if (-not $cloned) { throw 'Both Gitee and GitHub clone attempts failed' }
  }
  if (-not (Test-Path -LiteralPath (Join-Path $repo '.git'))) { throw 'Existing destination is not a Git checkout; it was not overwritten' }
  $origin = (Native $tools.git @('-C',$repo,'remote','get-url','origin')).Trim().TrimEnd('/')
  if ($origin -notin $sources -and ($origin + '.git') -notin $sources) { throw "Unexpected origin $origin; refusing automatic updates" }
  New-Item -ItemType Directory -Force -Path $runtime | Out-Null
  $exclude = Join-Path $repo '.git/info/exclude'
  if (-not ((Get-Content -LiteralPath $exclude -ErrorAction SilentlyContinue) -contains '/.dsb-backend/')) { Add-Content -LiteralPath $exclude -Value "`n/.dsb-backend/" }
  $dirty = Native $tools.git @('-C',$repo,'status','--porcelain')
  if ($dirty.Trim()) { throw 'Checkout contains local changes. Automatic update/build stopped; no files were reset or stashed.' }
  if ($update) {
    $null = Native $tools.git @('-C',$repo,'fetch','origin')
    $upstream = (Native $tools.git @('-C',$repo,'rev-parse','--abbrev-ref','--symbolic-full-name','@{upstream}')).Trim()
    if (-not $upstream.StartsWith('origin/')) { throw 'Current branch does not track origin; update stopped' }
    $ahead = (Native $tools.git @('-C',$repo,'rev-list','--count',"${upstream}..HEAD")).Trim()
    if ([int]$ahead -ne 0) { throw 'Local commits are ahead of upstream; update stopped without reset' }
    $null = Native $tools.git @('-C',$repo,'merge','--ff-only',$upstream)
  }
  return (Native $tools.git @('-C',$repo,'rev-parse','HEAD')).Trim()
}
function Build-Backend($tools, [string]$commit) {
  $release = Join-Path $runtime "releases/$commit"
  $artifact = Join-Path $release 'backend.jar'
  if (Test-Path -LiteralPath $artifact) { return $artifact }
  Push-Location $repo
  try { $null = Native $tools.maven @('-B','-ntp','-Pproduction','-pl','playwright-server','-am','clean','package','-DskipTests','-Ddriver.platform=win32_x64') }
  finally { Pop-Location }
  $jars = @(Get-ChildItem -LiteralPath (Join-Path $repo 'playwright-server/target') -Filter 'playwright-server-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' })
  if ($jars.Count -ne 1) { throw 'Expected exactly one backend JAR after Maven package' }
  New-Item -ItemType Directory -Force -Path $release | Out-Null
  Copy-Item -LiteralPath $jars[0].FullName -Destination $artifact
  return $artifact
}
function Start-Backend($tools, [string]$artifact, [string]$commit) {
  $token = [Guid]::NewGuid().ToString('N')
  $run = Join-Path $runtime ('runs/' + $token)
  New-Item -ItemType Directory -Force -Path $run | Out-Null
  $profile = Join-Path $runtime 'profile'
  Set-Content -LiteralPath (Join-Path $run 'app.properties') -Value "server.port=$($endpoint.Port)" -Encoding ASCII
  $arguments = @("-Ddsb.managed.instance=$token", "-Dserver.port=$($endpoint.Port)", "-Dbrowser.profileDir=$profile", "-Dbrowser.chrome.cdpProfileDir=$profile", '-Dbrowser.chrome.useUserProfile=false', "-Djdk.net.unixdomain.tmpdir=$run", '-jar', $artifact)
  # Start-Process joins its argument list. Quote each complete argument, including paths with spaces.
  if (@($arguments | Where-Object { $_.Contains('"') -or $_.Contains("`n") }).Count) { throw 'Unsupported quote/newline in launch path' }
  $quoted = @($arguments | ForEach-Object { '"' + $_ + '"' })
  $process = Start-Process -FilePath $tools.java -ArgumentList $quoted -WorkingDirectory $run -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'stdout.log') -RedirectStandardError (Join-Path $run 'stderr.log')
  $state = @{ pid=$process.Id; token=$token; commit=$commit; artifact=$artifact; baseUrl=$baseUrl; logDir=$run; java=$tools.java; startedAt=[DateTime]::UtcNow.ToString('o') }
  $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
  $deadline = [DateTime]::UtcNow.AddMilliseconds($config.startupTimeoutMs)
  do {
    if ($process.HasExited) { throw "Backend exited. Read $run/stdout.log and stderr.log" }
    if (Health) { Assert-OwnedListener $state; return $state }
    Start-Sleep -Milliseconds 500
    $process.Refresh()
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Backend not healthy before deadline; state/logs retained at $runtime. It was not blindly relaunched."
}

try {
  $healthy = Health
  $state = State
  $owned = Owned-Process $state
  if ($Action -eq 'status') {
    Save-Result @{ ok=$true; healthy=$healthy; owned=[bool]$owned; repoDir=$repo; baseUrl=$baseUrl; state=$state }
    exit 0
  }
  if ($Action -eq 'start' -and $healthy) {
    Save-Result @{ ok=$true; healthy=$true; owned=[bool]$owned; reused=$true; updateDeferred=$true; state=$state; note='Existing healthy service reused; updates are applied on a stopped backend, or staged by update.' }
    exit 0
  }
  # Cross-process lock stays outside the checkout so a fresh clone remains possible.
  $parent = Split-Path $repo -Parent
  New-Item -ItemType Directory -Force -Path $parent | Out-Null
  $lockPath = Join-Path $parent ((Split-Path $repo -Leaf) + '.backend.lock')
  try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
  catch { throw 'Another backend operation holds the deployment lock; try status later' }
  if ($Action -eq 'restart' -and $healthy -and -not $owned) { throw 'Healthy server is not owned by this deployment; refusing to restart it' }
  if (-not $healthy -and $owned) { throw "Managed process is alive but unhealthy. Inspect $($state.logDir); refusing a duplicate launch." }
  $tools = Ensure-Tools
  $commit = Ensure-Repository $tools ($config.autoUpdate -or $Action -eq 'update')
  $artifact = Build-Backend $tools $commit
  if ($Action -in @('prepare','update')) {
    Save-Result @{ ok=$true; prepared=$true; commit=$commit; artifact=$artifact; healthy=$healthy; restartRequired=($healthy -and (!$state -or $state.commit -ne $commit)); repoDir=$repo }
    exit 0
  }
  # Re-check port immediately before launch (another service may have appeared during build).
  if (Health) {
    if ($Action -ne 'restart') { Save-Result @{ ok=$true; healthy=$true; reused=$true; updateDeferred=$true }; exit 0 }
    $state = State; $owned = Owned-Process $state
    if (-not $owned) { throw 'Refusing to restart a server with unverified process ownership' }
    Assert-OwnedListener $state
    $tasks = Invoke-RestMethod -Uri "$baseUrl/playwright/tasks" -TimeoutSec 5
    if ($tasks.ok -ne $true -or $null -eq $tasks.data.count -or [int]$tasks.data.count -ne 0) { throw 'Browser tasks are active (or cannot be verified); close them before restart' }
    $shutdown = Invoke-RestMethod -Method Post -Uri "$baseUrl/playwright/command" -ContentType 'application/json' -Body '{"id":1,"method":"shutdown","params":{}}' -TimeoutSec 15
    if ($shutdown.ok -ne $true) { throw 'Browser shutdown failed; backend process was not killed' }
    if (-not (Owned-Process $state)) { throw 'Process ownership changed before stop' }
    Stop-Process -Id ([int]$state.pid) -ErrorAction Stop
    Wait-Process -Id ([int]$state.pid) -Timeout 15 -ErrorAction SilentlyContinue
  }
  # An unrelated non-browser listener must not be replaced or mistaken for successful startup.
  $listener = Get-NetTCPConnection -LocalPort $endpoint.Port -State Listen -ErrorAction SilentlyContinue
  if ($listener) { throw "Port $($endpoint.Port) is occupied; select a different baseUrl" }
  $state = Start-Backend $tools $artifact $commit
  Save-Result @{ ok=$true; healthy=$true; owned=$true; state=$state; repoDir=$repo; baseUrl=$baseUrl }
} catch {
  Save-Result @{ ok=$false; error=$_.Exception.Message; repoDir=$repo; baseUrl=$baseUrl }
  Write-Error $_.Exception.Message -ErrorAction Continue
  exit 1
} finally { if ($lock) { $lock.Dispose() } }
