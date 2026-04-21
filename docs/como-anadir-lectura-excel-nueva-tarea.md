# Leer Excel para una tarea nueva (con transformación nueva)

## Contexto

El proyecto **ingesta-processor** es un ETL en Spring Boot 3.3.5 (arquitectura hexagonal) que lee ficheros (Excel/XML) y los persiste en BD aplicando una pipeline de pasos declarada en YAML. Actualmente hay un job por cada fichero de negocio (Control, Risk, Action, CMDB…) y todos comparten el **mismo lector Excel** (`ExcelFileReaderAdapter`).

Este documento describe cómo leer un Excel nuevo y aplicar una **transformación que todavía no existe como `StepType`**. La lectura del Excel en sí NO requiere tocar Java — ya está resuelta y es reutilizable a través del puerto `FileReaderPort`. Lo único que falta es **añadir el nuevo tipo de paso**.

## Arquitectura relevante (lo que hay que saber antes de tocar nada)

- **Lector Excel reutilizable**: [ExcelFileReaderAdapter.java:27](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/reader/ExcelFileReaderAdapter.java) — `@Component` Spring, se selecciona automáticamente cuando el YAML declara `fileType: EXCEL`. No hay que duplicarlo.
- **Puerto**: [FileReaderPort.java:14](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/FileReaderPort.java) — devuelve `Stream<Map<String,Object>>` (cabeceras del Excel → clave; celdas → valor).
- **Jobs declarativos**: cada job es un `.yml` en [src/main/resources/jobs/](../src/main/resources/jobs/) (ya hay 25). El motor escanea esa carpeta automáticamente.
- **Pasos soportados** (enum): [StepType.java](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/StepType.java) — `TRIM, UPPERCASE, CONCATENATE, DEDUPLICATE, FILTER_NULL, LOOKUP, LINK_PARENT, SELECT, INSERT, TRUNCATE, VALIDATE_REFERENCE`.
- **Dispatch de los pasos**: **NO hay factoría ni handlers separados**. Todo el switch está en un solo método:
  [DefaultJobProcessor.applyTransformation()](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) — un `switch (step.getStepType())` con un `case` por cada tipo (líneas 202-306).
- **Firma de un paso de transformación**: muta `List<Action>` in-place (cada `Action` envuelve un `Map<String,Object>`), sin valor de retorno. Los parámetros YAML llegan en `step.getParameters()`.

## Pasos a seguir (ruta mínima)

### 1. Añadir el valor al enum `StepType`

Fichero: [StepType.java](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/StepType.java)

Añadir bajo la sección `// Transformation types` el nombre del nuevo paso (ej. `MY_NEW_STEP`).

### 2. Implementar la lógica en `DefaultJobProcessor.applyTransformation()`

Fichero: [DefaultJobProcessor.java](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) (líneas 202-306)

Añadir un nuevo `case` siguiendo el patrón existente (ver `CONCATENATE` en líneas 210-235 como referencia):

```java
case MY_NEW_STEP -> {
    // 1) Leer parámetros del YAML
    String param1 = (String) step.getParameters().get("param1");

    // 2) Validar. Si falta lo obligatorio → log WARN + break
    if (param1 == null) {
        step.addLog(LogEntry.warn(step.getName(),
                "MY_NEW_STEP missing required parameter: 'param1'"));
        break;
    }

    // 3) Transformar data in-place
    data.forEach(action -> {
        Object val = action.get("SomeColumn");
        // ... lógica ...
        action.data().put("TargetColumn", resultado);
    });

    // 4) Log de resumen
    step.addLog(LogEntry.info(step.getName(),
            "MY_NEW_STEP applied to " + data.size() + " rows"));
}
```

Convenciones a respetar (ya las siguen los pasos existentes):

- Validación manual de params; si faltan → `LogEntry.warn` + `break` (no lanzar excepción).
- Mutación in-place de `List<Action>` — nada de devolver una lista nueva.
- Terminar con un `LogEntry.info` o `LogEntry.summary` indicando cuántas filas se procesaron.

### 3. Crear el YAML del nuevo job

Fichero nuevo: `src/main/resources/jobs/<nombre>-import.yml`

Usar como plantilla [control-import.yml](../src/main/resources/jobs/control-import.yml). Estructura mínima:

```yaml
name: <nombre>-import
description: Descripción del nuevo job
enabled: true
fileType: EXCEL          # ← esto activa ExcelFileReaderAdapter automáticamente
batchSize: 500
sheetIndex: 0            # opcional; por defecto 0

source:
  type: RESOURCES
  location:
    path: <NombreFichero> # nombre del .xlsx sin extensión

tasks:
  - name: transform
    order: 1
    type: TRANSFORMATION
    subtasks:
      - name: <mi-paso>
        order: 1
        type: MY_NEW_STEP           # ← el nuevo StepType
        parameters:
          param1: "valor"

  - name: persist
    order: 2
    type: PERSISTENCE
    subtasks:
      - type: INSERT
        parameters:
          tableName: <MI_TABLA>
          autoMap: true
```

No hay registro ni configuración adicional: `LocalYamlScannerAdapter` recoge el fichero automáticamente al arrancar.

### 4. Añadir un test unitario

Fichero: [DefaultJobProcessorTest.java](../src/test/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessorTest.java)

Añadir un `@Test` siguiendo el patrón de los existentes (hay ejemplos para TRIM/CONCATENATE/LOOKUP). Helpers disponibles:

- `givenFileData(Map...)` — mockea el contenido del Excel.
- `buildJob(TaskType.TRANSFORMATION, new Step(name, StepType.MY_NEW_STEP, order, paramsMap))`.
- `processor.process(List.of(job))` — ejecuta el pipeline.
- Assertions sobre `job.getStatus()`, `stepLogs(job)` y las mutaciones del `Map` de la fila.

## Ficheros clave a modificar

| Fichero | Cambio |
|---|---|
| [StepType.java](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/StepType.java) | +1 valor al enum |
| [DefaultJobProcessor.java](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) línea 202 | +1 `case` en el switch |
| `src/main/resources/jobs/<nombre>-import.yml` | Fichero nuevo |
| [DefaultJobProcessorTest.java](../src/test/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessorTest.java) | +1 test |

## Ficheros que NO hay que tocar (reutilizables tal cual)

- `ExcelFileReaderAdapter` — el lector ya soporta `.xlsx`/`.xls`, multi-sheet, fechas, fórmulas y saltos de filas vacías antes de la cabecera.
- `FileReaderPort`, `JobFactory`, `IngestaService`, `SnakeYamlJobDefinitionAdapter` — ya enrutan `fileType: EXCEL` al adaptador correcto automáticamente.
- `pom.xml` — Apache POI 5.3.0 ya está como dependencia.

## Verificación end-to-end

1. Compilar: `mvn compile` — confirma que el switch cubre el nuevo enum (el `default` ya avisa si falta).
2. Test unitario del nuevo `case`: `mvn test -Dtest=DefaultJobProcessorTest#miNuevoTest`.
3. Arrancar la app con un Excel de prueba en la ruta declarada en `source.location.path` y verificar logs: debe aparecer `Reading Excel file: ...`, `Applying transformation: MY_NEW_STEP`, y el log de resumen que añadimos en el `case`.
4. Comprobar la tabla destino en BD tras el `INSERT`.

## Nota sobre alternativas descartadas

- **No** conviene crear clases-handler separadas (una por StepType) con dispatch por `@Component + supports()`. Rompería la convención actual del proyecto (todo en el switch de `DefaultJobProcessor`) y obligaría a refactorizar los 11 pasos existentes. Mantener el patrón actual es coherente y suficiente para este caso.
