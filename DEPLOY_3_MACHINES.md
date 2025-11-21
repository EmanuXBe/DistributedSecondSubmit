# Deploy 3 máquinas (ultra simple, copiar/pegar)

Puertos por defecto (cámbialos solo si chocan): GC REQ 6055 / GC PUB 6060 / Actor 6056 / GA 6057 / GA2 6070/6080.
IPs a usar: `IP_A` (GA2/Primary), `IP_B` (GA/Replica), `IP_C` (Front).

## Paso 0 (solo 1 vez en cada máquina)
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
chmod +x gradlew && ./gradlew build
JEROMQ_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.zeromq/jeromq/0.5.3 -name 'jeromq-0.5.3.jar' -print -quit)
export CP="$(pwd)/build/libs/BibliotecaDistribuida-1.0-SNAPSHOT.jar:$(pwd)/build/classes/java/main:${JEROMQ_JAR}"
```

## Máquina A (Primary – GA2)
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_A=192.168.0.10 IP_B=192.168.0.11
GA2_ROUTER_HOST=0.0.0.0 GA2_ROUTER_PORT=6070 \
GA2_REP_HOST=0.0.0.0 GA2_REP_PORT=6080 \
GA_HOST=$IP_B GA_PORT=6057 \
P_BOOK_DB=data/primary/books.csv P_LOANS_PATH=data/primary/loans.csv \
P_PENDING_LOG=data/primary/pending.log \
java -cp "$CP" org.example.storage.StoragePrimary
```

## Máquina B (Replica – GA)
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_A=192.168.0.10
GA_BIND_HOST=0.0.0.0 GA_PORT=6057 \
GA2_HOST=$IP_A GA2_ROUTER_PORT=6070 GA2_REP_PORT=6080 \
R_BOOK_DB=data/replica/books.csv R_LOANS_PATH=data/replica/loans.csv \
R_PENDING_LOG=data/replica/pending.log \
java -cp "$CP" org.example.storage.StorageReplica
```

## Máquina C (Front: actores, balanceador y cliente)
Terminal 1 – LoanActor
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_A=192.168.0.10 IP_B=192.168.0.11 IP_C=192.168.0.12
ACTOR_BIND_HOST=0.0.0.0 ACTOR_PORT=6056 \
GA_HOST=$IP_B GA_PORT=6057 \
GA2_HOST=$IP_A GA2_PORT=6080 \
java -cp "$CP" org.example.actor.LoanActor
```

Terminal 2 – ReturnRenewalActor
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_A=192.168.0.10 IP_B=192.168.0.11 IP_C=192.168.0.12
GA_HOST=$IP_B GA_PORT=6057 \
GA2_HOST=$IP_A GA2_PORT=6080 \
GC_HOST=$IP_C GC_PUB_PORT=6060 \
java -cp "$CP" org.example.actor.ReturnRenewalActor
```

Terminal 3 – LoadBalancer
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_C=192.168.0.12
GC_BIND_HOST=0.0.0.0 GC_PS_PORT=6055 GC_PUB_PORT=6060 \
ACTOR_HOST=$IP_C ACTOR_PORT=6056 \
java -cp "$CP" org.example.front.LoadBalancer
```

Terminal 4 – Cliente (manual o automático)
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_C=192.168.0.12
GC_HOST=$IP_C GC_PORT=6055 REQUESTS_FILE=data/requests/requests.txt \
java -cp "$CP" org.example.front.RequestProducer
```
Para probar automático:
```bash
cd /home/sistemas/Documentos/BibliotecaDistribuidos/BibliotecaDistribuida
export IP_C=192.168.0.12
GC_HOST=$IP_C GC_PORT=6055 ./bin/test_workflow.sh 1
```

## Verificación rápida
Replica (B):
```bash
grep "^1," data/replica/books.csv && grep "^1," data/replica/loans.csv
```
Primario (A):
```bash
grep "^1," data/primary/books.csv && grep "^1," data/primary/loans.csv
```
