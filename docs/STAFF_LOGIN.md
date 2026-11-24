# Acceso de personal SALOMON

## Activación
La nueva migración V2 crea `intra.staff_users` y `intra.staff_sessions` en `salomon_intra`.
Primero ejecutar el migrador existente con el rol `salomon_intra_migrator`, siguiendo
`deploy/OPERATIONS.md`, sin cambiar V1 ni volver a crear la base. Después desplegar
la API con la imagen aprobada. No aplicar esta migración a `salomon_web`.

No se entrega usuario ni contraseña predeterminados y **no hay registro público**.
Un administrador crea cada usuario mediante un procedimiento controlado; generar
un hash BCrypt (coste 12 o superior) fuera del navegador y sin registrarlo en logs,
y ejecutar por un canal administrativo seguro:

```sql
INSERT INTO intra.staff_users (username, display_name, password_hash, role)
VALUES (lower(:username), :display_name, :bcrypt_hash, 'PERSONAL');
```

No poner contraseñas ni hashes reales en Git, tickets o chats. Consultar al DBA
si el procedimiento de aprovisionamiento requiere permisos adicionales.

## API privada
- `POST /api/v1/auth/login` recibe JSON `{"username":"...", "password":"..."}`
  y devuelve token opaco y datos mínimos del usuario.
- `GET /api/v1/auth/me` requiere `Authorization: Bearer <token>`.
- `POST /api/v1/auth/logout` revoca el token.

El frontend debe llamar desde el servidor; nunca publicar la API ni incluir la
contraseña de PostgreSQL en el cliente. El token sólo debe guardarse en una cookie
HttpOnly Secure en producción (HTTPS). La sesión dura ocho horas, se revoca al salir,
y se bloquea el usuario durante 15 minutos después de cinco errores consecutivos.
Los permisos por módulo deberán comprobarse **en la API** al implementar cada módulo:
no basta con ocultar opciones del frontend.

Pendiente antes de producción: pruebas integradas de autenticación, control
de intentos distribuidos por IP, supervisión, auditoría de accesos y flujo
administrativo de altas/cambios de contraseñas.


## Aprovisionamiento inicial del superadministrador

Después de aplicar la migración V2, en el stack de API de Portainer
definir temporalmente la variable `INTRA_SA_INITIAL_PASSWORD` con la
contraseña inicial acordada por el administrador, y desplegar la API.

En el arranque, `InitialSuperadminProvisioner` crea solamente si no existe:
- username: `sa` (la pantalla admite `SA` porque normaliza a minúsculas).
- display_name: `Superadministrador`.
- role: `SA`.
- password_hash: BCrypt con coste 12, nunca contraseña en claro en PostgreSQL.

Si ya existe `sa`, el proceso no modifica su contraseña ni su rol.
Al confirmar el primer inicio de sesión, **eliminar la variable de Portainer**
y actualizar el stack para que la contraseña temporal no permanezca en la
configuración del contenedor. Cambiar inmediatamente la contraseña desde
`Mi cuenta` en la intranet por una clave nueva de 12 caracteres o más.
El endpoint `POST /api/v1/auth/change-password` valida la clave anterior,
actualiza el hash y revoca las demás sesiones del usuario.

El rol `SA` todavía es una etiqueta de cuenta; cada futuro módulo deberá
implementar y probar su autorización en la API antes de conceder capacidades
administrativas. No habilitar permisos globales simplemente por confiar en el
frontend. La variable de arranque debe protegerse como secreto y no escribirse
en Git ni en los logs.


## Panel inicial de asistencia (V3)

Aplicar `V3__attendance_dashboard.sql` mediante el servicio migrador de Portainer,
con el respaldo y revisión habituales, **antes** de desplegar la API nueva.
La migración añade `area` y `company` opcionales a `intra.staff_users`,
y crea `intra.attendance_daily` para guardar las marcas por usuario y día.
No rellena empresas, áreas ni horas artificialmente ni modifica las credenciales.

`GET /api/v1/attendance` admite opcionalmente `?date=YYYY-MM-DD`
y exige sesión Bearer válida con rol `SA`. Devuelve KPI (usuarios activos,
ingresos, salidas, personas actualmente en almuerzo) y listado de usuarios
con tiempos convertidos a `America/Lima`. Las horas ausentes son `null`.
Actualmente no existe un endpoint para registrar marcaciones: los datos
seguirán vacíos hasta integrar el biométrico o un mecanismo de marcación.


## Diagnóstico inicial ZKTeco MB10-VL (V4)

**Antes de actualizar el stack:** realizar respaldo, ejecutar migrador Flyway V4
y confirmar su salida 0. En `salomon-intra-api`, configurar
`INTRA_ZK_DEVICE_SN` con el número de serie exacto leído en el menú
Información del dispositivo. Si no se configura, el receptor devuelve 403.

Se añadieron rutas compatibles con la fase de diagnóstico de ADMS:
- `GET /iclock/cdata?SN=<serie>&options=all` (negociación inicial).
- `GET /iclock/getrequest?SN=<serie>` (consulta periódica).
- `POST /iclock/cdata` devuelve **503** deliberadamente hasta que
  exista almacenamiento transaccional de marcaciones. No confirma datos
  descartados y permite que el dispositivo intente reenviarlos.
- `GET /api/v1/attendance/device-status` requiere Bearer de usuario SA
  y devuelve fecha y última ruta observada del serial configurado.

`public-status` permite exclusivamente `/api/v1/status` y `/iclock/`
desde su proxy del puerto 13021. Nginx externo debe enviar las rutas
`/iclock/` del host `api.intranet.salomon.example` al mismo puerto, conservando
ruta y query string. Verificar TLS, proxy y firmware desde el reloj.

**Límite de seguridad:** la coincidencia del número de serie no autentica
criptográficamente un equipo. El estado `recentContact` solo significa que
la API ha recibido una solicitud con la serie configurada en los últimos
3 minutos. Es una señal de diagnóstico, no garantía de identidad, operación
del biométrico ni recepción de marcaciones. Antes de producción se requiere
mecanismo de autenticación a nivel proxy/red, y recepción idempotente y
validación de eventos con mapeo explícito de PIN a usuario.

No se almacenan fotos, rostros ni plantillas biométricas en este diagnóstico.


## Registro de biométricos desde la intranet (V5)

Antes de actualizar, realizar respaldo PostgreSQL. La migración Flyway
`V5__biometric_device_registry.sql` crea el inventario
`intra.biometric_devices`, asigna privilegios al usuario de aplicación
y mantiene los contactos ADMS de V4.

El usuario con rol `SA` puede abrir
`/panel/herramientas/biometricos` desde el desplegable superior para
registrar nombre, serie, modelo y ubicación, listar equipos, ver último
contacto, habilitar y deshabilitar. El frontend usa un proxy propio
`/api/biometrics`, autenticado mediante cookie HttpOnly, y el backend
`/api/v1/biometrics` verifica de nuevo el rol `SA`.

El receptor ADMS consulta los números de serie **activos** en la tabla,
por lo que **ya no utiliza `INTRA_ZK_DEVICE_SN`**. Si la variable
existía en Portainer, puede retirarse una vez desplegada V5. Registrar
el número de serie antes de probar `/iclock/getrequest`.

`/api/v1/attendance/device-status` sigue mostrando solo el último
dispositivo activo registrado, como vista rápida. Para todos los
equipos, consultar `/api/v1/biometrics` o la pantalla de gestión.

**Seguridad:** el SN es un identificador declarativo, no una prueba
criptográfica de identidad. Restringir el acceso a `/iclock/` en
Nginx con reglas de red adecuadas. Esta fase **solo monitorea conexiones**:
`POST /iclock/cdata` responde 503 y todavía no registra marcaciones.


## Importar empleados existentes desde un ZKTeco (V7)

La migración `V7__biometric_user_import.sql` crea la cola
`intra.biometric_user_sync_commands`. Se requiere V5/V6 previamente
aplicadas. La API expone exclusivamente para SA:

- `POST /api/v1/attendance/user-import/{deviceId}`: crea una solicitud
  de consulta de usuarios; no escribe en el reloj
- `GET /api/v1/attendance/user-import/{deviceId}`: estado de la última
  consulta, código de confirmación y cantidad total de usuarios vinculados

El frontend `/panel/herramientas/usuarios` ofrece «Importar desde
biométrico» y actualiza el listado mientras espera la respuesta.

Cuando el dispositivo llame `GET /iclock/getrequest?SN=...`, el
servidor responderá con la orden `C:<id>:DATA QUERY USERINFO`.
El reloj debería enviar su respuesta como `POST /iclock/cdata` con
`table=USERINFO`, `USER` u `OPERLOG`, según el firmware.
Solo se procesan mensajes de usuarios con PIN y nombre; se rechazan
las cargas mixtas o desconocidas con 503 para no confirmar descartes
de datos. No se descargan ni almacenan huellas, rostros, fotos o
plantillas biométricas. Un `POST /iclock/devicecmd` puede confirmar
la ejecución de la orden, pero esa confirmación **no significa que
se hayan recibido todos los usuarios**.

El emparejamiento se realiza por (equipo, PIN) mediante
`intra.attendance_employee_device_ids`. Cuando es un PIN desconocido,
el sistema crea un trabajador con código `ZK-{deviceId}-{PIN}`,
que podrá editarse desde Gestión de usuarios. Reenvíos de los mismos
usuarios no crean duplicados ni sobrescriben datos locales. La
importación todavía no almacena marcaciones: las tramas `ATTLOG`
seguirán respondiendo 503.

**Pruebas y seguridad:** la compatibilidad exacta del MB10-VL
necesita comprobarse en producción después del despliegue. El SN
declarado por el reloj no autentica al emisor; antes de admitir
importaciones en producción se debe restringir `/iclock/` por IP/VPN
u otros controles de confianza en Nginx. No enviar consultas desde
clientes externos que simulen el SN durante la prueba de sincronización.

Consultas de diagnóstico:

```sql
SELECT id, device_id, status, result_code, created_at, sent_at
FROM intra.biometric_user_sync_commands ORDER BY id DESC LIMIT 5;

SELECT e.full_name, e.employee_code, d.serial_number, m.device_user_id
FROM intra.attendance_employees e
JOIN intra.attendance_employee_device_ids m ON m.employee_id = e.id
JOIN intra.biometric_devices d ON d.id = m.device_id
ORDER BY e.id DESC;
```


## Recepción ATTLOG (V8)

La migración `V8__biometric_attendance_events.sql` crea
`intra.biometric_attendance_events`. Los reintentos de la misma
marcación se ignoran por clave única de dispositivo, PIN, fecha/hora
local, estado, tipo de verificación y código de trabajo.

`POST /iclock/cdata?SN=...&table=ATTLOG` recibe registros ADMS
separados por tabulación (`PIN<TAB>yyyy-MM-dd HH:mm:ss<TAB>estado
<TAB>verificación<TAB>código...`). Valida **todo** el lote, inserta
los registros en una transacción y devuelve `OK: N` solo después de
procesar las N filas. Ante tramas inválidas o errores de base de
datos, no confirma la recepción. No conserva rostros, plantillas
biométricas ni los campos reservados. La hora local del reloj se
interpreta como `America/Lima`; comprobar fecha/hora del dispositivo
antes de usar los reportes.

La tabla registra incluso PIN no vinculados, sin crear automáticamente
usuarios (un evento no proporciona necesariamente un nombre). La
asociación se hace mediante `intra.attendance_employee_device_ids`.

Para confirmar que se están guardando **marcaciones únicas**:

```bash
docker exec aleph-postgres psql -U salomon_admin -d salomon_intra -c "
SELECT d.serial_number, p.device_user_id, p.device_punched_at,
       p.attendance_status, p.verify_mode, p.received_at
FROM intra.biometric_attendance_events p
JOIN intra.biometric_devices d ON d.id=p.device_id
ORDER BY p.id DESC LIMIT 20;"
```

Para el número de eventos y usuarios vinculados:

```sql
SELECT count(*) FROM intra.biometric_attendance_events;
SELECT p.device_user_id, e.full_name, count(*) AS marcaciones
FROM intra.biometric_attendance_events p
LEFT JOIN intra.attendance_employee_device_ids m
  ON m.device_id = p.device_id AND m.device_user_id = p.device_user_id
LEFT JOIN intra.attendance_employees e ON e.id=m.employee_id
GROUP BY p.device_user_id, e.full_name ORDER BY marcaciones DESC;
```

El endpoint de consulta `GET /api/v1/attendance/punches` exige sesión SA.
El dashboard existente `GET /api/v1/attendance` todavía usa
`intra.staff_users` y `intra.attendance_daily`; **no infiere**
entradas/almuerzos/salidas con la tabla nueva sin reglas de negocio
adicionales. Mantener el reloj con registros originales hasta confirmar
que existen en PostgreSQL y verificar autenticidad de origen ADMS:
identificar un dispositivo solo por SN no es autenticación segura.


## Modos de autenticación del personal (V9)

La migración `V9__attendance_employee_auth_methods.sql` almacena únicamente
las modalidades de autenticación **confirmadas manualmente** para cada
empleado: `FACE`, `FINGERPRINT`, `PASSWORD`, `CARD`. No almacena
contraseñas, números de tarjeta, fotos ni plantillas biométricas.

`GET /api/v1/attendance/employees` entrega dos campos diferentes:

- `registeredMethods`: lista separada por comas de métodos confirmados en
  el módulo Gestión de usuarios (editable por SA)
- `observedModes`: modos vistos en los registros ATTLOG vinculados al
  dispositivo y PIN. La interpretación de códigos depende del firmware:
  1=huella, 15=rostro, 0=contraseña, 4=tarjeta, 20=rostro+huella; códigos no
  reconocidos se muestran como `CODE:N`, sin hacer inferencias adicionales.

Los POST/PUT del CRUD aceptan `authenticationMethods` como arreglo de
modalidades válidas, y preservan la información existente cuando se omite.
La UI muestra ambas categorías por separado, ya que una marcación facial
no demuestra que no existan también huellas o contraseñas enroladas.

La lectura directa del estado de enrolamiento del MB10-VL por ADMS todavía
no está implementada. La operación `DATA QUERY USERINFO` no garantiza
que el equipo comunique todos los tipos de credenciales enroladas.


## Alta remota de personal en un reloj (V10)

`attendance_employees` es el directorio **central**: no requiere un
reloj ni un PIN biométrico para crear un trabajador. La opción
«Enviar a biométrico» de Gestión de usuarios registra una orden
individual en `biometric_user_provision_commands`.

Sólo el superadministrador puede crearla o consultar su estado:
`POST /api/v1/attendance/provision/{employeeId}`, con JSON
`{"deviceId":1,"pin":"1001"}`, y
`GET /api/v1/attendance/provision/{employeeId}?deviceId=1`.

Cuando el dispositivo consulte `/iclock/getrequest`, se enviará:
`C:{1000000000+id}:DATA UPDATE USERINFO PIN=1001<TAB>Name=Nombre<TAB>Pri=0`.
El identificador ADMS de esta cola está desplazado para distinguirla
de las antiguas consultas de importación. El resultado recibido
en `/iclock/devicecmd` activa el enlace
`attendance_employee_device_ids` sólo si `Return=0`, y la orden
pasa a `confirmed`. Un código no cero la marca `failed`.
Se previenen PIN ya asignados a otro empleado en la base central.
La comprobación no puede descartar PIN preexistentes que todavía
no hayan sido importados desde el dispositivo.

No se envían contraseñas, huellas ni rostros. El enrolamiento se hace
físicamente. Se conserva historial de marcaciones y no se eliminan
usuarios del dispositivo. **Importante:** `sent` indica que el servidor
emitió una respuesta al polling, no que el dispositivo la ejecutara.
`confirmed` significa que el firmware devolvió código cero;
verificar en el terminal que el usuario se creó antes de enrolarlo.

Este mecanismo depende de que el MB10-VL efectúe
`GET /iclock/getrequest`; si el firmware no llama esa ruta, las
órdenes quedarán pendientes. Los comandos caducan tras 24 horas.
Asegurar el canal /iclock/ mediante controles en el proxy: el SN
por sí solo no autentica al dispositivo.

Diagnóstico SQL:

```sql
SELECT id, employee_id, device_id, device_user_id, status, result_code,
       created_at, sent_at, completed_at
FROM intra.biometric_user_provision_commands
ORDER BY id DESC LIMIT 10;
```


## DNI como ID/PIN de acceso del biométrico (V11)

La migración `V11__attendance_employee_dni.sql` agrega
`dni varchar(8)` a `attendance_employees`, con restricción de
**ocho dígitos** e índice UNIQUE para evitar personas con el mismo
DNI en el directorio. Se guarda como cadena, conservando ceros
iniciales. Los usuarios importados anteriormente se conservan con
DNI NULL hasta que un administrador verifique el documento: el
ID/PIN antiguo no se considera prueba de identidad.

La UI de Gestión de usuarios exige DNI al crear trabajadores
nuevos y lo permite corregir en empleados existentes. El modal
«Enviar usuario a biométrico» muestra el DNI de solo lectura;
el backend de provisión obtiene el DNI **directamente de la base**
y lo usa como `PIN` ADMS. El cliente ya no puede elegir arbitrariamente
el PIN. Se siguen aplicando controles de PIN utilizado por otro
empleado y de asignación existente con otro PIN.

El documento de identidad no es una contraseña ni una credencial
secreta. Se utiliza como identificador para enlazar usuarios, mientras
que la autenticación efectiva sigue siendo por rostro, huella u
otro método enrolado localmente. Mostrar DNI completo sólo a
administradores autorizados y evitar imprimirlo en logs.


## OPERLOG repetido con HTTP 503 (V12)

Ante `POST /iclock/cdata?table=OPERLOG`, el receptor ya no intenta
interpretar el mensaje como una respuesta a `DATA QUERY USERINFO`.
La migración `V12__biometric_operlog_inbox.sql` crea
`intra.biometric_operlog_inbox` con retención íntegra, idempotente
por (dispositivo, SHA-256 del mensaje). Se responde `OK` **solo tras
persistir exitosamente**; los mensajes vacíos o mayores de 64 KiB
siguen sin confirmarse. El runtime tiene permiso de INSERT, no de
lectura, sobre el contenido binario; no se expone endpoint web ni
se escriben los mensajes en los logs.

Estos bytes son material no clasificado que podría incluir datos
sensibles de operación del dispositivo. Restringir el acceso al dueño
de la base y definir una política de retención y purga tras analizar
el formato, antes de habilitar exportaciones de cualquier tipo.
Una vez procesada la cola, comprobar si el dispositivo empieza a
consultar `/iclock/getrequest`; la corrección **no garantiza** que
el firmware haga polling de órdenes.

Comprobar bandeja sin mostrar el contenido:

```sql
SELECT device_id, count(*) AS paquetes_unicos, max(first_received_at) AS ultimo
FROM intra.biometric_operlog_inbox GROUP BY device_id;
```

Comprobar estado de orden de alta:

```sql
SELECT id, device_user_id, status, sent_at, result_code
FROM intra.biometric_user_provision_commands ORDER BY id DESC LIMIT 5;
```
