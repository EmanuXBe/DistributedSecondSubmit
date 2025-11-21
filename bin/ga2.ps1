param(
  [string]$GaHost = "127.0.0.1" # IP de la réplica (B)
)

& "$PSScriptRoot\setup_env.ps1"

$env:GA2_ROUTER_HOST="0.0.0.0"; $env:GA2_ROUTER_PORT="6070"
$env:GA2_REP_HOST="0.0.0.0";    $env:GA2_REP_PORT="6080"
$env:GA_HOST=$GaHost;          $env:GA_PORT="6057"
$env:P_BOOK_DB="data/primary/books.csv"
$env:P_LOANS_PATH="data/primary/loans.csv"
$env:P_PENDING_LOG="data/primary/pending.log"

java -cp "$env:CP" org.example.storage.StoragePrimary
