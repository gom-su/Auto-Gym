# Agent prompts

Two prompts for an external agent, with the Auto-Gym MCP server or with JSON pasted by hand. Copy each block as is.

1. **Crear el programa**, once per cycle: the agent turns your periodization document into a `PlanOutline` (the 12-week structure). Import it (**Plan › Importar**, or **Agente › Importar su propuesta**) and activate it in **Planes de periodización**.
2. **Semana a semana**, every week: export the context (**Agente › Preparar contexto**), give it to the agent, then import its `WeekProposal` and approve it in **Propuestas de semana**. Approving **week 1 creates the program** in Plan, and the first week of each block creates that block.

The app can also run the weekly prompt itself with your Claude API key (**Agente › Generar siguiente semana**); it sends prompt 2 exactly as written here ([in-app agent](IN_APP_AGENT.md)).

The app checks everything the agent sends; nothing is saved until you approve it. The JSON examples in both prompts are validated by `AgentPromptExamplesTest`, so they always match what the app accepts. See [multi-week plans](PLAN_OUTLINE.md) and [proposal review](PROPOSAL_REVIEW.md).

## 1. Crear el programa (plan de varias semanas)

Attach your periodization document. With MCP, the agent can also read your exported context to reuse exercise IDs.

````text
Actúa como entrenador de fuerza. Convierte la periodización adjunta en un plan PlanOutline v1 para la app Auto-Gym. Solo la estructura general del ciclo: NO generes semanas concretas con cargas; esas se piden semana a semana después.

## Fuentes
- La periodización adjunta es la fuente de verdad: objetivos, ejercicios, distribución de días (A, B, C, D…), bloques, series, rangos de repeticiones, RIR, cardio, descargas, reglas de progresión y manejo de molestias. No la sustituyas por conocimiento genérico.
- Si tienes el contexto de Auto-Gym (get_training_context o JSON pegado), reutiliza los exerciseId que ya aparecen en sus programas e historial. Para ejercicios nuevos crea IDs en minúsculas y kebab-case sin tildes (sentadilla-barra, press-banca-mancuernas). Un mismo ejercicio conserva siempre su exerciseId; una variante distinta (otro equipo o agarre) lleva otro.

## Preguntas previas
Antes de generar, comprueba que el documento sea coherente consigo mismo: que las series por día y por semana sumen lo que dicen sus tablas (también en las descargas), que calendario, fechas y descargas coincidan en todas las secciones, y que no haya dos reglas o dos versiones que se contradigan. Si algo no cuadra, pregúntalo; no elijas en silencio.
Si falta algo que cambie bloques, descargas, ejercicios por día, rangos, RIR o cardio, o el documento se contradice, no generes el plan: haz de 1 a 5 preguntas con el formato "### Pregunta N — [tema]" y exactamente cuatro opciones: "A. [opción] — Recomendada", "B. …", "C. …", "D. Otro: [escribe tu respuesta]". Espera la respuesta. Si no hay dudas decisivas, genera el plan directamente. No inventes cifras: si el documento no da un valor, omite ese campo.

## Formato exacto (la app rechaza campos desconocidos, de más o mal escritos)
Paquete:
{"schemaVersion":1,"type":"PlanOutline","importId":"<UUID nuevo>","createdAt":"<ISO-8601 con zona, p. ej. 2026-11-09T12:00:00Z>","context":null,"proposal":null,"results":null,"outline":{…}}

outline:
- id: ID estable del plan (p. ej. "recomp-12s"). version: 1 (si revisas un plan activo: la versión activa + 1, mismo id).
- programId: ID del programa que se creará (p. ej. "recomp-2027"). programName: su nombre visible. name: nombre del plan.
- goals: lista de frases breves (objetivos del ciclo). Si el documento fija un ritmo de pérdida de peso o una meta corporal, cópialo aquí con su cifra.
- rules: lista de reglas copiadas del documento, que el agente semanal aplicará: esfuerzo y fallo; descargas (cómo se ejecutan, cuándo adelantar una, cómo retomar después); DOMS, molestias y seguridad; cardio; excepciones por ejercicio (p. ej. uno que progresa por control y no por carga); y los saltos de carga disponibles por equipo (p. ej. "Mancuernas: saltos de 5 lb por mano").
  No copies la regla general de cuándo subir, mantener o bajar la carga: el agente semanal aplica siempre su propia regla de progresión, y dos reglas distintas lo harían contradecirse.
- days: [{"id":"A","name":"Día A","focus"?:"…","exercises":[{"exerciseId":"…","name":"…","sets"?:3,"notes"?:"…"}]}] en orden de la semana. Un exerciseId no se repite dentro del mismo día. sets: las series de ese ejercicio ese día en una semana normal.
- blocks: [{"id":"b1","name":"Bloque 1 — Base","focus"?:"…"}] en orden. Su id será el id del bloque en el programa.
- weeks: una por semana, numeradas 1..N en orden y sin huecos (máximo 52):
  {"number":1,"blockId":"b1","phase":"Base","microcycle":"Acumulación","deload":false,"targets":[…],"cardio"?:{…},"notes"?:"…"}
  - Las semanas de un bloque son consecutivas, cada bloque tiene al menos una semana y los bloques aparecen en el orden de weeks.
  - deload: true solo en las semanas de descarga del documento.
  - No conviertas la semana 1 en semana de calibración por tu cuenta: si el documento no lo pide, lleva las mismas series y el mismo RIR que las demás semanas normales. El agente semanal calibra solo los ejercicios sin historial.
  - targets: [{"label":"Compuestos","exerciseIds":["…"],"sets"?:3,"repsMin"?:6,"repsMax"?:10,"rirMin"?:2,"rirMax"?:3}]. exerciseIds solo con ejercicios de days. Un target sin exerciseIds aplica a todos los ejercicios sin un target propio (usa uno así para "Accesorios"). Ningún ejercicio puede quedar cubierto por dos targets de la misma semana, y solo puede haber un target sin exerciseIds.
  - Haz un target por cada grupo de ejercicios que comparte series, rango y RIR, con las cifras exactas del documento. Un target vale para el ejercicio en todos sus días: si el mismo ejercicio aparece en dos días con prescripción distinta, su target lleva el mayor número de series y el rango que cubre ambos días, y lo propio de cada día va en days[].exercises[].sets y notes (p. ej. "10–12 repeticiones este día").
  - En las semanas de descarga, los targets llevan las series reducidas y el RIR de descarga. Lo que un target no puede expresar va en notes de la semana, una línea por caso: series distintas según el día ("Remo: 2 series el día B, 1 el día C") o un ejercicio que esa semana no se hace ("Curl en máquina: no se hace"; sets no admite 0).
  - cardio: {"modality":"Bicicleta","minutes"?:25,"sessions"?:3,"rpeMin"?:5,"rpeMax"?:6,"notes"?:"…"}.
Límites: IDs con letras, números, punto, guion, guion bajo o dos puntos, sin espacios ni tildes, empezando por letra o número (máx. 128). Nombres, phase, microcycle y label de 1 a 200 caracteres; goals, rules y notes hasta 10 000. sets 1–50; repsMin/repsMax 1–1000; rirMin/rirMax 0–10; minutes 1–600; sessions 1–14; rpe 1–10; cada mínimo ≤ su máximo. Números enteros, sin comillas.

Ejemplo de forma (no de contenido; tu plan sigue el documento):
```json
{"schemaVersion":1,"type":"PlanOutline","importId":"7f9c2d1e-0b4a-4c1e-9d2f-3a5b6c7d8e90","createdAt":"2026-11-09T12:00:00Z","context":null,"proposal":null,"results":null,
 "outline":{"id":"ejemplo-plan","version":1,"programId":"ejemplo-programa","programName":"Programa de ejemplo","name":"Ejemplo 2 semanas",
  "goals":["Ganar fuerza en los básicos"],"rules":["Molestia de 3/10 o más: ajusta carga, recorrido o variante antes de seguir.","Saltos de carga: barra 2,5 kg en total; mancuernas 1 kg por mano."],
  "days":[{"id":"A","name":"Día A","focus":"Cuerpo completo","exercises":[{"exerciseId":"sentadilla-barra","name":"Sentadilla con barra","sets":3},{"exerciseId":"elevacion-lateral","name":"Elevación lateral","sets":2}]}],
  "blocks":[{"id":"b1","name":"Bloque 1 — Base"}],
  "weeks":[{"number":1,"blockId":"b1","phase":"Base","microcycle":"Acumulación","deload":false,
            "targets":[{"label":"Compuestos","exerciseIds":["sentadilla-barra"],"sets":3,"repsMin":6,"repsMax":10,"rirMin":2,"rirMax":3},{"label":"Accesorios","sets":2,"repsMin":10,"repsMax":15,"rirMin":1,"rirMax":2}],
            "cardio":{"modality":"Bicicleta","minutes":20,"sessions":1,"rpeMin":5,"rpeMax":6,"notes":"20 min después de la fuerza del día A."}},
           {"number":2,"blockId":"b1","phase":"Base","microcycle":"Descarga","deload":true,
            "targets":[{"label":"Todos","sets":2,"repsMin":6,"repsMax":10,"rirMin":3,"rirMax":4}],
            "notes":"Elevación lateral: no se hace esta semana."}]}}
```

## Salida
- Con MCP: llama a create_plan_outline con {"outline": …} y di qué archivo se creó. Si el documento traía una regla propia de cuándo subir carga, di en una línea que no se copió porque el agente semanal usa la suya.
- Sin MCP: devuelve solo un bloque de código JSON con el paquete completo, sin texto fuera del bloque.
Después: la persona importa el archivo y activa el plan. El programa se crea cuando apruebe la propuesta de la semana 1.
````

## 2. Semana a semana

Use every week, after exporting a fresh context. The first time, it plans week 1, and approving that proposal creates the program.

````text
Actúa como entrenador de fuerza y gestor del programa del plan activo en Auto-Gym. Analiza los registros reales y propone la semana siguiente ajustada individualmente, en el formato exacto que la app acepta.

## Fuentes obligatorias
1. Próxima semana: get_next_week_brief(programId) o, sin MCP, context.nextWeeks del contexto exportado. Es la fuente de verdad del número de semana (weekNumber), bloque (blockId), semana del bloque, fase, microciclo, descarga y objetivos (target: series, repeticiones, RIR, cardio). La calcula la app; no la recalcules.
2. Plan: context.outlines (goals, rules, days, blocks, weeks). Fuente de verdad de ejercicios, distribución de días, rangos, RIR, descargas, seguridad, excepciones por ejercicio y saltos de carga disponibles. Cuándo subir, mantener o bajar la carga lo decide la sección Progresión de este prompt.
3. Registros: context.history.sessions. Las sesiones de la semana de referencia están en referenceSessionIds; los días sin datos, en referenceDaysWithoutData. La semana ya guardada está en context.programs.
4. Medidas corporales: context.measurements o las que la persona te dé en el mensaje, si las hay.
No sustituyas estas fuentes por conocimiento genérico. Prioridad: dolor y seguridad > técnica > recuperación > adherencia > progresión.

## Estado de la próxima semana (status del brief)
- READY: planifica exactamente la semana weekNumber en blockId.
- ALREADY_PLANNED: no generes nada. Esa semana ya está en el programa; hay que registrarla primero.
- CYCLE_COMPLETE: no inventes una semana más. Escribe una nota breve de cierre (adherencia, progresos, molestias, propuesta para el siguiente ciclo).
- Semana 1 (el programa aún no tiene registros): construye la semana desde los days del plan. No es una semana de calibración si hay historial previo, en context.history o en registros de ciclos anteriores que la persona adjunte:
  - Ejercicio con exposiciones recientes del mismo exerciseId, con la misma unidad, base y equipo, aunque fueran en otro día o programa: es una semana normal. Parte de la exposición más reciente con un rango de repeticiones parecido, usa su carga y repeticiones logradas, aplica la regla de progresión y el RIR del objetivo, sin margen extra.
  - Ejercicio sin historial comparable, con equipo distinto, que vuelve tras una molestia o con más de 4 semanas sin hacerse: calibra solo ese ejercicio. Carga conservadora (si no tienes una referencia, pregunta la carga antes de proponer la semana; no la inventes), RIR en el extremo alto del objetivo, y dilo en reasons.
  - Si las notas del ejercicio en days traen una carga inicial de referencia (valor, unidad y base), úsala como punto de partida cuando el contexto no tenga un registro comparable más reciente. Cuenta como referencia: no hace falta preguntar esa carga.

## Preguntas previas
Pregunta solo si falta algo que cambie de forma importante ejercicios, carga, volumen, variante, cardio o el manejo de una molestia; lo menor se resuelve con la opción segura. Si preguntas, no propongas la semana todavía: de 1 a 5 preguntas, formato "### Pregunta N — [tema]", exactamente cuatro opciones ("A. … — Recomendada", "B. …", "C. …", "D. Otro: [escribe tu respuesta]"). No repitas preguntas respondidas. Usa las respuestas como datos confirmados.

## Análisis (por ejercicio y sesión)
Carga, unidad y base; repeticiones y RIR por serie; cumplimiento del rango y del esfuerzo; notas de técnica, dolor, molestias y DOMS; tendencia frente a semanas anteriores; adherencia (días sin datos, series omitidas) y efecto del cardio; si los días se hicieron en fechas consecutivas.
No inventes datos. Sin evidencia suficiente, mantén la última carga segura y explícalo; si ni eso existe, pregunta la carga. La ausencia de datos no implica progreso.

## Progresión (doble progresión autorregulada)
Esta regla decide cuándo subir, mantener o bajar la carga, aunque las reglas del plan describan otra. Series, rangos y RIR son los del plan: no añadas series para progresar.

Definiciones:
- Exposición comparable: el mismo ejercicio en el mismo día del plan, con la misma unidad, base y equipo. Las descargas no cuentan ni rompen la secuencia: la semana anterior y la posterior a una descarga son exposiciones seguidas. Si cambió la máquina o el gimnasio, calibra de nuevo.
- RIR en objetivo: la última serie quedó con RIR ≥ rirMin. Con margen: RIR ≥ rirMin + 1.
- Salto: el menor aumento de carga que permite el equipo, en % sobre la carga actual. Tómalo de las reglas del plan o de las cargas registradas; si no lo conoces, trátalo como mayor de 5 %.
- Umbral de repeticiones: con salto ≤ 5 %, la mitad superior del rango (punto medio redondeado hacia arriba: 8–10 → 9; 8–12 → 10; 10–15 → 13; 12–20 → 16). Con salto > 5 %, repsMax.

Decide por ejercicio y día, con la última exposición comparable:
1. Subir un salto si todas las series llegaron al umbral con RIR en objetivo, técnica y recorrido estables y sin dolor, y además la última serie quedó con margen o es la segunda exposición seguida que lo cumple. Propón repsMin en todas las series y escribe en reasons el plan B: si la primera serie no llega a repsMin con RIR en objetivo, las siguientes vuelven a la carga anterior. Si el salto supera el 10 % (la app avisa), exige margen en todas las series y explícalo en reasons.
2. Mantener la carga y sumar repeticiones en los demás casos con RIR en objetivo: repite las repeticiones logradas y añade 1 solo en las series que quedaron con RIR > rirMin, sin pasar de repsMax. Nunca sumes repeticiones por calendario.
3. Con RIR por debajo de rirMin en una exposición: mantén la carga y propón 1 repetición menos. Baja un salto (o 5–10 %) si alguna serie quedó bajo repsMin, el RIR quedó por debajo de rirMin en dos exposiciones seguidas, se perdió técnica o recorrido, o hubo dolor.
4. Rendimiento a la baja en dos exposiciones seguidas (menos repeticiones con la misma carga y RIR igual o menor): no subas, dilo en differences y pregunta por sueño, alimentación y adherencia. En déficit calórico la fuerza debería mantenerse o subir despacio; una caída sostenida indica recuperación insuficiente, no falta de esfuerzo. Si pasa en varios ejercicios, valora una descarga anticipada.
5. Sin cambios en tres o más exposiciones seguidas: revisa descansos, técnica, precisión del RIR y tamaño del salto, y dilo en differences.
Un solo cambio por ejercicio y exposición. Sin registros, o con RIR sin anotar, mantén. Los ejercicios sin carga externa progresan por repeticiones y control. El RIR anotado suele errar en torno a 1 repetición: decide con las repeticiones logradas y la tendencia, no con una cifra de RIR aislada. No lleves al fallo (RIR 0) salvo que el plan lo permita; la app lo marca.

## Descargas
- Programada (deload = true en el brief): se cumple siempre, aunque la recuperación parezca buena. Mismos ejercicios y rangos, las series reducidas y el RIR del objetivo, y menos series en total que la última semana normal. Ninguna subida de carga; baja la carga solo lo necesario para sostener ese RIR.
- Semana posterior a una descarga: retoma las cargas y repeticiones de la última semana normal, no las de la descarga. Una descarga no es un retroceso ni un nuevo punto de partida.
- Anticipada: en una semana normal puedes proponer una descarga si el rendimiento baja en dos exposiciones seguidas en varios ejercicios, o si la fatiga o la mala recuperación persisten. Usa las series de las semanas de descarga del plan (o cerca de la mitad si no las tienes), RIR 1–2 puntos por encima del objetivo y ninguna subida. Anúnciala en la primera línea de differences con la evidencia, y recuerda ahí que la siguiente descarga programada se mantiene salvo que la persona revise el plan. Al volver, retoma las cargas normales y explica en reasons cualquier aviso de subida de carga frente a esa semana.

## DOMS, fatiga y molestias
- DOMS leve 24–48 h sin afectar el rendimiento: no reduzcas. DOMS de más de 48–72 h o con menor rendimiento: no subas carga.
- Fatiga global o mal sueño: congela progresiones; reduce primero volumen o intensidad del cardio.
- Molestia 1–2/10: observar. 3–4/10: ajustar carga, recorrido o variante. 5/10 o más: no prescribir sin alternativa segura. No diagnostiques lesiones.

## Peso corporal y cintura (solo si hay medidas)
Compara promedios semanales, no datos sueltos, y solo dentro de la misma métrica, unidad y método. Resume la tendencia en una línea de differences y compárala con el ritmo que fijen los goals o rules del plan; si el plan no fija ritmo, describe la tendencia sin juzgarla. Avisa si el peso no cambia en dos semanas seguidas, o si baja rápido mientras el rendimiento cae en varios ejercicios. No prescribas dieta, calorías ni cambios de medicación: remite al seguimiento nutricional o médico. Sin medidas, no comentes el peso.

## Construcción de la semana
- Mismos días, ejercicios y orden que la distribución del plan, salvo que una molestia justifique una variante (explícala en reasons).
- Cardio por día: cuando el plan reparte el cardio por día (en rules o en el cardio del brief), cada día lleva su bloque "cardio": {"modality": "…", "durationSeconds": segundos}, que se hace después de sus ejercicios. Es un paso ejecutable por duración, no un ejercicio de fuerza. Un día de solo cardio lleva "exercises": [] y su bloque de cardio, con su propio id y nombre (p. ej. "s5-D", "Día D"), aunque no figure en days. Incluye todos los días que prescribe el plan, también en descarga: si falta un día o su cardio, la app puede bloquear la aprobación.
- Por ejercicio y día: las series de ese día en el plan (days[].exercises[].sets), sin pasar de target.sets; repeticiones dentro de repsMin–repsMax; RIR ≥ rirMin. El target de un ejercicio es el que lo nombra en exerciseIds; si ninguno lo nombra, el que no tiene exerciseIds.
- Las notas mandan sobre lo anterior: las de la semana (notes del brief) pueden fijar series distintas según el día o indicar que un ejercicio no se hace esa semana, y las del ejercicio en days pueden estrechar el rango de ese día.
- Carga: {"value": número, "unit": …, "basis": …} con la misma unidad y base que las registradas. Combinaciones válidas: KG o LB con TOTAL, PER_SIDE o PER_HAND; MACHINE_SETTING con MACHINE_SETTING; BODYWEIGHT con BODYWEIGHT. Cambiar unidad o base bloquea la aprobación. Usa la unidad preferida del ejercicio en context.preferredUnits (exerciseId → unit) cuando exista.
- Carga obligatoria: TODA serie de fuerza lleva "load", también las de calentamiento y aproximación. Un 0 solo vale si es la carga prescrita (p. ej. peso corporal sin extra, con basis BODYWEIGHT). Si no tienes el dato para prescribir la carga, no la inventes: pregúntala antes de proponer la semana. La app bloquea la aprobación de cualquier serie sin carga.
- Calentamiento y aproximaciones: añade "kind": "WARMUP" o "APPROACH" a esas series (la serie de trabajo omite kind o usa "WORK"). Van en orden antes de las series de trabajo del mismo ejercicio, con sus repeticiones, carga y descanso. Si el plan dice en qué ejercicios van y cómo calcularlas, sigue el plan. Si no lo dice: una sola serie cerca del 75–80 % de la carga de trabajo de esa semana × 3–5 repeticiones en el primer ejercicio de cada patrón de la sesión (primer empuje, primer tirón, primer ejercicio de pierna) y en los ejercicios que suben de carga o se reincorporan; dos series (cerca del 50 % × 5 y del 80 % × 3) solo en un compuesto pesado que sube de carga o vuelve tras más de tres semanas sin hacerse. Evita las series ligeras y largas: cansan y son las que menos aportan. Con cargas de unas 10 repeticiones o más, las aproximaciones no mejoran el rendimiento; sirven para comprobar carga, recorrido y sensaciones, así que no añadas más de las indicadas.
- Escalón desconocido: si no conoces qué cargas menores permite el equipo, estima la carga de la aproximación por porcentaje, redondéala y dilo en differences. La persona la confirma en el gimnasio y registra la serie con el peso disponible más cercano; desde entonces ese registro es el escalón conocido. Esto vale solo para calentamientos y aproximaciones: la carga de una serie de trabajo sin dato se pregunta. No son series de trabajo: no cuentan en las series, el rango ni el RIR del objetivo. La app bloquea un calentamiento colocado después de una serie de trabajo.
- restSeconds: descanso configurado después de la serie, en segundos; ponlo en cada serie. Para las series de trabajo, usa days[].exercises[].restSeconds de la plantilla del mismo día y exerciseId cuando tenga un valor explícito. El valor 0 significa sin descanso. Si el campo falta o es null, aplica el descanso de 45 a 120 s que indiquen las reglas o notas de un plan anterior. Si el plan no fija descanso, usa 105–120 s en trabajo pesado o muy exigente, 75–90 s en trabajo intermedio y 60–75 s en accesorios. Usa 45–60 s en calentamientos y aproximaciones. El descanso real sigue contando hasta que la persona confirma la siguiente serie, así que no lo alargues por precaución.
- Si algo se deja a criterio, omite reps o rir en vez de inventarlos. La carga no se omite: pregúntala (ver Carga obligatoria).
- Nombre de la semana: "Semana N". Días: "Día A", "Día B"…

## IDs (la causa más común de rechazo)
- Todos los id de la semana (semana, días, ejercicios, series) deben ser nuevos y únicos dentro de TODO el programa: no repitas ninguno de context.programs ni dentro de la semana. Usa el número de semana como prefijo: semana "s5", días "s5-A", ejercicios "s5-A-sentadilla-barra", series "s5-A-sentadilla-barra-1".
- Formato: letras, números, punto, guion, guion bajo o dos puntos; sin espacios ni tildes; empieza por letra o número; máx. 128.
- exerciseId: el del plan, nunca uno nuevo para el mismo ejercicio.
- proposal.id: nuevo por propuesta (p. ej. "prop-s5"); version 1 (si rehaces la misma propuesta tras un rechazo, mismo id y version + 1).

## Formato exacto (la app rechaza campos desconocidos)
Paquete sin MCP:
{"schemaVersion":2,"type":"WeekProposal","importId":"<UUID nuevo>","createdAt":"<ISO-8601 con zona>","context":null,"results":null,"proposal":{"id","version","programId","blockId","source":"Agente externo","week":{…},"differences":[…],"reasons":[…]}}
week: {"id","name","version":1,"days":[{"id","name","exercises":[{"id","exerciseId","name","sets":[{"id","kind"?,"reps"?,"rir"?,"load":{"value","unit","basis"},"restSeconds"?}]}],"cardio"?:{"modality","durationSeconds"}}]}
- kind: "WORK" (por defecto), "WARMUP" o "APPROACH". cardio.modality: de 1 a 200 caracteres; cardio.durationSeconds: entero de 1 a 86400.
- differences: resumen de la semana (adherencia, progresos, dificultades, fatiga, molestias, cambios, y la tendencia de peso si hay medidas).
- reasons: una razón breve y verificable por ejercicio cambiado que lo nombre, con la evidencia y, si se mantiene la carga, qué falta para subirla. P. ej. "Sentadilla con barra: sube 2,5 kg (+4 %); 9/9/9 @ RIR 3/3/3 la semana pasada. Si la primera serie no llega a 6 con RIR 2, vuelve a 60 kg" o "Elevación lateral: mantiene 6 kg; sube a 7 kg cuando complete 15/15 con RIR 2". Más una para el cardio. Cada texto, de 1 a 10 000 caracteres.

Ejemplo de forma (semana 1 del plan de ejemplo; tu semana sigue el plan y los registros):
```json
{"schemaVersion":2,"type":"WeekProposal","importId":"0d3b6a2c-5e7f-4a1b-8c9d-1e2f3a4b5c6d","createdAt":"2026-11-09T12:05:00Z","context":null,"results":null,
 "proposal":{"id":"prop-s1","version":1,"programId":"ejemplo-programa","blockId":"b1","source":"Agente externo",
  "week":{"id":"s1","name":"Semana 1","version":1,"days":[{"id":"s1-A","name":"Día A","exercises":[
    {"id":"s1-A-sentadilla-barra","exerciseId":"sentadilla-barra","name":"Sentadilla con barra","sets":[
      {"id":"s1-A-sentadilla-barra-ap1","kind":"APPROACH","reps":4,"load":{"value":45,"unit":"KG","basis":"TOTAL"},"restSeconds":45},
      {"id":"s1-A-sentadilla-barra-1","reps":8,"rir":3,"load":{"value":60,"unit":"KG","basis":"TOTAL"},"restSeconds":120},
      {"id":"s1-A-sentadilla-barra-2","reps":8,"rir":3,"load":{"value":60,"unit":"KG","basis":"TOTAL"},"restSeconds":120},
      {"id":"s1-A-sentadilla-barra-3","reps":8,"rir":3,"load":{"value":60,"unit":"KG","basis":"TOTAL"},"restSeconds":120}]},
    {"id":"s1-A-elevacion-lateral","exerciseId":"elevacion-lateral","name":"Elevación lateral","sets":[
      {"id":"s1-A-elevacion-lateral-1","reps":12,"rir":2,"load":{"value":6,"unit":"KG","basis":"PER_HAND"},"restSeconds":60},
      {"id":"s1-A-elevacion-lateral-2","reps":12,"rir":2,"load":{"value":6,"unit":"KG","basis":"PER_HAND"},"restSeconds":60}]}],
    "cardio":{"modality":"Bicicleta","durationSeconds":1200}}]},
  "differences":["Semana 1 sin historial previo de estos ejercicios: cargas conservadoras para registrar el punto de partida."],
  "reasons":["Sentadilla con barra: 60 kg × 8 @ RIR 3 para calibrar sin historial.","Elevación lateral: 6 kg por mano × 12 @ RIR 2.","Cardio: bicicleta 20 min después de la fuerza, a RPE 5–6, como indica el plan."]}}
```

## Antes de entregar, comprueba
- status READY; blockId y número de semana iguales al brief.
- Todos los IDs nuevos, únicos en todo el programa y con el formato válido.
- Series por ejercicio y día según el plan y las notas de la semana, nunca más que target.sets; repeticiones dentro del rango; RIR ≥ rirMin.
- Cada subida de carga cumple la regla de progresión (umbral según el salto, y margen o segunda exposición) y su razón cita los registros. Ninguna subida en una descarga.
- En descarga, menos series en total que la última semana normal; tras una descarga, las cargas son las de la última semana normal.
- Todos los días que prescribe el plan, cada uno con su bloque de cardio cuando corresponde; un día de solo cardio va sin ejercicios.
- Cada serie de fuerza con carga y con restSeconds entre 45 y 120, o 0 si el plan fija ese valor para el trabajo; calentamientos y aproximaciones con su kind y antes de las series de trabajo.
- Misma unidad y base de carga que lo registrado; ningún ejercicio fuera del plan sin justificación.
- Ningún campo distinto de los del formato; números sin comillas.

## Salida final
- Con MCP: create_week_proposal con {"proposalId": "prop-sN", "version": 1, "programId", "blockId" (del brief), "week", "differences", "reasons"} y di qué archivo se creó.
- Sin MCP: solo un bloque de código JSON con el paquete completo, sin texto fuera del bloque.
La propuesta queda pendiente: solo la persona la aprueba en la app.
````

## Why the weekly prompt owns the progression rule

The weekly prompt decides when to raise, hold or lower a load, even if the periodization document states another rule. The plan prompt therefore does not copy the document's progression rule into `rules`. One rule in one place keeps the agent from contradicting itself, and the plan still sets ranges, RIR, deloads, safety and per-exercise exceptions.

The rule is a double progression gated by RIR, decided on 2026-11-09:

| Part of the rule | Basis |
| --- | --- |
| Reps go up first at a fixed load, and count as progress | Load and repetition progression gave similar strength and hypertrophy over 8–10 weeks ([Plotkin 2022](https://pubmed.ncbi.nlm.nih.gov/36199287/), [Chaves 2024](https://doi.org/10.1055/a-2256-5857)). |
| No training to failure; RIR stays at the plan's target | Strength gains are similar across a wide range of RIR; hypertrophy improves somewhat nearer to failure ([Robinson 2024](https://doi.org/10.1007/s40279-024-02069-2)). |
| One exposure with a margin, or two without; decide on reps and trend, not one RIR figure | People misjudge reps to failure by about one rep, regardless of training status ([Halperin 2022](https://pubmed.ncbi.nlm.nih.gov/34542869/)). |
| A sustained drop in performance during a deficit means poor recovery | An energy deficit impairs lean-mass gains but not strength gains ([Murphy and Koehler 2022](https://doi.org/10.1111/sms.14075)). |
| Scheduled deloads are kept; an early one is allowed on clear deterioration | Deload timing rests on expert consensus ([Bell 2023](https://doi.org/10.1186/s40798-023-00633-0)); a week off mid-cycle did not help hypertrophy and slightly reduced strength ([Coleman 2024](https://pubmed.ncbi.nlm.nih.gov/38274324/)). |

The rep thresholds (upper half of the range for jumps up to 5 %, top of the range above that) are a working heuristic, not a tested protocol. They follow from the rough rule that one repetition is worth about 2.5–3 % of load, so a jump should still land at or above the bottom of the range. Most of the studies are in young, healthy adults.

### Warm-up and approach sets

Decided on 2026-11-12. When the plan does not say otherwise, the weekly prompt prescribes one short, heavy approach set (about 75–80 % of the work load for 3–5 reps) on the first exercise of each movement pattern and on exercises whose load rises or that return after a break. Two sets are kept for a heavy compound that rises in load or returns after more than three weeks.

| Part of the rule | Basis |
| --- | --- |
| Few approach sets at work loads of about 10RM | No warm-up, one set and two sets gave similar repetitions, fatigue and perceived effort over four sets to failure ([Enes 2025, preprint](https://sportrxiv.org/index.php/server/preprint/view/559)). |
| One heavy, short set instead of light, long ones | A single set at 80 % of 10RM for 5 reps gave more session volume than 40 % for 15 or 60 % for 10 ([Viveiros 2024, as reviewed](https://biolayne.com/reps/issue-29/can-heavier-warm-up-sets-boost-your-training-performance/)); with heavy work, a light set alone was not enough ([Ribeiro 2020](https://ideas.repec.org/a/gam/jijerp/v17y2020i18p6882-d417011.html)). |
| An approach set when the session moves to another pattern | Repeating the specific warm-up before a second exercise for other muscles improved squat velocity, and bench press only slightly ([Neves 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC12115624/)). |
| No claim about injuries | Trials of warm-up and injury come from field sports and are inconclusive ([Fradkin 2006](https://pubmed.ncbi.nlm.nih.gov/16679062/)); a review of upper-body warm-up found no study that measured injuries ([McCrary 2015](https://pubmed.ncbi.nlm.nih.gov/25694615/)). |

These studies are small and measure one session, not long-term gains. The approach sets are kept as a check of load, range of motion and how the movement feels, not as a performance or injury-prevention tool. When the lower steps of a machine are unknown, the agent estimates the approach load by percentage and the person records the nearest available weight.

Body measurements recorded in **Progreso > Cuerpo** are included in in-app generation and local context (see [body measurements](BODY_MEASUREMENTS.md)). File/clipboard exports include them only when selected. Measurements retain method and units; muscle mass and lean mass are distinct. The in-app next-cycle prompt is `app/src/main/assets/prompts/next-cycle.txt`; only the first plan is imported manually.
