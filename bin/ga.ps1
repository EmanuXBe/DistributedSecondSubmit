param(
  [string]$Ga2Host = "127.0.0.1" # IP del primary (A)
)

if ($Ga2Host -eq "IP_A" -or [string]::IsNullOrWhiteSpace($Ga2Host)) {
  Write-Error "Debes indicar la IP real del primary (GA2). Ejemplo: .\\ga.ps1 10.0.0.5"
  exit 1
}

& "$PSScriptRoot\setup_env.ps1"

$env:GA_BIND_HOST="0.0.0.0"; $env:GA_PORT="6057"
$env:GA2_HOST=$Ga2Host;      $env:GA2_ROUTER_PORT="6070"; $env:GA2_REP_PORT="6080"
$env:R_BOOK_DB="data/replica/books.csv"
$env:R_LOANS_PATH="data/replica/loans.csv"
$env:R_PENDING_LOG="data/replica/pending.log"

java -cp "$env:CP" org.example.storage.StorageReplica
