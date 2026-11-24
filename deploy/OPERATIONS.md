# Entorno de pruebas de SALOMON

El stack `salomon-intra` se administra en Portainer. Su definición está en
`portainer/compose.yml`. No incluye PostgreSQL ni cambia servicios de la web pública.

| Servicio | Red y dirección | Salud |
| --- | --- | --- |
| api | `aleph_network` hacia `aleph-postgres:5432`; red privada del stack hacia frontend | `/actuator/health/database`, `/actuator/health/readiness` |
| frontend | Sólo red interna del stack; API `http://api:8080` desde Node, nunca desde el navegador | `/api/health` vida; `/api/readiness` comprueba API y PostgreSQL |
| aleph-postgres | Existente, persistencia existente, sin puerto publicado | No lo recrea este stack |

## Acceso restringido

Frontend se vincula exclusivamente a `127.0.0.1:13020` en el VPS. No hay entrada
pública ni cambios de DNS/proxy. Acceder mediante un túnel SSH autenticado desde el
equipo de desarrollo, reenviando su puerto local 13020 al 127.0.0.1:13020 del VPS.
Con el túnel activo, abrir `http://127.0.0.1:13020`. La API no publica puertos.
No publicar esta aplicación antes de implementar autenticación y autorización.

## Variables y secretos

En Portainer definir `INTRA_API_IMAGE` e `INTRA_FRONT_IMAGE` con los digests
registrados en el resumen de cada workflow de GitHub Actions. No usar `latest`.
`INTRA_DB_PASSWORD_FILE` apunta al archivo absoluto del VPS con la contraseña
existente de `salomon_intra_app`: propietario UID 10001, modo 0400 y directorio
restringido. Se monta sólo lectura como `/run/secrets/DB_PASSWORD`. No poner su
contenido en variables del stack, Git, argumentos, imágenes ni registros.
El frontend sólo necesita `SALOMON_API_INTERNAL_URL`, sin prefijo NEXT_PUBLIC.
La API no recibe credenciales del migrador. GHCR requiere un token separado
`read:packages` en el registro autenticado de Portainer; nunca incluirlo en compose.

Para desarrollo local, usar los ejemplos `.env.example`. Next carga `.env.local`;
Spring requiere variables exportadas o un gestor de secretos. No publicar 5432 para
desarrollo: usar acceso privado/túnel autorizado y una base de desarrollo separada
si se necesitan datos desechables. No usar `salomon_web`.

## Nuevas versiones

Crear rama, abrir PR y esperar CI: backend Maven/JDK21 y PostgreSQL temporal;
frontend lint, TypeScript, build y Playwright. Después de fusionar, CI publica
GHCR privado con etiqueta `sha-<commit>` y registra digest. El job de publicación
depende del éxito de las pruebas. El despliegue es manual y revisable en Portainer.
Guardar los digests anteriores antes de cambiar sólo el servicio que se actualiza.
Comprobar los tres endpoints de readiness/database y cargar la portada a través
del túnel. Verificar estado healthy, reinicios, memoria y registros.

V1 ya está aplicada: NO ejecutar bootstrap ni migrador para este despliegue.
Para V2 y posteriores, añadir SQL nuevo `V2__descripcion.sql`, no editar V1.
Probar y revisar compatibilidad hacia atrás. Antes de migrar, exigir respaldo
externo comprobado y una restauración de ensayo. Usar el job `compose.migrate.yml`
una sola vez con la misma imagen revisada, secreto exclusivo del migrador y salida
0. Después desplegar la API. Nunca habilitar Flyway en el servicio de ejecución.

## Diagnóstico y reversión

Consultar Portainer: Containers, Health, Stats y Logs. Rotación de registros 3x10MB
por servicio, sin logging de parámetros SQL ni solicitudes con credenciales.
Healthchecks no muestran detalles. `unless-stopped` recupera salidas de proceso;
Docker Standalone no reinicia automáticamente un contenedor sólo por unhealthy.
Si queda unhealthy, revisar antes de reiniciar sólo el nuevo servicio afectado.

Para revertir: restaurar el digest anterior de API/frontend en el mismo stack,
actualizar y comprobar salud. No bajar todo el stack ni tocar PostgreSQL.
Las migraciones no se deshacen automáticamente; una restauración de datos requiere
revisión separada. Conservar imágenes anteriores y el compose anterior.

## Respaldos

Las copias previas de `salomon_intra` se restauraron correctamente, pero permanecen
en el mismo disco del VPS. Eso NO constituye respaldo externo. Falta un destino
independiente (S3 compatible, NAS/servidor SSH o almacenamiento de otra cuenta) y
credenciales privadas introducidas fuera de Git. No marcar esta tarea completada
hasta subir, descargar y restaurar una copia real.

Política preparada: dump diario de `salomon_intra` a las 02:30 America/Lima,
cifrado antes de salir del VPS; conservar 7 diarios, 4 semanales y 6 mensuales;
verificar integridad en cada ejecución y restauración mensual en base aislada.
Objetivos iniciales: RPO 24h y RTO por medir con la restauración externa.
Respaldar también HBA y definición de despliegue sin contraseñas. Credenciales y
clave de recuperación se custodian por separado. Alertar fallo o antigüedad >26h.
No hay tarea externa activa hasta configurar y verificar el destino.
