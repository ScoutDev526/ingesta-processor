# Guión de presentación — ingesta-processor

---

## 1. INTRODUCCIÓN

> "Este sistema es la evolución en Java de los scripts Python que teníamos antes. Hace exactamente lo mismo que hacía ESClassificationSystem, pero ahora está estructurado como una aplicación empresarial mantenible, testeable y extensible. Lo llamo **ingesta-processor**."

---

## 2. QUÉ HACE EL SISTEMA

> "El sistema tiene una responsabilidad muy clara: **tomar ficheros Excel o XML de fuentes externas, transformar su contenido, y persistirlo en base de datos**. A esto lo llamamos un pipeline ETL — Extract, Transform, Load."

> "El pipeline tiene siempre la misma secuencia:"

```
1. Escanea definiciones YAML de jobs
2. Filtra los habilitados (enabled: true)
3. Descarga los ficheros (local o SharePoint)
4. Construye objetos Job/Task/Step en memoria
5. Ejecuta transformaciones (TRIM, LOOKUP, etc.)
6. Ejecuta persistencia (TRUNCATE + INSERT)
7. Genera el report Excel de logs
8. Envía notificación
9. Limpia ficheros temporales
```

---

## 3. ARQUITECTURA HEXAGONAL

> "La arquitectura que usamos se llama **hexagonal** o Ports & Adapters. La idea es que el núcleo de negocio no sabe nada de dónde vienen los datos ni dónde van. Solo habla con interfaces."

**Dibujar en la pizarra:**

```
┌─────────────────────────────────────────────────────┐
│                   NÚCLEO (core)                     │
│                                                     │
│  IngestaService ──► DefaultJobProcessor             │
│       │                   │                         │
│  Ports (interfaces)   Ports (interfaces)            │
└───────┼───────────────────┼─────────────────────────┘
        │                   │
   INBOUND              OUTBOUND
   ┌────┴────┐        ┌──────────────────────────┐
   │REST API │        │ YAML │ SharePoint │ Excel │
   │Scheduler│        │ JDBC │ Notif.     │ Report│
   └─────────┘        └──────────────────────────┘
```

> "Si mañana tenemos que leer de S3 en vez de SharePoint, solo añadimos un nuevo adapter. El núcleo no toca nada."

---

## 4. LOS YAML JOBS — El corazón del sistema

> "Cada proceso de ingesta está definido en un fichero YAML. No hay que tocar código Java para añadir un nuevo import. Solo crear un YAML."

**Ejemplo de estructura:**

```yaml
name: hubwoo-contracts-import
enabled: true
fileType: EXCEL
sheetIndex: 0

source:
  type: SHAREPOINT
  location:
    path: HUBWOO

tasks:
  - name: transform-data
    type: TRANSFORMATION
    subtasks:
      - name: trim-whitespace
        type: TRIM
      - name: resolve-owner-ck
        type: LOOKUP                        # ← resolución email→CK
        parameters:
          sourceColumn: Business_Contract_Owner
          referenceTable: hr
          referenceKeyColumn: mail
          referenceValueColumn: id
          nullValues: ["Unknown", ""]

  - name: persist-data
    type: PERSISTENCE
    subtasks:
      - name: truncate-contracts
        type: TRUNCATE
        parameters:
          tableName: CONTRACTS
      - name: insert-contracts
        type: INSERT
        parameters:
          tableName: CONTRACTS
          autoMap: false
          mappings:
            - excelColumn: "Master_Agreement_ID"
              dbColumn: "ID"
            - excelColumn: "Agreement_Name"
              dbColumn: "NAME"
```

> "Hay dos tipos de tareas: **TRANSFORMATION** (modifica los datos en memoria) y **PERSISTENCE** (los persiste en BD). Dentro de cada tarea hay steps."

**Step types disponibles:**

| Tipo | Qué hace |
|------|----------|
| `TRIM` | Elimina espacios en los valores |
| `UPPERCASE` | Convierte a mayúsculas |
| `CONCATENATE` | Une columnas con separador |
| `DEDUPLICATE` | Elimina filas duplicadas |
| `FILTER_NULL` | Elimina filas con campo vacío |
| `LOOKUP` | Resuelve un valor via consulta a BD (ej. email→CK) |
| `TRUNCATE` | Vacía la tabla destino |
| `INSERT` | Inserta las filas con mapeo de columnas |
| `VALIDATE_REFERENCE` | Valida que un valor existe en tabla de referencia |
| `LINK_PARENT` | Crea relaciones padre-hijo en tablas de relación |

---

## 5. COLUMN AUTO-MAPPER

> "El INSERT tiene un modo `autoMap: true` que hace magia: convierte las cabeceras del Excel a UPPER_SNAKE_CASE, elimina acentos (á→a, ñ→n), divide camelCase, y las compara con las columnas de la tabla BD. Si coinciden, las mapea automáticamente."

> "Para casos donde el nombre Excel no coincide con el de BD, se pone un mapping explícito. Los mappings explícitos siempre tienen prioridad."

---

## 6. SCRIPTS PYTHON MIGRADOS

> "Hemos migrado varios scripts Python. Los que eran imports simples de Excel se convirtieron en YAMLs. Los que tenían lógica de negocio compleja se convirtieron en servicios Java."

| Python script | Migrado a |
|---------------|-----------|
| `ImportDataHubwoo` | `hubwoo-contracts-import.yml` + step `LOOKUP` |
| `UpdateRolesOwnership` | `RoleOwnershipService.java` + endpoint REST |
| `UpdateDepartments` | `DepartmentUpdateService.java` + endpoint REST |
| Imports simples (Risk, Control, etc.) | YAMLs directamente |

> "Para `UpdateRolesOwnership` y `UpdateDepartments`, que necesitaban consultas SQL complejas y lógica de árbol de managers, creamos servicios Java con su propio puerto e adaptador JDBC. Se invocan por REST:"
>
> - `POST /api/ingesta/roles/ownership`
> - `POST /api/ingesta/departments/update`

---

## 7. API REST

| Endpoint | Descripción |
|----------|-------------|
| `POST /api/ingesta/execute?jobs=nombre1,nombre2` | Ejecuta los jobs indicados (o todos si no se especifica ninguno) |
| `GET  /api/ingesta/report/{id}/excel` | Descarga el Excel de logs de esa ejecución |
| `POST /api/ingesta/roles/ownership?date=2026-04-01` | Recalcula el ownership de roles |
| `POST /api/ingesta/departments/update?date=2026-04-01` | Detecta y clasifica nuevos departamentos |
| `GET  /api/ingesta/health` | Health check |

---

## 8. EL EXCEL DE LOGS

> "Al final de cada ejecución, el sistema genera automáticamente un Excel de logs, igual al que generaba ESClassificationSystem."

**Estructura:**

- **Hoja 1** (`ESClassificationSystem`): todos los logs del proceso completo, ordenados por timestamp
- **Hojas siguientes**: una por job, con sus logs específicos
- **Columnas**: `TIMESTAMP | SEVERITY | STEP | MESSAGE`

**Niveles de log y colores de fila:**

| Nivel | Color | Cuándo |
|-------|-------|--------|
| `TRACE` | Gris | Relaciones no encontradas (LINK_PARENT, VALIDATE_REFERENCE) |
| `INFO` | Blanco | Información general del proceso |
| `SUMMARY` | Verde | Totales al final de cada step |
| `WARN` | Amarillo | Advertencias |
| `ERROR` | Rojo | Errores |

**Color de la pestaña de cada hoja** según el estado final del job:

| Estado | Color |
|--------|-------|
| SUCCESS | Verde |
| PARTIAL | Amarillo |
| SKIPPED (sin fichero) | Azul |
| FAILED | Rojo |
| Otro | Negro |

> "El Excel se guarda en `ingesta.working-directory` y también está disponible para descarga via REST usando el ID de la ejecución."

---

## 9. CONFIGURACIÓN

```yaml
# application.yml
ingesta:
  jobs.path: classpath:jobs           # dónde están los YAMLs
  working-directory: /tmp/ingesta     # directorio de trabajo y reports
  report.title: ESClassificationSystem
  scheduler:
    enabled: false
    cron: "0 0 2 * * ?"              # ejecución automática a las 2am
  notification.enabled: true
```

---

## 10. CÓMO AÑADIR UN NUEVO IMPORT

> "Para añadir un nuevo proceso de ingesta, en la mayoría de los casos solo hay que crear un fichero YAML. No hay que tocar código Java."

**Pasos:**

1. Crear `mi-nuevo-import.yml` en `src/main/resources/jobs/` con `enabled: true`
2. Definir la fuente (`source`) y el tipo de fichero
3. Añadir la tarea TRANSFORMATION con los steps necesarios
4. Añadir la tarea PERSISTENCE con TRUNCATE + INSERT
5. Arrancar la aplicación → el job se detecta automáticamente

> "Si el nuevo proceso necesita lógica especial (consultas SQL complejas, árbol de jerarquías...) como `UpdateDepartments`, entonces se crea un servicio Java con su puerto e adaptador, siguiendo el mismo patrón."

---

## 11. CÓMO ARRANCAR Y PROBAR

```bash
# Compilar
mvn clean package

# Arrancar (puerto 8080)
mvn spring-boot:run

# Ejecutar todos los jobs
curl -X POST http://localhost:8080/api/ingesta/execute

# Ejecutar solo un job específico
curl -X POST "http://localhost:8080/api/ingesta/execute?jobs=hubwoo-contracts-import"

# Descargar el Excel de logs (usar el "id" del JSON devuelto por /execute)
curl http://localhost:8080/api/ingesta/report/{id}/excel -o report.xlsx
```

---

## PREGUNTAS FRECUENTES

**¿Se puede ejecutar en paralelo?**
> Sí, con `ingesta.parallel-jobs=N` en `application.yml`.

**¿Y si falla un job?**
> Los otros siguen ejecutándose. El ProcessReport final indica SUCCESS, PARTIAL o FAILED con el detalle de cada job.

**¿Cómo se añade soporte para un nuevo tipo de fuente (S3, FTP...)?**
> Se implementa la interfaz `FileDownloaderPort` en un nuevo adapter marcado con `@Component`. El resto del sistema lo detecta automáticamente por Spring.

**¿Dónde están los logs de ejecución?**
> En el Excel generado en `ingesta.working-directory`, y en los logs de aplicación (SLF4J/Logback).

**¿Qué pasa si el Excel de origen no tiene exactamente las columnas esperadas?**
> Con `autoMap: true` el sistema hace lo mejor que puede e informa de las columnas no mapeadas. Con `autoMap: false` solo se mapean las columnas definidas explícitamente en el YAML.
