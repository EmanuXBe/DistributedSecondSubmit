$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jar  = Get-ChildItem -Path "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.zeromq\jeromq\0.5.3" -Filter "jeromq-0.5.3.jar" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (-not $env:CP) {
  $env:CP = "$root\build\libs\BibliotecaDistribuida-1.0-SNAPSHOT.jar;$root\build\classes\java\main;$jar"
}
set-location $root
write-host "CP listo: $env:CP"
