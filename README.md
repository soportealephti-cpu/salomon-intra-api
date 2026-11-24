# Intranet SALOMON — Backend

Copia adaptada de ALEPH con base de datos independiente.

# SALOMON Intranet · API

Java 21, Spring Boot 4.1.2, PostgreSQL 17 y Flyway.

## Configuración

La API usa DB_HOST (aleph-postgres), DB_PORT (5432), DB_USERNAME (salomon_intra_app) y DB_PASSWORD (obligatoria). La URL apunta a salomon_intra y al esquema intra. DB_POOL_MAX_SIZE limita el pool a cinco conexiones por defecto. .env.example no contiene secretos; Spring no carga automáticamente un archivo .env, por lo que hay que exportar las variables en desarrollo.

Al arrancar se comprueba la conexión real, el nombre de la base, los privilegios limitados del usuario y la migración inicial. La API no inicia correctamente si falta alguno. No usar salomon_admin como usuario de ejecución.

## Migraciones y permisos

El bootstrap revisable está en deploy/postgres/bootstrap.sql. Un administrador debe ejecutarlo conectado únicamente a salomon_intra, con ON_ERROR_STOP=1 y después de habilitar sus conexiones. No crea bases, no contiene contraseñas ni activa LOGIN. Nunca ejecutarlo en salomon_web.

| Rol | Acceso previsto |
| --- | --- |
| salomon_admin | Administración inicial; no se entrega a la API. |
| salomon_intra_migrator | CONNECT y propiedad de los esquemas intra y flyway; sin privilegios administrativos. |
| salomon_intra_app | CONNECT, USAGE de intra y DML sobre objetos de aplicación; sin CREATE ni acceso al historial Flyway. |

Los roles creados por el bootstrap quedan NOLOGIN. Establecer contraseñas distintas por un canal seguro y habilitar LOGIN sólo después de revisar atributos y membresías. Los permisos predeterminados se aplican a objetos futuros creados por el migrador en intra; no reparan automáticamente objetos o roles preexistentes.

El perfil migrate es un trabajo puntual: recibe DB_MIGRATION_PASSWORD (y opcionalmente DB_MIGRATION_USERNAME, por defecto salomon_intra_migrator), ejecuta Flyway y termina. No necesita la contraseña de ejecución. El historial está en el esquema privado flyway; las tablas de aplicación están en intra. Clean y baseline automático están deshabilitados; los esquemas deben existir.

La API normal mantiene Flyway deshabilitado y sólo recibe la contraseña de ejecución. Ejecutar primero una única instancia del migrador, comprobar salida 0 y luego arrancar la API con la misma versión de imagen. Revisar compatibilidad de migraciones con versiones anteriores; no deshacer migraciones automáticamente.

**Estado del servidor verificado el 16/11/2026:** se configuraron reglas específicas al inicio de pg_hba.conf para los dos roles de intranet, con SCRAM-SHA-256 por socket local y TCP desde aleph_network (172.27.0.0/16), y rechazo expreso de otras bases y de replicación. Las reglas anteriores se conservaron byte por byte. Se respaldó el archivo y se validó pg_hba_file_rules antes de recargar, sin reiniciar PostgreSQL. No se cambiaron los permisos de salomon_web ni se publicaron puertos. Ambos roles tienen LOGIN. Las conexiones JDBC autenticadas comprobaron identidad, mínimo privilegio, DDL permitido sólo al migrador y rechazo de salomon_web y postgres; además se verificaron rechazos locales y TCP sin contraseña. Flyway 12.4.0 aplicó V1 con salomon_intra_migrator, validó el historial y confirmó una segunda ejecución sin cambios. Las pruebas DDL/DML se revirtieron sin dejar tablas de prueba. La página pública y el acceso de salomon_app siguieron funcionando. El respaldo previo fue restaurado en una base de comprobación y su integridad se verificó antes de migrar; la copia permanece en el mismo disco del servidor, por lo que sigue pendiente una copia externa y una política de respaldos recurrentes.

## Salud

| Ruta | Uso |
| --- | --- |
| /actuator/health/database | Conexión JDBC real: 200 UP o 503 DOWN. |
| /actuator/health/readiness | Disponibilidad del proceso y de PostgreSQL. |
| /actuator/health/liveness | Vida del proceso, independiente de la base. |
| /api/v1/status | Estado básico; no prueba PostgreSQL. |

La salud no muestra detalles ni componentes de la conexión. Mantener endpoints y registros en la red privada: aún no hay autenticación ni módulos de negocio.

## Pruebas

Ejecutar `mvn -B -ntp clean verify` con JDK 21 y un usuario sin privilegios. Las pruebas descargan y arrancan un PostgreSQL 17.11 temporal mediante una dependencia exclusiva de pruebas; initdb no admite root. No requieren Docker, credenciales ni acceso al VPS. Las contraseñas de prueba se generan durante la ejecución.

Se comprueban la conexión como usuario restringido, la migración y validación de Flyway, la segunda ejecución sin cambios, la denegación de DDL e historial, la salud HTTP sin detalles, el estado DOWN/503 al perder acceso y la terminación del perfil migrate. CI ejecuta la misma verificación. Estas pruebas aisladas no sustituyen la prueba autenticada del VPS.

## Portainer: preparado, no desplegado

Los archivos deploy/portainer/compose.yml y compose.migrate.yml reutilizan la red existente aleph_network, donde se observó aleph-postgres. No declaran PostgreSQL, no montan su volumen, no publican puertos y no modifican servicios de la web. La red es compartida con otros servicios; no es una red exclusiva de la intranet.

Después de pasar CI, construir el Dockerfile y publicar la imagen en un registro privado elegido para el proyecto. Definir INTRA_API_IMAGE con un tag inmutable o digest revisado, idéntico para API y migrador. Los archivos no construyen ni publican imágenes automáticamente.

En Docker Standalone, preparar los archivos de secretos de forma segura en el VPS y definir sus rutas absolutas con INTRA_DB_PASSWORD_FILE e INTRA_MIGRATION_PASSWORD_FILE. Verificar que Portainer puede montar estos archivos. El secreto de ejecución debe ser legible por UID 10001 en el contenedor. Los secretos se cargan con configtree desde /run/secrets; la API no recibe el secreto del migrador. No guardar secretos en Git, imágenes, URLs JDBC ni argumentos de procesos. Se admiten variables de entorno para desarrollo; los compose evitan contraseñas en variables del contenedor.

Impacto previsto: un contenedor API nuevo, limitado a 512 MiB, 0.75 CPU y cinco conexiones, más un job puntual de migración de 384 MiB y 0.5 CPU. No hay cambios de proxy ni DNS. Antes de desplegar, revisar memoria y disco libres, backup recuperable de salomon_intra, permisos, DNS Docker y conectividad. Migrar, verificar salida 0, desplegar API y comprobar readiness desde la misma red. No publicar rutas ni conectar el frontend antes de revisar autenticación y proxy.

La configuración de PostgreSQL y la migración inicial ya se comprobaron en el VPS. La API sigue sin desplegarse y el PR debe revisarse antes de fusionar. Faltan la imagen de aplicación aprobada, los secretos de ejecución montados y la comprobación de readiness una vez se autorice el despliegue. Conservar una imagen anterior compatible para revertir la API; nunca usar Flyway clean.
