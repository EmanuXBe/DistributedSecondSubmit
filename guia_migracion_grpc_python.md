# Guía de migración a gRPC con Python

- **Objetivo:** migrar servicios existentes a gRPC usando Python, siguiendo un enfoque contract-first, generación de stubs y despliegue sin interrupciones.
- **Cuándo usarlo:** baja latencia, contratos tipados, streaming bidireccional y compatibilidad multilenguaje.

## Prerrequisitos
- Python 3.10+ y `pip`/`pipx`.
- Dependencias base: `grpcio`, `grpcio-tools`, `protobuf`; opcional: `grpcio-status` y `grpclib` (async).
- Certificados TLS (propios o PKI) si expones tráfico fuera de una red confiable.

## Pasos de migración (alto nivel)
1. **Inventario y contratos**: lista endpoints/colas actuales, payloads, SLA y errores esperados; define si habrá breaking changes.
2. **Diseña el `.proto` (contract-first)**: modela servicios y mensajes; usa enums para estados; versiona por paquete/servicio (`package billing.v1;`).
3. **Estructura recomendada**
   ```
   /proto/              # contratos .proto
   /src/server/         # servidor Python
   /src/client/         # cliente o SDK Python
   /tests/              # unit, integración y compatibilidad
   pyproject.toml       # dependencias y tooling
   ```
4. **Ejemplo de contrato**
   ```proto
   syntax = "proto3";
   package billing.v1;
   option go_package = "billing/v1"; // si generas stubs en otros lenguajes

   message InvoiceRequest { string id = 1; }
   message InvoiceReply { string id = 1; double total = 2; string currency = 3; }

   service BillingService {
     rpc GetInvoice(InvoiceRequest) returns (InvoiceReply);
   }
   ```
5. **Genera stubs en Python**
   ```bash
   python -m grpc_tools.protoc \
     -I proto \
     --python_out=src \
     --grpc_python_out=src \
     proto/billing/v1/billing.proto
   ```
6. **Servidor mínimo (sync)**
   ```python
   # src/server/main.py
   import grpc
   from concurrent import futures
   from billing.v1 import billing_pb2, billing_pb2_grpc

   class BillingService(billing_pb2_grpc.BillingServiceServicer):
       def GetInvoice(self, request, context):
           # lógica real de negocio aquí
           return billing_pb2.InvoiceReply(id=request.id, total=123.45, currency="USD")

   def serve():
       server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
       billing_pb2_grpc.add_BillingServiceServicer_to_server(BillingService(), server)
       server.add_insecure_port("[::]:50051")  # cambia a TLS en producción
       server.start()
       server.wait_for_termination()

   if __name__ == "__main__":
       serve()
   ```
7. **Cliente mínimo**
   ```python
   # src/client/main.py
   import grpc
   from billing.v1 import billing_pb2, billing_pb2_grpc

   def main():
       with grpc.insecure_channel("localhost:50051") as channel:
           stub = billing_pb2_grpc.BillingServiceStub(channel)
           resp = stub.GetInvoice(billing_pb2.InvoiceRequest(id="inv-1"))
           print(resp)

   if __name__ == "__main__":
       main()
   ```
8. **TLS y autenticación:** usa `ssl_channel_credentials` y `server_credentials`; mTLS para servicio-a-servicio; JWT en metadata o autorización en proxy (Envoy/NGINX).
9. **Observabilidad:** interceptores para logging (sin PII), métricas Prometheus/OpenTelemetry por método y estado, tracing con OpenTelemetry propagando contexto en metadata.
10. **Errores:** emplea `grpc.StatusCode` y `grpcio-status` para detalles estructurados (`INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED`).
11. **Testing:** unit (lógica de dominio), integración (servidor in-process + cliente real), compatibilidad (snapshot de .proto; falla si cambia sin versión).
12. **Estrategia de rollout:** despliegue paralelo REST/colas + gRPC; usa adaptador REST↔gRPC temporal; canary/blue-green con métricas de latencia y errores.
13. **Versionado de contratos:** paquetes por versión (`package billing.v1`); no reutilices IDs; agrega campos con nuevos números; documenta políticas de soporte.
14. **Despliegue:** contenedoriza servidor; expone 50051 o el puerto elegido; proxy L4/L7 para TLS, balanceo y observabilidad; ajusta keepalive y tamaños máximos de mensaje.
15. **Checklist rápido**
    - [ ] Contratos `.proto` revisados y versionados
    - [ ] Stubs generados y publicados (si hay SDK)
    - [ ] TLS/mTLS configurado
    - [ ] Logs, métricas y traces activos
    - [ ] Tests unit + integración + compat pasan
    - [ ] Plan de rollback/canary definido
    - [ ] Dashboards y alertas en producción

## Cómo aplicar según la arquitectura
- **Monolito → gRPC interno:** extrae módulos a servicios pequeños; adaptador HTTP→gRPC para frontends.
- **Microservicios REST → gRPC:** prioriza rutas de mayor carga; conserva REST público si clientes externos no migran.
- **Event-driven:** usa gRPC para comandos/sync y eventos para pub/sub; gRPC streaming si necesitas flujos continuos.
- **Multilenguaje:** genera stubs para cada lenguaje; fija versiones de `protoc` y `protobuf` en CI.

## Próximos pasos mínimos
1. Confirmar dependencias y crear `proto/` con el contrato inicial.
2. Generar stubs y montar servidor/cliente de referencia con TLS antes de producción.
3. Añadir pruebas de compatibilidad para evitar romper contratos al iterar.