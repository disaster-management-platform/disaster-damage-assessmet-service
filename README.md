# Damage Assessment

Servicio Java 21, Jakarta EE 11 Full Platform y Maven, validado en WildFly 42.0.0.Final. Un único WAR contiene API JAX-RS/JSON-B, BLL mediante Stateless EJB, CDI, Jakarta Validation y persistencia JPA/JTA. No contiene vistas ni dependencias de presentación.

## Ejecución

```sh
mvn package
```

Instale el driver PostgreSQL JDBC 42.7.7 en WildFly como despliegue `postgresql-42.7.7.jar`. Ejecute las migraciones SQL explícitamente con credenciales administrativas, fuera del arranque de WildFly. Para instalaciones nuevas aplique V001 y después V002; si V001 ya está aplicada, ejecute únicamente `migrations/V002__assessments_schema.sql`. V002 renombra el esquema anterior sin copiar ni recrear tablas y puede repetirse sin modificar datos. Detenga las instancias de Daños antes de cambiar el esquema y despliegue después este WAR. El arranque valida el esquema mediante Hibernate y nunca crea ni modifica tablas. Después de V002 aplique `migrations/V003__evaluation_history.sql` y `migrations/V004__evidence.sql`; actualice los permisos con `server/permissions.sql`, usando la identidad administrativa. V003 conserva la evaluación vigente y añade su historial; no modifica V001 ni V002. El historial de migraciones se conserva en `assessments.schema_history`.

El DataSource JNDI `java:jboss/datasources/DamageAssessment` es administrado por el contenedor. `server/datasource.cli` configura JTA, pool máximo de 8 conexiones y timeouts acotados, usando variables externas:

- `DAMAGE_DB_HOST`, `DAMAGE_DB_PORT` (5432), `DAMAGE_DB_NAME` (postgres).
- `DAMAGE_DB_USER`, `DAMAGE_DB_PASSWORD`: rol exclusivo de este servicio; nunca la cuenta administrativa.
- `DAMAGE_DB_CA`: ruta de CA local, fuera del repositorio. JDBC utiliza obligatoriamente `sslmode=verify-full`; host, puerto y nombre forman la URL externa sin permitir desactivar TLS.
- `DAMAGE_DB_POOL_MIN` (0), `DAMAGE_DB_POOL_MAX` (8), `DAMAGE_DB_POOL_WAIT_MS` (5000). Cada réplica tiene su propio pool: dimensione la suma dentro del presupuesto PostgreSQL del dominio.
- `DAMAGE_DB_CONNECT_TIMEOUT` (5), `DAMAGE_DB_SOCKET_TIMEOUT` (30), `DAMAGE_DB_QUERY_TIMEOUT` (10), en segundos. WildFly valida las conexiones al obtenerlas y descarta conexiones rotas mediante los componentes PostgreSQL de IronJacamar.
- `DISASTERS_BEARER_TOKEN`: token de servicio externo opcional, de duración acotada y obtenido del proveedor de identidad; nunca se incluye en el WAR.
- `DISASTERS_BASE_URL`: URL del servicio propietario de Desastres. Timeout de conexión 3 segundos y de solicitud 5 segundos; no hay transacciones JTA abiertas durante esa llamada.

En el proyecto Supabase existente se utiliza Session Pooler en el puerto 5432: permite conectividad IPv4 y mantiene la sesión y los prepared statements necesarios para JDBC/JPA. No se utiliza transaction pooling ni un pool adicional en la aplicación; WildFly administra las conexiones persistentes. Se conserva la posibilidad de conexión directa si la red dispone de IPv6.

Antes de configurar el DataSource, cree de forma segura el login exclusivo `damage_assessment_api`, sin privilegios administrativos ni membresías de otros dominios. Con una conexión administrativa ejecute `server/permissions.sql`: asigna la propiedad de `assessments` al rol NOLOGIN `damage_assessment_migrator`, permite al login solo SELECT/INSERT y UPDATE sobre columnas autorizadas, y deja sin acceso a PUBLIC, anon y authenticated. El login no puede borrar tablas, modificar recibos originales ni consultar el historial de migraciones. Las tablas/secuencias futuras permanecen privadas; sus permisos de ejecución deben concederse explícitamente cuando una migración los requiera. Las futuras migraciones deben ejecutarse con `SET ROLE damage_assessment_migrator`; el rol de ejecución nunca recibe esa membresía. El esquema no debe añadirse a los esquemas expuestos por Data API.

Conserve fuera de Git un backup consistente previo al cambio y los ACL originales. V002 es transaccional: un error revierte el cambio de nombre. Para recuperación posterior, detenga Daños y coordine un rollback del despliegue y del nombre del esquema; no restaure ni sobrescriba otros dominios.

Con WildFly Full ya iniciado y las variables externas cargadas en el proceso del servidor:

```sh
mvn dependency:get -Dartifact=org.postgresql:postgresql:42.7.7
"$WILDFLY_HOME/bin/jboss-cli.sh" --connect --command="deploy $HOME/.m2/repository/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar"
"$WILDFLY_HOME/bin/jboss-cli.sh" --connect --file=server/datasource.cli
"$WILDFLY_HOME/bin/jboss-cli.sh" --connect --command="deploy target/damage-assessment.war"
```

 El contexto HTTP es `/`; no instale este WAR en el mismo contexto raíz de otra aplicación. No se incluyen credenciales, certificados ni binarios. El servidor de aplicaciones se obtiene y configura fuera del repositorio.

Configure externamente `mp.jwt.verify.issuer`, `mp.jwt.verify.publickey.location` (JWKS HTTPS o clave pública confiable) y `mp.jwt.verify.audiences`, mediante MicroProfile Config, variables de entorno o propiedades JVM. No se proporcionan claves predeterminadas ni mecanismos de autenticación propios. SmallRye JWT/WildFly verifica firma y token; `smallrye.jwt.path.groups=role` utiliza el claim existente `role`. El JWT debe incluir `iat` y `exp` conforme a MP-JWT, además de issuer y audiencia configurados. Se requieren `sub` y `organization_id`; el acceso a un desastre requiere `disaster_id`, escalar o array. No se confía en encabezados de identidad ni en actor/organización enviados en el cuerpo.

Roles: `damage.read`, `damage.write`, `damage.admin`. `reconstruction.read` y `reconstruction.write` pueden consultar snapshots autorizados. Creación, edición y asociación requieren escritura de Daños; los catálogos requieren `damage.admin`. Los preliminares solo son visibles a su organización. La lectura de un desastre autorizado ofrece la vista completa entre organismos; la modificación exige además pertenecer a la organización evaluadora. Los diagnósticos anónimos solo revelan UP/DOWN.

## Contratos

OpenAPI 3.0.3 con esquemas y ejemplos se conserva en `src/main/resources/META-INF/openapi.json` y se consulta autenticado en `GET /api/evaluaciones/openapi`.

| Método | Ruta | Semántica |
|---|---|---|
| GET | `/api/evaluaciones?disasterId={id}` | `{schemaVersion:1, disasterId, evaluations:[...]}`, snapshot completo, sin paginación. |
| GET | `/api/evaluaciones/{id}` | Detalle autorizado con version y fechas. |
| POST | `/api/evaluaciones` | Idempotency-Key UUID obligatorio; 201 nuevo Draft, 200 repetición original, 409 contenido diferente. |
| PUT | `/api/evaluaciones/{id}` | Edita Draft con expectedVersion; no cambia assetId ni asociación. |
| PUT | `/api/evaluaciones/{id}/desastre` | `{disasterId, expectedVersion}`; asocia un preliminar tras comprobar el servicio propietario. |
| PUT | `/api/evaluaciones/{id}/estado` | Validación/rechazo autorizado con expectedVersion y razón. |
| POST | `/api/evaluaciones/{id}/revisiones` | Nueva revisión Draft de una evaluación Validated/Rejected. |
| GET | `/api/evaluaciones/{id}/historial` | Snapshots inmutables por versión. |
| GET | `/api/evaluaciones/mapa?disasterId={id}` | Mismo envelope completo, con latitude, longitude y geometry. |
| GET | `/api/evaluaciones/catalogos` | Tipos y categorías; incluye inactivos para referencias históricas. |
| POST | `/api/evaluaciones/catalogos/tipos-activo` | `{id, name}`. |
| POST | `/api/evaluaciones/catalogos/categorias-dano` | `{id, name, damageLevel}`. |
| PUT | `/api/evaluaciones/catalogos/tipos-activo/{id}/desactivar` | Desactiva sin borrar referencias. |
| PUT | `/api/evaluaciones/catalogos/categorias-dano/{id}/desactivar` | Desactiva sin borrar referencias. |
| GET | `/api/evaluaciones/diagnostico/live` | Liveness. |
| GET | `/api/evaluaciones/diagnostico/ready` | Conexión JPA y tabla disponibles; 503 en fallo. |

Ejemplo de creación (evaluator y organization los asigna el servidor):

```json
{"disasterId":null,"assetId":"bridge-001","assetTypeId":"Bridge","damageCategoryId":"Severe","evaluatedAt":"2026-11-18T00:00:00Z","location":"Zona Norte","latitude":4.7,"longitude":-74.0,"geometry":null,"description":"Daño estructural","evidence":[],"budget":150000.00}
```

La categoría determina el nivel canónico None, Minor, Moderate, Severe o Destroyed. El tipo determina assetType. Se permiten nuevas entradas sin recompilar. Referencias, nombre del catálogo y mapeo de nivel son estables; la operación administrativa posterior es su desactivación. Una categoría puede incluir `assetTypeId` al crearse para limitar su clasificación a ese tipo; null mantiene el alcance global de los catálogos existentes.

El presupuesto es COP, no negativo y con dos decimales. Coordenadas WGS84 vienen en pares o ambas null. GeoJSON admite Point, Polygon y MultiPolygon en longitud-latitud, hasta 2000 posiciones y 64 KiB; JTS se utiliza únicamente para validación geométrica, no como motor GIS. El cuerpo tiene un máximo de 256 KiB. Validación estructural: 400; referencias inactivas y geometría inválida: 422. 401/403 distinguen autenticación y permisos; 404 oculta preliminares ajenos; 409 identifica conflictos de identidad, activo o versión; 503 representa dependencia no disponible. Los errores de persistencia no se convierten en arrays vacíos.

## Decisiones ADD y compatibilidad

El enunciado §4.2 es la fuente principal. DAM-01/02 usan el snapshot y mapa; DAM-03/04 disponen de detalle, creación y edición de borradores. Evidencias multipart y la consulta DAM-05 de sincronización siguen fuera del alcance. La cola offline permanece en Android. Se conservan únicamente referencias de evidencia, sin subir ni servir archivos.

`revision` comienza en 1 y representa la revisión funcional. PUT edita únicamente Draft sin incrementar esa revisión; @Version cambia con cada modificación. `PUT /api/evaluaciones/{id}/estado` admite Draft → Validated o Draft → Rejected con `{status, expectedVersion, reason}`, razón obligatoria y rol `damage.admin`. Para validar se requiere un desastre asociado. `POST /api/evaluaciones/{id}/revisiones` recibe EvaluationInput con expectedVersion, permite pasar de Validated/Rejected a un nuevo Draft e incrementa revision, conservando evaluationId, assetId y disasterId. No se modifica directamente contenido aprobado o rechazado.

`GET /api/evaluaciones/{id}/historial` devuelve snapshots inmutables por versión, actor autenticado, organización, fecha, acción y razón. La evaluación y el historial se persisten en la misma transacción. Las filas anteriores a V003 reciben un snapshot Baseline capturado durante la migración, con actor null: no se inventa quién realizó sus cambios pasados. El recibo original de creación no cambia tras ediciones, estados ni revisiones.

El snapshot schemaVersion 1 devuelve únicamente el estado vigente de cada evaluación. No incluye revisiones antiguas como duplicados. Reconstrucción conserva su política: solo la revisión actual Validated con daño distinto de None es elegible; un nuevo Draft no hace reaparecer una aprobación histórica. El presupuesto sigue siendo decimal COP explícito: desconocido no se transforma en cero y debe completarse para registrar la evaluación bajo el contrato actual. Cero representa un importe conocido de cero.

Las lecturas de Daños requieren organización y acceso por desastre. Los roles de servicio reconstruction.read/write pueden consultar snapshots completos de los desastres autorizados entre organizaciones; no reciben permisos de escritura sobre Daños. Modificaciones y transiciones siguen limitadas a la organización propietaria.

assetId identifica el activo; no se cambia por edición. El contrato de Reconstrucción exige una evaluación actual por activo y desastre: un índice único aplica esa restricción al asociar. Las revisiones se realizan sobre ese mismo registro y su historial, sin relajar la unicidad. No se fusionan registros por proximidad ni se crean claves foráneas hacia disasters. La creación y su recibo original son atómicos; el reintento devuelve el recibo original aunque el borrador haya sido editado, mientras GET devuelve su estado actual. La propiedad de la operación incluye la identidad evaluadora y su organización.

El contenedor administra concurrencia, componentes stateless, conexiones y transacciones. JPA materializa resultados antes de retornarlos y @Version evita actualizaciones perdidas. La separación de esquemas y credenciales reduce el alcance de acceso; validar disasterId mediante REST preserva ownership, a cambio de depender de la disponibilidad de Desastres para nuevas asociaciones. JTA local garantiza atomicidad en Daños, sin transacción distribuida con Desastres. REST/JSON permite interoperar con .NET sin compartir sus bibliotecas. El WAR requiere un Application Server compatible y configuración operativa; no se afirma mejor desempeño ni disponibilidad sin mediciones.

## Validación local

Con PostgreSQL aislado, este esquema migrado, el WAR desplegado y el servicio real de Desastres disponible:

```sh
DAMAGE_TEST_URL=http://127.0.0.1:8170 \
DISASTERS_TEST_URL=http://127.0.0.1:8198 \
DAMAGE_TEST_KEY=/ruta/privada/de/pruebas.pem \
python3 scripts/integration_test.py
```

El servidor de prueba debe confiar exclusivamente en la clave pública de ese par, issuer `https://identity.test` y audiencia `platform`. La clave privada se genera fuera del repositorio y se utiliza solo para firmar identidades del escenario local. Este procedimiento no habilita un bypass de autenticación en producción. El script rechaza destinos remotos y crea datos de prueba; nunca debe apuntarse al proyecto Supabase. También admite una URL local de YARP para comprobar el enrutamiento.

La regresión de persistencia entre dos instancias locales se ejecuta con:

```sh
DAMAGE_TEST_URLS=http://127.0.0.1:8170,http://127.0.0.1:8270 \
DAMAGE_TEST_KEY=/ruta/privada/de/pruebas.pem \
DAMAGE_TEST_POSTGRES_CONNECTION="host=127.0.0.1 port=5549 dbname=postgres user=$USER" \
python3 scripts/persistence_test.py
```

Ambas instancias deben compartir el PostgreSQL aislado y la configuración JWT de pruebas. La prueba comprueba creación concurrente sin duplicados, concurrencia optimista, recibo original, precisión monetaria y rollback de una edición rechazada. El bloque SQL opcional requiere psycopg2 y una conexión administrativa al PostgreSQL local aislado; induce un fallo al insertar historial y satura el pool para comprobar rollback, HTTP 503 y recuperación. No apunta a Supabase.

En YARP configure externamente `ReverseProxy__Clusters__damage-assessment-api__Destinations__local__Address` con la URL del Application Server. Las rutas son explícitas, sin fallback a operaciones futuras. La ausencia de destinos de Daños no impide atender otros servicios.

Para habilitar el consumidor existente de Reconstrucción, configure externamente `ExternalApis__DamageAssessments__BaseUrl` y su `ExternalApis__DamageAssessments__BearerToken` de servicio, con issuer/audiencia y claims autorizados, y desactive `Reconstruction__DemoExternalData` en producción. En Development, una BaseUrl explícita de DamageAssessments selecciona el lector real incluso con DemoExternalData; la financiación demo permanece identificada como simulada y no se utiliza fuera de Development. No se cambia su lector HTTP ni sus reglas financieras. El contrato se verificó utilizando directamente `DamageAssessmentHttpReader` contra este servicio a través de YARP.

## Evidencias privadas y Valet Key

Aplique V004 explícitamente después de V003 y actualice `server/permissions.sql` antes del despliegue. Añade metadatos en `assessments.evidence`; no modifica recibos ni snapshots existentes. El runtime puede insertar y actualizar únicamente estados, leases y fechas de limpieza. Los archivos permanecen en Object Storage, nunca como BLOB ni como almacenamiento local de la aplicación.

Configure mediante la API administrativa de Supabase un bucket privado `assessments-evidence`, `file_size_limit=6291456` y `allowed_mime_types=["image/jpeg","image/png","application/pdf"]`. Cree una identidad técnica Auth exclusiva, confirmada administrativamente, con contraseña aleatoria y sin roles administrativos. Aplique una vez `server/storage.sql` con `psql -v storage_identity="$STORAGE_IDENTITY"`: permite SELECT/INSERT solo en ese bucket, DELETE solo bajo `pending/` y ningún UPDATE. No añada políticas públicas. Las políticas son específicas de esta identidad y no conceden acceso a otros dominios. No utilice la clave administrativa como credencial permanente del adaptador.

Externalice `EVIDENCE_STORAGE_BUCKET`, `EVIDENCE_STORAGE_PUBLISHABLE_KEY`, `EVIDENCE_STORAGE_EMAIL` y `EVIDENCE_STORAGE_PASSWORD` en el proceso WildFly, fuera de Git. El adaptador obtiene un access token temporal del Auth existente y lo renueva mediante autenticación del servidor; estos tokens nunca se entregan a clientes. No cambia la identidad JWT de negocio. El proyecto está fijado a `yhbwpetxhydxssernbrb`.

`EVIDENCE_MAX_BYTES` limita el archivo (por defecto 6 MiB, puede reducirse), `EVIDENCE_MAX_PER_EVALUATION` limita las evidencias pendientes/verificadas (20, máximo 50), `EVIDENCE_MAX_PENDING_ORGANIZATION` limita autorizaciones activas (100), y `EVIDENCE_VERIFY_CONCURRENCY` limita operaciones de Storage por réplica (2, máximo 4). Configure también el límite del bucket al reducir el tamaño. Multipart permite 64 KiB adicionales para su envelope; YARP conserva un límite explícito de 6356992 bytes en estas rutas. Las consultas y modificaciones que no usan archivos no llaman a Storage.

- `POST /api/evaluaciones/{id}/evidencias`, JSON e Idempotency-Key UUID: `{originalFileName,contentType,size,sha256}`. Devuelve metadatos PendingUpload y autorización para un único objeto temporal. Transfiera con PUT al `uploadUrl`, `Content-Type` indicado y `x-upsert:false`. No se envía Authorization del servicio ni una clave Supabase al cliente. Se permite renovar la URL conservando la identidad mientras la intención esté vigente.
- La vigencia de subida se devuelve como `uploadExpiresAt`, tomada del token real del proveedor (habitualmente dos horas). `expiresAt` es el horizonte conservador de reconciliación/limpieza, tres horas desde la última autorización emitida; no promete que la URL siga funcionando hasta ese momento.
- El mismo POST acepta multipart `file`, `size` y `sha256`, con Idempotency-Key, para compatibilidad DAM-03. Usa la misma intención, Storage y confirmación; no crea un almacén alternativo.
- `POST /{id}/evidencias/{evidenceId}/confirmar`: verifica el contenido real por streaming, copia a `verified/`, verifica nuevamente la copia y solo entonces confirma. Devuelve Verified y `attachment:UUID`, o 422 con Rejected/Expired cuando el resultado es definitivo. Un objeto aún inexistente conserva PendingUpload. 409 durante otra confirmación admite reintento; el lease recuperable dura cinco minutos. 503 conserva la intención.
- `GET /{id}/evidencias` y `GET /{id}/evidencias/{evidenceId}`: metadatos autorizados, sin URLs persistidas.
- `GET /{id}/evidencias/{evidenceId}/descarga`: URL GET firmada de 60 segundos, después de verificar permisos y estado Verified.

La confirmación no modifica automáticamente la evaluación. Asocie `attachment:UUID` mediante el PUT versionado existente mientras esté Draft. Se verifica propiedad, estado y revisión. Una nueva revisión puede reutilizar explícitamente archivos verificados del mismo registro; nunca sobrescribe su contenido. Referencias de texto históricas se conservan al mantenerlas en el documento, pero no se aceptan nuevas referencias arbitrarias ni se validan como archivos reales. Para aprobar una evaluación con evidencias, todas sus referencias deben estar verificadas. No existe obligatoriedad universal de fotos.

Las verificaciones tienen timeouts de red y un cierre del stream a los 30 segundos, con buffers de 8 KiB. Se comprueban extensión, MIME, magic bytes, tamaño real y SHA-256; esto no equivale a antivirus ni a una validación completa de todos los formatos. Las copias definitivas son inmutables por ausencia de UPDATE/DELETE y nunca se limpian. Una copia confirmada por Storage con respuesta perdida se reconcilia leyendo su hash antes de confirmar metadatos. No existe una transacción distribuida: cada transición local usa JTA fuera de las llamadas HTTP.

La limpieza es oportunista, acotada a cinco intenciones por nueva solicitud: elimina únicamente objetos `pending/` después del horizonte conservador, sin leases activos, y registra su limpieza. También retira las copias temporales de archivos ya verificados; conserva los definitivos y todos los historiales. Sin tráfico, la limpieza se retrasa; no se incorpora un scheduler. Los estados no confirmados siguen siendo visibles. Una intención Expired/Rejected no se transforma silenciosamente en otra operación.

Valet Key reduce la transferencia directa por WildFly; la compatibilidad multipart y la verificación siguen consumiendo recursos del servidor. Separar contenido y metadatos permite permisos mínimos e independencia del proveedor en el adaptador, a cambio de consistencia eventual, dependencia de Storage, objetos huérfanos temporales, expiración y costo de leer dos veces el contenido. No se atribuyen mejoras cuantitativas sin medición.

Prueba de esta iteración, con dos WildFly locales, PostgreSQL aislado y el bucket real dedicado (solo archivos sintéticos):

```sh
DAMAGE_TEST_URLS=http://127.0.0.1:8170,http://127.0.0.1:8270 \
DAMAGE_TEST_KEY=/ruta/privada/pruebas.pem \
DAMAGE_TEST_POSTGRES_CONNECTION="host=127.0.0.1 port=5549 dbname=postgres user=$USER" \
python3 scripts/evidence_test.py
```

La conexión administrativa opcional se admite únicamente en loopback y simula pérdida de confirmación tras la copia. Retire mediante administración solo las rutas sintéticas de esa ejecución, nunca evidencias referenciadas por datos reales. No apunte las pruebas de metadatos a Supabase. Gateway: `python3 scripts/test_gateway.py EvidenceRoutingTests`, con el servicio local disponible y el Gateway previamente compilado.
