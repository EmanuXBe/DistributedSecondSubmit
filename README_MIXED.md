# Deploy mixto: 2 Windows (GA2/GA) + 1 Linux (Front)

Puertos por defecto: GC REQ 6055 / GC PUB 6060 / Actor 6056 / GA 6057 / GA2 6070/6080. Roles: **Windows A = GA2 (primary)**, **Windows B = GA (replica)**, **Linux C = Front**.

## Prerrequisitos (una vez)
- Java 17 en las 3 máquinas.
- `./gradlew build` ejecutado al menos una vez (en Windows `.\gradlew.bat build`).

### Windows: preparar entorno (PowerShell)
```powershell
cd C:\ruta\BibliotecaDistribuida
.\gradlew.bat build
bin\setup_env.ps1   # deja CP listo
```

### Linux: preparar entorno (bash)
```bash
cd /home/usuario/BibliotecaDistribuida
chmod +x gradlew && ./gradlew build
source bin/env.sh   # deja CP listo
```

## Arranque ultra corto (usa scripts)

### Máquina A (Windows GA2 / Primary)
```powershell
cd C:\ruta\BibliotecaDistribuida
bin\ga2.ps1 IP_B    # IP_B = IP de la réplica (GA en Windows B)
```

### Máquina B (Windows GA / Replica)
```powershell
cd C:\ruta\BibliotecaDistribuida
bin\ga.ps1 IP_A     # IP_A = IP del primary (GA2 en Windows A)
```

### Máquina C (Linux Front)
```bash
cd /home/usuario/BibliotecaDistribuida
# Terminal 1: LoanActor
./bin/loan_actor.sh IP_B IP_A
# Terminal 2: Return/Renewal Actor
./bin/return_actor.sh IP_B IP_A IP_C
# Terminal 3: LoadBalancer
./bin/gc.sh IP_C
# Terminal 4: Cliente interactivo
./bin/client.sh IP_C
# (opcional) Cliente automático
./bin/client_auto.sh IP_C 1
```

## Verificación rápida
- En B (Windows GA): `Select-String "^1," data/replica/books.csv` y `Select-String "^1," data/replica/loans.csv`
- En A (Windows GA2): `Select-String "^1," data/primary/books.csv` y `Select-String "^1," data/primary/loans.csv`

Notas:
- Sustituye `IP_A`, `IP_B`, `IP_C` por las IP reales de cada máquina.
- Si cambias la ruta del repo, ajusta CP en cada máquina respetando `;` en Windows y `:` en Linux.
