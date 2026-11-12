# AutoGym · Contrato compartido del modelo de entrenamiento

Estado: **borrador v2, acordado con Codex, pendiente del commit del modelo** (Claude implementa; Codex revisó la v1 y pidió los cambios incorporados aquí).
Origen: [AUTOGYM_MEJORAS_PLAN_Y_ENTRENAMIENTO.md](AUTOGYM_MEJORAS_PLAN_Y_ENTRENAMIENTO.md).

Este documento fija los campos que comparten el plan, la generación, la sesión, las superficies (app, notificación, burbuja, reloj) y el historial. Todo cambio de un campo listado aquí debe actualizarse primero en este archivo.

## 1. Principios

- Lo prescrito no cambia por un registro. El registro guarda lo realizado; la prescripción de la sesión queda como referencia.
- Los datos anteriores se leen sin migración destructiva. Los snapshots sin campos nuevos reciben valores por defecto documentados.
- Un valor nunca medido o no registrado es **ausente (`null`)**, no cero ni un valor inventado.

## 2. Unidades de carga

| Concepto | Dónde vive | Regla |
|---|---|---|
| **Unidad prescrita** | `PlannedSet.unit` (se mantiene) | Viene del plan o de la generación. Es la guía de la serie. |
| **Unidad registrada** | `SetRecord.recordedUnit: WeightUnit?` y también en cada `SetRevision` | Unidad con la que se guardó esa serie o esa versión anterior. `null` = la prescrita. Así una edición posterior conserva interpretable el valor anterior. |
| **Unidad preferida** | `preferredUnit: WeightUnit?` en el **catálogo**, por `exerciseId`, limitada a `KG` o `LB` | Se actualiza al guardar una serie con unidad corregida. El agente la usa al generar o regenerar. |

Reglas:
- La base de carga (`basis`: por mano, total, carga adicional, ajuste de máquina) es independiente de la unidad.
- Al guardar con unidad distinta de la prescrita se conserva el objetivo prescrito y se registra `recordedUnit`.
- Los valores se guardan en décimas de la unidad registrada. No se convierte kg↔lb al guardar.
- Aceptación: serie prescrita en kg que se registra en lb → la siguiente propuesta de ese `exerciseId` prescribe en lb; otros ejercicios conservan su unidad.

## 3. Tipo de serie

`PlannedSet.warmup: Boolean` se sustituye por `setType: SetType`:

```
enum SetType { WORK, WARMUP, APPROACH }
```

- `WORK`: serie de trabajo prescrita. También puede ser una serie de trabajo añadida durante la sesión, con `extra = true`.
- `WARMUP` / `APPROACH`: calentamiento o aproximación prescritos por el agente. Se identifican como tales en revisión, sesión, notificación, burbuja e historial.
- Un `WARMUP`/`APPROACH` **no** alimenta la preselección de RIR ni de carga de las series de trabajo.
- **Lectura de snapshots antiguos**: `warmup = true` se lee como `APPROACH` y conserva `extra = true`. Si existen ambos campos, manda `setType`.

## 4. Carga prescrita obligatoria

Cada `PlannedSet` de fuerza lleva `weightTenths`, `unit`, `basis` y `loadPrescribed`.

- Un `0` es válido cuando `loadPrescribed = true` (carga prescrita de cero o peso corporal sin extra).
- `loadPrescribed = false` solo indica que no había carga prescrita; la validación de §8 bloquea la aprobación en ese caso.

## 5. Descanso

**Configuración y resolución**
- El plan guarda el descanso por serie, ejercicio o grupo, con excepciones por semana. La propuesta resuelve un `restSeconds` concreto por serie.
- Rango válido: **45 s ≤ restSeconds ≤ 120 s** (límites inclusivos) para lo configurado.

**Segmento de descanso (entidad propia de la secuencia de sesión, no dentro de `SetRecord`)**

```
data class RestSegment(
  val id: String,              // único por sesión; identifica el descanso en las acciones
  val afterStepId: String,     // paso que lo precede
  val startedAtMillis: Long,   // reloj monotónico + pared + bootCount, como RestDeadline
  val configuredSeconds: Int,  // ajustable con −15 s/+15 s dentro de 45–120 s
  val pausedMillis: Long,      // pausa de sesión ocurrida dentro del descanso, registrada aparte
  val confirmedAtMillis: Long?,// instante de OK o Saltar; null mientras está abierto
  val skipped: Boolean = false
)
```

- **Actual rest** = `confirmedAtMillis − startedAtMillis − pausedMillis`. Se guarda como `actualRestSeconds` derivado del segmento; no se duplica en `SetRecord`.
- **Vencimiento ≠ confirmación.** Al llegar a cero el segmento sigue abierto y pendiente de OK, también tras reabrir la app. `RestFinished` **no** avanza el paso automáticamente, ni al restaurar.
- **OK** y **Saltar descanso** cierran el mismo segmento e inician el siguiente paso en una sola transición persistida.
- La acción incluye `sessionId` y `restSegmentId`. Un toque duplicado o atrasado (reloj, burbuja, notificación) con un id ya cerrado no hace nada.
- **−15 s / +15 s** cambian `configuredSeconds` dentro de 45–120 s sin reiniciar el tiempo transcurrido.
- El tiempo real puede superar 120 s; solo el valor configurado está acotado.
- Ejemplo de aceptación: 75 s programados, OK 20 s después del aviso, 10 s de pausa dentro del descanso → **95 s de descanso + 10 s de pausa**.

## 6. Secuencia única de sesión: fuerza y cardio

- La sesión planificada es una **lista de pasos con identidad estable**, en la que cada paso es una serie de fuerza o un paso de cardio. El orden es único para ambos.

```
data class PlannedCardio(
  val stepId: String,          // identidad estable en la secuencia
  val modality: String,
  val plannedSeconds: Int,     // A/B/C: 1800; D: 3600
)
```

- `CardioEntry` añade `plannedCardioId: String?`. Un cardio manual anterior conserva `null`.
- Un paso de cardio se ejecuta por duración: iniciar, pausar, terminar. Guarda el tiempo realizado. Si se cierra antes, queda **parcial** con el tiempo realizado.
- Un día D sin series de fuerza es una sesión planificada válida. Debe poder iniciarse desde **Hoy** y **Plan**, guardar el cardio al terminar y aparecer en historial y adherencia tras reabrir la app.
- **Demo vs. planificado**: la sesión demo se identifica por `source == null`. `plan` vacío con `source != null` es un día planificado sin series, nunca el demo. `activePlan()` no debe sustituir una lista vacía por el demo cuando hay `source`.
- El archivado conserva sesiones sin `SetRecord` cuando tienen cardio registrado.
- Los 30/60 minutos y la estructura A/B/C/D se validan en el plan que los prescribe (§8), no como regla global.

## 7. Comentarios

```
data class SessionComment(
  val id: String,
  val text: String,            // máx. MAX_NOTE_LENGTH
  val createdAtMillis: Long,
  val programId: String,
  val weekId: String?,         // semana de origen; no es la clave de vigencia
  val sessionId: String?,
  val exerciseId: String?,     // null = comentario general de la sesión
  val setIndex: Int?,          // solo si va asociado a una serie
  val resolvedAtMillis: Long?  // null = vigente
)
```

- **Almacén propio**: los comentarios no viven dentro de la semana ni de sus registros. Reemplazar una semana y borrar sus registros **no** borra comentarios; `weekId` queda solo como origen.
- **Contexto de generación**: se incluyen comentarios vigentes (`resolvedAtMillis = null`) del **mismo `programId`**. Para comentarios de ejercicio, solo los del `exerciseId` que aparece en la generación.
- Las notas de serie y cardio existentes (`SetRecord.notes`, `CardioEntry.notes`) siguen válidas.

## 8. Validación (lado Claude)

Antes de marcar una propuesta como lista para aprobar:
1. Toda serie de fuerza tiene `unit` y `weightTenths` explícitos (o `loadPrescribed = true` con 0).
2. Días A, B, C y D presentes en cada ciclo, también en semanas de descarga.
3. A, B y C contienen un `PlannedCardio` de 1800 s; D contiene uno de 3600 s y puede no tener series de fuerza.
4. `restSeconds` dentro de 45–120 s en cada serie.
5. Un error indica ejercicio/día/serie afectados y la corrección requerida.

## 9. Compatibilidad

Tres versiones independientes:
- **Versión de sesión** (`WorkoutState`): sube para `setType`, `RestSegment`, `PlannedCardio` y `plannedCardioId`.
- **Versión de respaldo completo** (`BackupCodec`): sube; el respaldo comprueba los campos del original contra el objeto reserializado, así que hay que probar un **respaldo v1 real que contenga `warmup`**, aunque la escritura nueva emita `setType`.
- **Versión de intercambio JSON/MCP** (`docs/JSON_EXCHANGE.md`, `docs/MCP_SERVER.md`): se actualiza en el mismo cambio que el código.

Reglas de lectura:
- Versiones anteriores se leen con los defaults de §3 y §5.
- Descansos históricos fuera de 45–120 s se leen sin alterarse.
- Tiempos que nunca se midieron quedan `null` (no se rellenan con 0).
- Los planes y registros anteriores deben seguir leyéndose sin pérdida.

## 10. Puntos cerrados con Codex (v2)

- Nombres confirmados: `setType`, `recordedUnit`, `preferredUnit`, `PlannedCardio`, `SessionComment`.
- `preferredUnit` vive en el catálogo por `exerciseId`, solo `KG`/`LB`.
- `actualRestSeconds` se deriva de `RestSegment`, separado de `SetRecord`.
- Pendiente solo de implementación: el nombre exacto del campo de id de paso en `SetRecord` (`stepId` o mantener `index`), que se decide al hacer el commit.
