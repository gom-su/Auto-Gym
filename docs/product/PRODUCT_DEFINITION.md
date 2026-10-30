# Definición de producto de Auto-Gym

Versión de producto 1.0 · 30 de octubre de 2026.

Versión pública derivada de la definición de producto original. Los requisitos y sus identificadores se conservan en español para mantener trazabilidad. El documento fuente se conserva localmente; el ejemplo de intercambio se ha sustituido por datos ficticios. Las decisiones confirmadas, recomendaciones técnicas y asuntos abiertos conservan su condición original.

Esta definición describe el producto previsto. La implementación actual es una prueba de concepto; consulte [la evaluación de brechas](IMPLEMENTATION_GAPS.md).

## 1. Resumen ejecutivo y decisiones aprobadas

El producto es una aplicación Android nativa en Kotlin para planificar, ejecutar, registrar y analizar entrenamientos personales. Reemplaza el flujo manual de editar tablas de Obsidian: las series se muestran precargadas y, si se ejecutan según lo esperado, se completan con un único toque. Cuando existen desviaciones, pueden ajustarse repeticiones, carga, RIR o comentarios.

La planificación se organiza en programas, bloques y semanas. Cada semana puede tener distintos días, ejercicios, cargas, objetivos y volumen; no se trata de una plantilla semanal fija. Hasta contar con MCP, la aplicación exportará un contexto estructurado para un agente externo e importará la propuesta de la semana siguiente en JSON.

| Decisión confirmada | Implicación |
| --- | --- |
| Uso personal primero | Se optimiza para un usuario, sin registro público, marketplace ni personalización genérica compleja. |
| Analítica equilibrada | Fuerza/rendimiento y peso/composición corporal reciben igual importancia. |
| Registro moderado, mínima fricción | Datos esenciales: series, peso, repeticiones, RIR, cardio y notas opcionales de entrenamiento; no cuestionarios intrusivos. |
| Planificación semanal variable | Cada semana conserva su prescripción propia y su historial independiente. |
| Arquitectura híbrida | La IA propone; un motor determinista valida; el usuario aprueba explícitamente. |
| Sin datos de salud | No se construyen módulos clínicos, consultas médicas, molestias, sueño, fatiga, diagnósticos ni restricciones sanitarias. |
| Importar/exportar desde el MVP | Los archivos estructurados permiten trabajar con agentes externos sin MCP. |

## 2. Problema, objetivos y principios de experiencia

### 2.1 Problema que se busca resolver

El registro actual en Obsidian exige navegar hasta el ejercicio correcto, desplazarse por tablas y editar manualmente repeticiones, RIR y otros valores. Aunque es expresivo, genera trabajo innecesario durante una sesión. Además, el historial de Markdown no está estructurado de forma óptima para comparar semanas, calcular tendencias o intercambiar planes con agentes.

### 2.2 Objetivos de producto

Reducir al mínimo los toques necesarios para registrar cada serie.

Conservar la diferencia entre lo planificado y lo ejecutado, sin sobrescribir la prescripción.

Permitir importar la siguiente semana, aunque cambien ejercicios, bloques, días o series.

Medir tendencias de fuerza, adherencia y composición corporal usando datos comparables.

Permitir que una IA explique propuestas y aprenda de decisiones previas sin aplicarlas sola.

Mantener la aplicación funcional sin red y sin depender de un proveedor de IA.

### 2.3 Indicadores propuestos para evaluar el producto

| Indicador | Método de medición | Meta inicial sugerida* |
| --- | --- | --- |
| Fricción | Proporción de series completas sin editar valores | ≥ 80 % de series con un toque |
| Integridad | Series registradas que permanecen tras reinicio/interrupción | 100 % en pruebas de persistencia |
| Importación | Archivos válidos aceptados sin pérdida de campos soportados | 100 % de casos de prueba |
| Trazabilidad | Propuestas aprobadas con diferencias y justificación disponibles | 100 % |
| Utilidad analítica | Gráficas distinguibles por periodo, variante y unidad | Cobertura de casos de prueba |

* Estas metas son hipótesis de calidad para validar, no compromisos previamente acordados.

## 3. Alcance y recorridos del usuario

### 3.1 Incluido en el producto

Programación por semanas variables; registro desde la aplicación y ventana flotante; series normales, de calentamiento y adicionales; cardio; historial; importación/exportación; analítica; mediciones de peso/composición corporal; motor de reglas de entrenamiento; generación de propuestas por agentes externos; aprobación explícita; memoria y evaluación de recomendaciones.

### 3.2 Excluido deliberadamente

Historias clínicas, consultas con especialistas, hallazgos y recomendaciones médicas, diagnósticos, restricciones clínicas, lesiones, molestias, sueño, fatiga u otros registros de salud. Tampoco se incluyen por ahora redes sociales, coaching para terceros, multiusuario, pagos, publicación de rutinas ni integración inmediata con servicios externos de datos corporales.

### 3.3 Recorrido principal: completar una semana

Preparación: importar una semana de entrenamiento en JSON o migrarla desde Markdown; revisar advertencias y confirmar.

Ejecución: abrir Día A/B/C/D; iniciar un ejercicio; ver serie, carga, repeticiones y RIR prellenados.

Registro: pulsar «Completar serie» si coincide con lo previsto, o modificar únicamente las desviaciones.

Cierre: guardar ejercicios, cardio, estado de sesión y comentarios; conservar datos automáticamente.

Análisis: consultar el progreso y exportar resultados/contexto para el agente externo.

Planificación siguiente: importar propuesta generada por IA, validar, comparar cambios y aprobar.

### 3.4 Estados y casos especiales

Las sesiones pueden estar planificadas, en curso, completadas, parcialmente realizadas o no realizadas. Los campos sin dato son «no informados», nunca cero implícito. Cambiar el orden de ejercicios, reabrir una sesión o modificar una serie completada no elimina el historial de cambios relevante.

## 4. Backlog consolidado y priorizado

Los 24 elementos siguientes son el backlog vigente. «MVP», «V1» y «V2» indican orden de entrega, no obligatoriedad de implantar todos los elementos antes de probar la aplicación.

MVP — Fundamentos y registro

| ID | Funcionalidad | Fase |
| --- | --- | --- |
| ENT-01 | Programas, bloques, semanas y días con estructura variable | MVP |
| ENT-02 | Importar y exportar planes y resultados en JSON | MVP |
| ENT-03 | Migración de entrenamientos desde Obsidian Markdown | MVP |
| ENT-04 | Registro de series con un toque desde ventana flotante | MVP |
| ENT-05 | Edición de carga, repeticiones, RIR, series y comentarios | MVP |
| ENT-06 | Calentamientos, descansos y sesiones parciales | MVP |
| ENT-07 | Historial local, guardado automático y recuperación de sesiones | MVP |
| ENT-08 | Identidad estable de ejercicios, variantes y máquinas | MVP |
| ENT-09 | Validación de datos, unidades y prevención de duplicados | MVP |

V1 — Analítica y progreso

| ID | Funcionalidad | Fase |
| --- | --- | --- |
| ANA-01 | Historial comparativo por ejercicio | V1 |
| ANA-02 | Gráficas de fuerza, repeticiones, RIR y volumen | V1 |
| ANA-03 | Seguimiento de peso y composición corporal | V1 |
| ANA-04 | Seguimiento de objetivos y adherencia al entrenamiento | V1 |
| ANA-05 | Comparaciones entre semanas, bloques y programas | V1 |

V1 — Inteligencia artificial

| ID | Funcionalidad | Fase |
| --- | --- | --- |
| AG-01 | Agente que solicita información faltante para planificar | V1 |
| AG-02 | Comparación y aprobación explícita de semanas propuestas | V1 |
| AG-03 | Memoria trazable de decisiones de entrenamiento | V1 |
| AG-04 | Evaluación posterior de recomendaciones anteriores | V1 |
| AG-05 | Motor determinista de progresión y validación de reglas de entrenamiento | V1 |
| AG-06 | Generación de semanas mediante archivos JSON intercambiados | V1 |
| AG-07 | Explicaciones de cambios de ejercicios, cargas, RIR y volumen | V1 |

V2 — Integraciones

| ID | Funcionalidad | Fase |
| --- | --- | --- |
| INT-01 | Servidor MCP con herramientas de consulta y creación de propuestas | V2 |
| INT-02 | Sincronización segura entre aplicación y servicios externos | V2 |
| INT-03 | Análisis avanzado de tendencias y detección de estancamientos | V2 |

## 5. Requisitos funcionales y criterios de aceptación

Esta sección transforma el backlog en requisitos comprobables. Los criterios están definidos para orientar implementación y pruebas; podrán refinarse en tickets sin cambiar el propósito del producto.

### 5.1 MVP — Fundamentos y registro

#### ENT-01  Estructura variable de programa

Alcance: Crear programas con bloques y semanas que contengan días y ejercicios distintos; no imponer una distribución fija.

Aceptación: Importar dos semanas con número de días, ejercicios y series diferentes sin perder asociaciones.

#### ENT-02  Intercambio JSON

Alcance: Importar/ exportar contexto, propuestas de semana y resultados; incluir versión de esquema y unidades.

Aceptación: Un JSON exportado se puede reimportar en una instalación de prueba sin pérdida de campos soportados.

#### ENT-03  Migración desde Obsidian

Alcance: Leer encabezados, metadatos, ejercicios, prescripciones, cargas, resultados y comentarios; advertir ambigüedades.

Aceptación: La semana de ejemplo se migra y los datos ambiguos quedan señalados para revisión, sin atribuciones inventadas.

#### ENT-04  Ventana flotante de bajo roce

Alcance: Mostrar ejercicio/serie actual y campos precargados; permitir completar en un toque.

Aceptación: Una serie sin desviaciones se registra desde el overlay con un solo botón y persiste inmediatamente.

#### ENT-05  Desviaciones registrables

Alcance: Editar carga, repeticiones, RIR y notas; modificar, omitir o añadir series.

Aceptación: La ejecución refleja los valores reales y conserva por separado la prescripción original.

#### ENT-06  Sesiones reales y cardio

Alcance: Permitir calentamientos, series de aproximación, temporizador, pausas, cardio y sesiones parciales.

Aceptación: Una sesión interrumpida reanuda exactamente donde quedó y conserva cardio y series previas.

#### ENT-07  Persistencia offline

Alcance: Guardar progresivamente en Room; reconstruir estado al reiniciar.

Aceptación: No se pierde un registro confirmado tras cierre forzado de la app o ausencia de red.

#### ENT-08  Catálogo de ejercicios

Alcance: Asignar ID estable a movimiento y variantes relevantes; registrar bases de carga y configuración de máquina.

Aceptación: Cambiar el nombre visible no rompe el historial; variantes no equivalentes se distinguen.

#### ENT-09  Validación e idempotencia

Alcance: Validar tipos, rangos, cargas, unidades, identificadores y duplicados.

Aceptación: Una segunda importación del mismo paquete no duplica semanas ni series y comunica conflictos.

### 5.2 V1 — Analítica

#### ANA-01  Comparación entre exposiciones

Alcance: Consultar todas las ejecuciones del mismo ejercicio a través de semanas y programas.

Aceptación: Una vista presenta fecha, variante, carga, reps y RIR sin mezclar unidades diferentes.

#### ANA-02  Gráficas de rendimiento

Alcance: Visualizar cargas, repeticiones, RIR y volumen; permitir contextualizar descargas y cambios.

Aceptación: La gráfica distingue semanas de descarga y no considera una serie omitida como cero.

#### ANA-03  Peso y composición corporal

Alcance: Registrar mediciones con fecha, unidad, método y campos opcionales.

Aceptación: Se visualizan tendencias sin equiparar mediciones hechas con métodos distintos sin advertencia.

#### ANA-04  Objetivos y adherencia

Alcance: Mostrar cumplimiento de sesiones, series y cardio frente al plan; objetivos configurables.

Aceptación: Los indicadores separan completado, parcial, no realizado y sin registro.

#### ANA-05  Comparaciones por fase

Alcance: Comparar semanas, bloques y programas bajo condiciones comparables.

Aceptación: El usuario puede filtrar fases y observar evolución sin tratar una descarga como regresión.

### 5.3 V1 — IA y aprobación

#### AG-01  Preguntas focalizadas

Alcance: Identificar vacíos relevantes para la planificación y solicitar información sin bloquear innecesariamente.

Aceptación: El sistema registra la respuesta o la marca como no disponible; nunca la inventa.

#### AG-02  Revisión de propuesta

Alcance: Mostrar diferencias con la semana/base relevante y solicitar confirmación antes de activarla.

Aceptación: Una propuesta importada permanece pendiente hasta aceptación expresa del usuario.

#### AG-03  Memoria de decisiones

Alcance: Guardar qué cambió, por qué, evidencia, fecha y versión de propuesta aprobada.

Aceptación: Es posible revisar retrospectivamente la causa de cada cambio significativo.

#### AG-04  Evaluación posterior

Alcance: Registrar resultados esperados y contrastarlos con datos observados en un periodo definido.

Aceptación: Cada recomendación puede terminar favorable, desfavorable, inconclusa o pendiente.

#### AG-05  Validación determinista

Alcance: Comprobar reglas configuradas de progresión, integridad y coherencia; devolver bloqueos y advertencias.

Aceptación: Una propuesta que infringe una regla obligatoria no puede activarse sin resolver el conflicto.

#### AG-06  Planificación externa sin MCP

Alcance: Permitir copiar/exportar contexto y pegar/importar la respuesta estructurada del agente.

Aceptación: Se completa el ciclo exportar → generar externamente → importar → validar → aprobar.

#### AG-07  Explicabilidad

Alcance: Relacionar cada ajuste relevante con evidencia, comparación y motivo expresado en lenguaje claro.

Aceptación: La pantalla de diferencias muestra razonamiento para aumentos, reducciones y sustituciones.

### 5.4 V2 — Integraciones y análisis avanzado

#### INT-01  Servidor MCP

Alcance: Exponer recursos/herramientas de consulta y creación de propuestas con permisos limitados.

Aceptación: El agente puede consultar contexto autorizado y crear una propuesta, pero no aprobarla.

#### INT-02  Sincronización futura

Alcance: Diseñar sincronización autenticada para usar MCP remoto sin acceso directo a Room.

Aceptación: Ante desconexión y conflicto no se pierden registros ni se acepta escritura externa sin autorización.

#### INT-03  Analítica avanzada

Alcance: Detectar señales de estancamiento con comparabilidad y evidencia suficiente.

Aceptación: Cada alerta indica período, datos usados, limitaciones y explicación verificable.

## 6. Modelo de datos y reglas de integridad

### 6.1 Entidades nucleares

| Dominio | Entidades sugeridas | Datos clave |
| --- | --- | --- |
| Planificación | Program, TrainingBlock, WeekPlan, SessionPlan | Fase, número/orden, estado, fechas, sesiones |
| Ejercicios | Exercise, ExerciseVariant, EquipmentProfile | ID estable, nombre, variante, base de carga, incrementos |
| Prescripción | ExercisePrescription, SetPrescription, CardioPrescription | Series, objetivos, rangos, carga, RIR, orden, notas |
| Ejecución | WorkoutSession, ExercisePerformance, SetPerformance, CardioPerformance | Valores reales, estado, fecha/hora, comentarios |
| Seguimiento | BodyMeasurement, TrainingGoal | Métrica, fecha, unidad, método, objetivo |
| IA y auditoría | WeekProposal, ValidationIssue, Approval, TrainingDecision, RecommendationEvaluation | Versión, fuente, reglas, justificación, resultado observado |

### 6.2 Invariantes de dominio

Todas las propuestas, semanas, ejercicios y series tienen identificadores estables e independientes del nombre visible.

Un valor de carga contiene magnitud, unidad y base de medición (total, por lado, por mano, peso corporal o configuración de máquina).

La prescripción y la ejecución se almacenan separadamente; los cambios no borran el objetivo original.

«No informado», «omitido», «no realizado» y «valor cero» son estados semánticamente distintos.

Las semanas tienen versiones; aprobar una versión no autoriza modificaciones posteriores de otra.

La procedencia identifica si el dato provino de registro manual, importación de Obsidian o propuesta de IA.

Las reglas tienen ID y versión; cada validación guarda cuáles se aplicaron.

### 6.3 Flujos de estado propuestos

Propuesta: borrador/importada → validación → requiere corrección o lista para revisar → aprobada/rechazada. Solo «aprobada» puede convertirse en semana activa.

Sesión: planificada → en curso → completada/parcial/no realizada. Los estados finales pueden corregirse mediante una acción consciente y trazable.

## 7. Arquitectura de responsabilidades

### 7.1 Principio de autoridad

| REGLA ARQUITECTÓNICA  La IA propone. El motor determinista valida. La persona usuaria aprueba. Kotlin ejecuta y persiste. MCP, cuando exista, transporta solicitudes y propuestas: no es la fuente de verdad ni el motor de reglas. |
| --- |

| Componente | Responsabilidades | Límite |
| --- | --- | --- |
| UI Android / overlay | Navegación, datos precargados, registro rápido, estadísticas y aprobación | No calcula decisiones de progresión con lógica dispersa en pantallas. |
| Dominio Kotlin | Casos de uso, entidades, métricas, políticas y validación | Sin dependencia de Android/UI para permitir pruebas unitarias. |
| Room / repositorios | Persistencia local, consultas y migraciones de esquema | No interpreta ni corrige decisiones de IA. |
| Integración portable | Contratos JSON, migración Markdown, deduplicación y exportación | No activa contenido importado sin pasar por dominio. |
| Agente externo | Analiza historial, pregunta por datos y propone semanas explicadas | No escribe sesiones reales ni autoriza su propia propuesta. |
| MCP futuro | Herramientas autenticadas de lectura y creación de propuestas | No accede directamente a una base Room remota. |

### 7.2 Módulos Kotlin sugeridos

`:app` (navegación y composición), `:feature:workout`, `:feature:planning`, `:feature:progress`, `:core:domain`, `:core:rules`, `:core:analytics`, `:data:local`, `:integration:portable`. Una pantalla simple de mediciones corporales puede vivir inicialmente en `:feature:progress`; no se crea un módulo de salud.

Tecnologías propuestas: Jetpack Compose y ViewModel para la interfaz principal; servicio de ventana superpuesta aprovechando la prueba de concepto existente; Room para almacenamiento offline; Kotlin coroutines/Flow para datos reactivos. El motor de reglas debe funcionar como Kotlin puro, aislado de la interfaz.

### 7.3 Severidad de validaciones

| Nivel | Significado | Resultado |
| --- | --- | --- |
| ERROR | Contrato mal formado, datos imposibles o regla obligatoria incumplida | Bloquea importación/activación hasta corregir |
| WARNING | Información incompleta o progresión atípica sin incumplimiento obligatorio | Permite revisión expresa y deja advertencia |
| INFO | Cambio válido que conviene explicar | Se presenta en comparación y auditoría |

### 7.4 MCP no es requisito para el MVP

En la primera etapa, el usuario copia o comparte un archivo JSON con el contexto para un agente externo, recibe una propuesta y la importa. En una etapa posterior, MCP podrá consultar recursos autorizados y crear propuestas mediante un backend o un adaptador de sincronización autenticado. Una instancia remota no puede leer directamente los datos guardados en Room en el teléfono.

## 8. Intercambio de datos e interoperabilidad

### 8.1 Contratos propuestos

| Contrato | Dirección | Mínimo requerido |
| --- | --- | --- |
| TrainingContext | App → agente | Versión, fecha, programa, fase, historial relevante, reglas, objetivos y mediciones autorizadas |
| WeekProposal | Agente → app | Versión, IDs, semana, días, ejercicios, series, diferencias y razones |
| TrainingResults | App → agente / respaldo | Sesiones ejecutadas, series, cardio, estados, notas y fechas |

El esquema JSON será versionado (por ejemplo `schemaVersion: 1`) y validado antes de persistir. IDs estables y un `importId` permiten importaciones idempotentes. Los campos desconocidos se deben manejar según una política explícita de compatibilidad, nunca descartar datos silenciosamente.

### 8.2 Ejemplo orientativo de una prescripción

```json
{
  "schemaVersion": 1,
  "type": "WeekProposal",
  "programId": "synthetic-demo",
  "weekNumber": 1,
  "sessions": [
    {
      "code": "A",
      "exercises": [
        {
          "exerciseId": "demo-exercise",
          "target": {
            "sets": 2,
            "repsMin": 6,
            "repsMax": 8,
            "rir": 3
          },
          "load": {
            "value": 20,
            "unit": "kg",
            "basis": "total"
          }
        }
      ]
    }
  ]
}
```

Ejemplo completamente ficticio para documentación; no es un esquema definitivo ni una recomendación de entrenamiento.

### 8.3 Migración desde Obsidian

El importador debe reconocer metadatos (programa, semana, bloque, fase y microciclo), títulos de días, tablas por ejercicio y apartados de cardio; conservar notas de planificación y comentarios del usuario. Debe soportar cargas totales, por mano, por lado, peso corporal y aproximaciones, además de discrepancias entre resultados y series prescritas. Estos casos conservan el dato original y requieren advertencias y revisión, sin normalizar mediante suposiciones. Los ejemplos públicos de prueba serán ficticios; los archivos reales permanecerán locales.

### 8.4 Privacidad de las exportaciones

Por defecto, el contexto destinado a un agente se limitará a información de entrenamiento. Las mediciones de peso y composición corporal solo se incluirán si se seleccionan expresamente para ese intercambio. Las exportaciones locales y copias de seguridad requieren control claro por parte del usuario.

## 9. Analítica y evolución longitudinal

### 9.1 Fuerza y rendimiento

Indicadores: carga por serie, repeticiones, RIR, mejor serie comparable, tendencia de rendimiento y volumen de entrenamiento. Las estimaciones de fuerza máxima, cuando se incorporen, deben identificarse como estimaciones y no como mediciones exactas. Un aumento de repeticiones al mismo peso y RIR puede representar progreso incluso si la carga no cambió.

### 9.2 Peso y composición corporal

La aplicación registrará peso corporal y métricas opcionales de composición corporal para observar tendencias a medio plazo. Cada dato conserva fecha, método y unidad; mediciones de métodos distintos no se interpretan como equivalentes sin avisar. La analítica reconoce que una mejora en fuerza puede coexistir con reducción de peso corporal.

### 9.3 Comparabilidad entre semanas

Comparar el mismo ejercicio/variante y la misma base de carga; no equiparar automáticamente pesos por lado con pesos totales.

Anotar el contexto de bloque, fase y descarga para no confundir una reducción planificada con pérdida de rendimiento.

Permitir filtros por programa, fase, ejercicio y periodo, evitando unir sesiones sin datos suficientes.

Diferenciar adherencia (lo realizado frente a lo prescrito) de progreso (cambio del rendimiento).

Expresar límites de la evidencia cuando faltan registros o una serie tiene un RIR estimado o ambiguo.

### 9.4 Seguimiento de las decisiones de IA

Las recomendaciones pueden registrar cambio, motivo, evidencia usada, fecha de aprobación, resultado esperado y revisión futura. El informe posterior compara lo previsto con lo registrado, clasificando el resultado como favorable, desfavorable, pendiente o inconcluso sin afirmar causalidad automática.

## 10. Requisitos no funcionales y riesgos

| Área | Requisito de diseño |
| --- | --- |
| Rendimiento UX | El botón de completar debe responder inmediatamente y nunca depender de conectividad. |
| Offline first | Uso cotidiano íntegro sin conexión; sincronización e IA siempre opcionales. |
| Fiabilidad | Persistir cada serie confirmada y restaurar el estado ante cierre del proceso. |
| Compatibilidad Android | Validar permisos, comportamiento de ventana flotante y restricciones de segundo plano en el teléfono objetivo. |
| Seguridad | Minimizar permisos; proteger almacenamiento y exportaciones con controles apropiados; no incorporar módulos clínicos. |
| Evolución | Migraciones versionadas de Room y JSON; tests de compatibilidad con versiones anteriores. |
| Observabilidad | Logs locales de errores de importación y validación sin volcar datos personales innecesarios. |
| Accesibilidad | Controles grandes, texto legible y registro utilizable desde la pantalla principal si el overlay falla. |

### 10.1 Riesgos relevantes

Ambigüedad del Markdown existente: requiere vista de revisión y política de campos sin información.

Equivalencia de ejercicios y máquinas: las gráficas serán engañosas si se mezclan variantes o bases de carga.

Limitaciones de Android: permisos de superposición, ciclo de vida y cierres del proceso pueden afectar el registro flotante.

Crecimiento de esquemas: definir versión, procedencia y migraciones desde el primer entregable.

Propuestas de IA insuficientemente justificadas: el motor de reglas y la aprobación explícita deben mantenerse independientes.

Datos de composición corporal: aunque se excluyó salud clínica, siguen mereciendo minimización y control de exportación.

## 11. Roadmap, pruebas y definición de entregado

### 11.1 Plan de entrega sugerido

| Iteración | Objetivo demostrable | Dependencias |
| --- | --- | --- |
| 0 · Base | Modelo de dominio + JSON versionado + pruebas de importación + Room | Definir contrato de datos |
| 1 · Registro | Semana activa, series precargadas, overlay y persistencia offline | Iteración 0 |
| 2 · Historial | Resultados por ejercicio, cardio y estado de sesiones | Iteración 1 |
| 3 · Ciclo IA | Exportar contexto, importar propuesta, validar, comparar y aprobar | Iteraciones 0–2 |
| 4 · Analítica | Gráficas longitudinales, composición corporal y adherencia | Historial de calidad |
| 5 · Aprendizaje | Memoria de decisiones y evaluación posterior | Ciclo IA + analítica |
| 6 · MCP | Interfaz de herramientas, autenticación y sincronización | Contrato estable + reglas |

### 11.2 Pruebas de aceptación integradas

Importar una semana real de Obsidian, revisar datos ambiguos y obtener una semana ejecutable sin perder notas.

Completar una serie como estaba prevista con un solo toque desde ventana flotante.

Modificar peso, RIR y repeticiones de una serie real y confirmar que la prescripción permanece intacta.

Reiniciar la aplicación durante una sesión y recuperarla con los datos previamente guardados.

Importar dos semanas distintas y consultar una gráfica del mismo ejercicio sin mezclar variantes.

Exportar TrainingContext, generar WeekProposal externamente, importar, validar, comparar y aprobar.

Intentar aprobar una propuesta que viola una regla obligatoria: debe bloquearse e indicar el motivo.

Reimportar un mismo archivo sin duplicados y conservar historial de procedencia y decisiones.

Exportar contexto para agente sin métricas corporales salvo selección explícita.

### 11.3 Definición de terminado por funcionalidad

Una función se considera entregada cuando cuenta con comportamiento implementado, validación de entradas, manejo de errores, pruebas automatizadas pertinentes, persistencia/compatibilidad cuando aplique, textos comprensibles y verificación manual en el dispositivo Android objetivo.

## 12. Registro de decisiones y asuntos abiertos

### 12.1 Decisiones confirmadas

| Tema | Decisión |
| --- | --- |
| Fuente de verdad | Aplicación Android local; Obsidian es solo fuente de migración inicial. |
| IA | Genera propuestas completas; aprobación humana obligatoria. |
| Validación | Motor determinista independiente del agente. |
| Transporte inicial | JSON importable/exportable y opción de copiar/pegar. |
| MCP | Integración posterior; no condiciona el MVP. |
| Analítica | Misma importancia para fuerza/rendimiento y peso/composición corporal. |
| Datos excluidos | Todo el ámbito clínico, salud, síntomas y recuperación subjetiva. |

### 12.2 Asuntos pendientes para diseño detallado

Definir versiones mínimas soportadas de Android y el comportamiento final de ventana flotante, notificación y pantalla principal.

Fijar taxonomía inicial de ejercicios, variantes y perfiles de máquinas (incluidos incrementos de carga).

Diseñar el esquema JSON formal completo, compatibilidad hacia atrás y niveles de validación.

Decidir estrategia de respaldo/restauración local y futura sincronización entre dispositivos.

Definir las reglas iniciales de progresión por ejercicio, incluyendo situaciones de descarga y cambio de programa.

Diseñar los gráficos iniciales y las condiciones mínimas de comparabilidad de cada indicador.

Precisar cómo manejar importaciones Markdown parciales o inconsistentes y las decisiones de corrección del usuario.

| CRITERIO RECTOR  La primera versión debe resolver extraordinariamente bien el registro diario y el intercambio semanal con un agente. Toda funcionalidad nueva debe justificar su complejidad frente a esa experiencia principal. |
| --- |

Fin de la definición de producto · Versión 1.0
