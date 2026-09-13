# Sequential baseline sweep. Runs one measurement at a time so the results share one machine state.
param(
  [ValidateSet('Likes','Search','Fcm','Register')][string]$Part='Likes',
  [ValidateRange(1,5)][int]$Repeats=3,
  [string]$Duration='3m',
  [string]$Warmup='1m',
  [string]$Label='register'
)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repoRoot
$runner=Join-Path $PSScriptRoot 'run.ps1'
$failures=@()
# A k6 threshold abort must not hide the remaining cases, so each case is isolated.
function Measure-Case([string]$Scenario,[int]$PageSize,[int]$Rate){
  Write-Host "=== $Scenario page=$PageSize rate=$Rate"
  try { & $runner -Action Measure -Scenario $Scenario -PageSize $PageSize -Rate $Rate -Duration $Duration -Warmup $Warmup }
  catch {
    $script:failures+=[pscustomobject]@{scenario=$Scenario;pageSize=$PageSize;rate=$Rate;error="$_"}
    Write-Host "=== CASE FAILED $Scenario page=$PageSize rate=$Rate : $_"
  }
}
switch($Part){
  Likes {
    # N+1 growth by page size, repeated on identical data.
    foreach($repeat in 1..$Repeats){
      foreach($size in 10,50,100){ Measure-Case 'likes' $size 10 }
    }
  }
  Search {
    # Request rate ramp on the item search, then the admin listing.
    foreach($rate in 10,30,100){ Measure-Case 'items' 10 $rate }
    # The admin listing collapses at 10/s on medium data, so a 1/s case is measured first.
    Measure-Case 'admin' 10 1
    Measure-Case 'admin' 10 10
  }
  Fcm {
    # Requires a seed with -Fanout and FCM_DELAY_MS set before the app started.
    if(-not $env:FCM_DELAY_MS){throw 'Set $env:FCM_DELAY_MS and restart the app before the FCM baseline.'}
    foreach($repeat in 1..$Repeats){ Measure-Case 'fcm' 10 1 }
  }
  Register {
    # Single-request registration cost for the current dataset. No load tool involved.
    node (Join-Path $PSScriptRoot 'measure-register.mjs') $Label 20 5
  }
}
if($failures.Count -gt 0){
  $failures | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $repoRoot "artifacts/performance/suite-failures-$Part.json")
  Write-Host "=== $($failures.Count) case(s) failed; see artifacts/performance/suite-failures-$Part.json"
}
if($Part -ne 'Register'){ node (Join-Path $PSScriptRoot 'report.mjs') }
