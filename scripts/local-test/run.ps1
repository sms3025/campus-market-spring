param(
  [ValidateSet('Build','Start','Stop','Seed','Measure','Reset')][string]$Action='Start',
  [ValidateSet('smoke','small','medium','large')][string]$Scale='smoke',
  [ValidateSet('uniform','skewed')][string]$Distribution='uniform',
  [ValidateSet('items','likes','admin','fcm')][string]$Scenario='items',
  [ValidateRange(1,1000)][int]$Rate=10,
  [ValidateRange(1,100)][int]$PageSize=10,
  [ValidateRange(0,100000)][int]$Page=0,
  [string]$Duration='3m', [string]$Warmup='1m',
  [ValidateRange(1,100)][int]$Fanout=1,
  [switch]$ConfirmReset
)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repoRoot
$composeArgs=@('compose','-f','compose.local-test.yml')
if(Test-Path docker/local-test/.env){$composeArgs+=@('--env-file','docker/local-test/.env')}
function Compose { & docker @composeArgs @args; if($LASTEXITCODE -ne 0){throw "Docker command failed: $args"} }
switch($Action){
  Build {
    & ./gradlew.bat bootJar --console=plain
    if($LASTEXITCODE -ne 0){throw 'Gradle build failed. Configure JAVA_HOME for Java 17.'}
    Compose build app
  }
  Start { Compose --profile app up -d --wait }
  Stop { Compose --profile app stop }
  Seed {
    Compose --profile app stop app
    $env:SEED_SCALE=$Scale; $env:SEED_DISTRIBUTION=$Distribution; $env:SEED_FANOUT="$Fanout"
    $env:CODE_VERSION=(& git rev-parse HEAD).Trim()
    New-Item -ItemType Directory -Force artifacts/performance | Out-Null
    Compose run --rm seed
    $archive=Join-Path 'artifacts/performance' ('seed-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+$Scale+'.json')
    Copy-Item -LiteralPath artifacts/performance/seed-manifest.json -Destination $archive
  }
  Reset {
    if(!$ConfirmReset){throw 'Reset deletes only campus_local_test DB and this Compose Redis data. Re-run with -ConfirmReset.'}
    Compose --profile app stop app
    # Fixed DB name; root password stays inside the container environment.
    Compose exec -T mariadb sh -c 'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS campus_local_test; CREATE DATABASE campus_local_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"'
    Compose exec -T redis redis-cli FLUSHDB
    Write-Host 'Dedicated test DB reset. Run Seed, then Start.'
  }
  Measure {
    $manifestPath=Join-Path $repoRoot 'artifacts/performance/seed-manifest.json'
    if(!(Test-Path $manifestPath)){throw 'Seed manifest missing. Run Seed first.'}
    $apiBase=if($env:LOCAL_API_URL){$env:LOCAL_API_URL}else{'http://localhost:8080'}
    $dataset=Invoke-RestMethod "$apiBase/local-test/api/dataset"
    if($dataset.state -ne 'complete'){throw 'Database seed is incomplete.'}
    $savedManifest=Get-Content -Raw -Encoding UTF8 $manifestPath | ConvertFrom-Json
    $activeManifest=$dataset.manifest | ConvertFrom-Json
    if($savedManifest.completedAt -ne $activeManifest.completedAt){throw 'Manifest does not match the active database.'}
    $id=(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+"-$Scenario-p$PageSize-r$Rate"
    $folder=Join-Path $repoRoot "artifacts/performance/$id"
    New-Item -ItemType Directory -Force $folder | Out-Null
    Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $folder 'seed-manifest.json')
    $env:SCENARIO=$Scenario;$env:RATE="$Rate";$env:PAGE_SIZE="$PageSize";$env:PAGE="$Page"
    $env:RUN_ID="$id/warmup";$env:DURATION=$Warmup
    New-Item -ItemType Directory -Force (Join-Path $folder 'warmup') | Out-Null
    # Warm-up output is discarded anyway, so a failed threshold here must not skip the measurement.
    try { Compose run --rm k6 | Out-File -Encoding utf8 (Join-Path $folder 'warmup.log') }
    catch { Write-Host "Warm-up did not meet its thresholds: $_" }
    $env:RUN_ID=$id;$env:DURATION=$Duration
    $before=Invoke-RestMethod "$apiBase/local-test/api/dataset"
    $appId=(& docker @composeArgs ps -q app)
    if($appId){
      $appEnv=(& docker inspect --format '{{json .Config.Env}}' $appId | ConvertFrom-Json)
      @{imageAndLimits=(& docker inspect --format '{{.Image}} cpuNano={{.HostConfig.NanoCpus}} memoryBytes={{.HostConfig.Memory}}' $appId);settings=@($appEnv | Where-Object {$_ -match '^(FCM_REAL|FCM_DELAY_MS|JAVA_TOOL_OPTIONS|DB_POOL_SIZE)='})} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $folder 'app-runtime.json')
    }
    (Invoke-WebRequest -UseBasicParsing "$apiBase/actuator/prometheus").Content | Set-Content -Encoding utf8 (Join-Path $folder 'metrics-before.txt')
    @{runId=$id;scenario=$Scenario;rate=$Rate;pageSize=$PageSize;page=$Page;duration=$Duration;warmup=$Warmup;startedAt=(Get-Date).ToUniversalTime().ToString('o');commit=(& git rev-parse HEAD);dirty=(@(& git status --porcelain).Count -gt 0);currentItems=$before.currentItems;fcmDelayMs=$env:FCM_DELAY_MS;docker=(& docker info --format '{{.OSType}} CPUs={{.NCPU}} Memory={{.MemTotal}}')} | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $folder 'conditions.json')
    try{Compose run --rm k6 | Tee-Object -FilePath (Join-Path $folder 'console.log')}
    finally{
      (Get-Date).ToUniversalTime().ToString('o') | Set-Content (Join-Path $folder 'ended-at.txt')
      Invoke-RestMethod "$apiBase/local-test/api/dataset" | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $folder 'dataset-after.json')
      (Invoke-WebRequest -UseBasicParsing "$apiBase/actuator/prometheus").Content | Set-Content -Encoding utf8 (Join-Path $folder 'metrics-after.txt')
    }
    Write-Host "Report: $folder/report.html"
  }
}
