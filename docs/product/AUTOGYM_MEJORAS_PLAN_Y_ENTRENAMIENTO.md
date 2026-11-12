# AutoGym · Mejoras del plan y del registro de entrenamiento

## 1. Prescribir las 12 semanas completas

El plan de 12 semanas debe contener las pautas, las cargas de referencia, los descansos y la distribución de cardio necesarios para generar una semana entrenable. El agente semanal programa las series de aproximación y materializa el resto de la prescripción para esa semana. La revisión y la aprobación de la semana son explícitas.

### Calentamiento y cargas

- El agente de semana a semana debe crear y programar las series de aproximación que correspondan a los ejercicios y las cargas de esa semana. La propuesta semanal las incluye en orden, antes de las series de trabajo, con sus repeticiones, carga y descanso. Las aproximaciones prescritas tienen su propio tipo y se identifican como **Calentamiento** o **Serie de aproximación** en la revisión, la sesión, la notificación, la burbuja y el historial. Se registran aparte del volumen de trabajo y de las aproximaciones añadidas durante la sesión.
- El plan debe guardar una carga inicial de referencia por ejercicio de fuerza para generar la semana 1. Debe incluir valor, unidad y base de la carga, como por mano, total, carga adicional o ajuste de máquina. Tomar el valor de la periodización o de un registro previo comparable. Si falta una referencia que haga falta para prescribir, pedir ese dato antes de completar la propuesta. Los ejercicios que se hacen con peso corporal deben indicarlo expresamente.

### Carga obligatoria en cada semana generada

- El agente debe prescribir la carga de **cada serie de fuerza**, tanto de calentamiento como de trabajo, al generar o regenerar cualquier semana. La prescripción incluye el valor, la unidad (**kg** o **lb**) y la base cuando corresponda. Para una serie de peso corporal, indicar **Peso corporal** y consignar la carga adicional si la hay. Para una máquina con posiciones, indicar el ajuste prescrito.
- La app debe validar que todas las series de fuerza tengan una carga explícita antes de dar la propuesta por lista para aprobar. Si falta alguna, pedir al agente que la corrija e identificar el ejercicio y la serie. Si el agente carece de los datos para determinarla, debe preguntarle al usuario. Un valor de cero se admite cuando representa la carga prescrita.
- La propuesta muestra la carga de cada serie con su unidad y base. Al entrenar, la app la precarga como guía editable en todas las semanas.

### Elegir la unidad de peso por ejercicio

- Ofrecer un selector **kg/lb** por ejercicio. Si la unidad precargada es incorrecta, el usuario puede cambiarla y editar el valor para registrar lo que realmente usó. La base de la carga, como **por mano** o **total**, es independiente de la unidad elegida. Los ajustes de máquina y los ejercicios de peso corporal mantienen su representación correspondiente.
- Al guardar una serie con la unidad corregida, registrarla como la unidad de ese ejercicio. El agente debe usarla al generar o regenerar sus próximas prescripciones. El contexto de generación debe incluir la unidad corregida y los valores registrados con ella. Los registros previos conservan su valor y unidad originales.

**Aceptación.** Si el peso viene precargado en kg y el usuario registra la serie en lb, la siguiente propuesta que incluya ese ejercicio prescribe sus cargas en lb. Otro ejercicio conserva su propia unidad.

### Descansos definidos en el plan

Guardar en el plan un valor concreto en segundos por serie, ejercicio o grupo de ejercicios, con las excepciones de cada semana. La propuesta semanal resuelve esas reglas en descansos concretos que la app usa durante la sesión. Elegir el tiempo según la exigencia de la serie y el objetivo del bloque. Los **45 s y 120 s** son los límites inclusivos de la duración configurada para cada descanso. Reservar **120 s** para el trabajo más exigente. El descanso real se registra hasta que el usuario inicie el siguiente paso, incluida la espera después de que venza el contador.

Durante el descanso, ofrecer **−15 s**, **+15 s** y **Saltar descanso**. Los botones de ajuste cambian la duración de ese descanso hasta el límite correspondiente y actualizan al instante el contador que comparten las superficies. **Saltar descanso** permite iniciar antes de que llegue a cero. El historial guarda el tiempo real transcurrido también cuando se ajusta o se salta el descanso.

Como punto de partida para asignar valores concretos en el plan, se proponen estos intervalos. La exigencia depende de la carga y el esfuerzo de la serie, además del tipo de ejercicio.

| Tipo de serie | Intervalo propuesto |
| --- | --- |
| Trabajo pesado o series de alta exigencia | 105–120 s |
| Trabajo de exigencia intermedia | 75–90 s |
| Accesorios o trabajo de baja exigencia | 60–75 s |
| Aproximaciones ligeras | 45–60 s |

El metaanálisis de Singer y colaboradores encontró un posible beneficio pequeño para la hipertrofia con descansos de más de 60 s y no detectó diferencias apreciables más allá de 90 s. En un ensayo con cargas cercanas al máximo, el press de banca necesitó más recuperación para sostener el rendimiento que la flexión de codo. Los intervalos de la tabla son una propuesta para este plan dentro de los límites pedidos. [Singer et al., 2024](https://pubmed.ncbi.nlm.nih.gov/39205815/) · [Senna et al., 2016](https://pubmed.ncbi.nlm.nih.gov/26907842/)

### Cardio ejecutable y día D

| Día | Contenido de cada semana |
| --- | --- |
| A, B y C | Fuerza según el plan y un ejercicio de cardio de 30 min por día |
| D | Una sesión de solo cardio de 60 min |

- Incluir A, B, C y D en cada propuesta del ciclo, también en semanas de descarga. Si falta un día o su cardio obligatorio, la validación debe pedir una corrección antes de permitir aprobar la propuesta. Aplicar la regla al plan que prescribe esta distribución, de modo que otros programas puedan tener otra estructura.
- El cardio debe aparecer en el orden de la sesión como un ejercicio ejecutable por duración. Desde **Hoy** y **Plan** se puede iniciar el día D aunque no tenga series de fuerza. El usuario puede iniciar, pausar y terminar el cardio, ver el tiempo restante y registrar la duración real. Un día D con cardio registrado cuenta en el historial y en la adherencia semanal.
- Durante el cardio, mostrar en el **Samsung Galaxy Watch6 Classic** la frecuencia cardíaca disponible y la referencia **121–140 lpm**, con una indicación de si la lectura está dentro del rango. Mostrar **Sin lectura** cuando el sensor no entrega un dato vigente. El objetivo de esta pantalla es que el usuario pueda consultar el pulso durante el ejercicio.

**Aceptación.** La semana 1 se genera a partir de las cargas iniciales. En todas las semanas, una propuesta con una serie de fuerza sin carga requiere corrección antes de quedar lista para aprobar, y las cargas válidas se ven y se precargan al entrenar. Las aproximaciones se identifican como tales. A, B y C incluyen 30 min cada uno y D incluye 60 min de solo cardio. El descanso programado se conserva al pasar del plan a la propuesta y de la propuesta al temporizador.

## 2. Generar y revisar una semana

### Regeneración con feedback

- Ofrecer **Regenerar con feedback** para una propuesta pendiente y para una semana ya aprobada, incluso si ya tiene entrenamientos registrados. El agente recibe el feedback, la versión anterior, el plan activo y el contexto de la misma semana y bloque. La nueva versión conserva el número de semana y muestra qué cambió antes de aprobar.
- Para una propuesta pendiente, una regeneración exitosa deja una única versión vigente para aprobar. Si la solicitud se cancela o falla, la versión previa sigue disponible.
- Para una semana aprobada, aprobar la nueva versión reemplaza la planificación de esa semana y borra sus registros de entrenamiento, incluidos fuerza, cardio, RIR y tiempos. La revisión debe indicar qué semana se reemplazará y avisar del borrado. El reemplazo y el borrado se aplican juntos al aprobar. Durante la generación y la revisión, la semana anterior y sus registros siguen disponibles. Un fallo o cancelación conserva esos datos y las demás semanas siguen disponibles con sus propios registros.

### Indicador mientras Claude piensa

- Mientras se envía la solicitud, mostrar **Enviando solicitud**. Cuando el servicio confirme que empezó a procesarla mediante el flujo de eventos, mostrar **Claude está pensando…** durante la espera larga. Si Claude entrega resúmenes intermedios sobre lo que está trabajando, mostrarlos junto al indicador. Si el proveedor solo confirma la actividad, mantener el mensaje con un indicador de carga. Al terminar, mostrar la propuesta o las preguntas de Claude. [Streaming de Claude](https://platform.claude.com/docs/en/build-with-claude/streaming) · [Resúmenes de pensamiento de Claude](https://platform.claude.com/docs/en/build-with-claude/thinking)
- Una cancelación o un error debe salir del estado de carga, explicar qué pasó y ofrecer un reintento cuando corresponda, conservando el feedback escrito. Mientras la solicitud está en curso, una segunda pulsación no inicia otra generación.

### Ejercicios nuevos y advertencias accionables

- El agente puede proponer ejercicios nuevos y entregar los datos necesarios para registrarlos en el catálogo. Reutilizar el ID cuando la variante ya exista y asignar un ID estable para una variante nueva. Si el ejercicio nuevo es válido y pertenece a la propuesta, mostrarlo como **Ejercicio nuevo** en la revisión y registrarlo al aprobar, una sola vez.
- Cuando quede un problema real, explicar qué ejercicio o día está afectado, el motivo y cómo resolverlo. Una sustitución fuera del plan, una variante ambigua o una referencia inválida sigue requiriendo la validación correspondiente. Si el agente puede corregir el problema con la información disponible, hacerlo durante la generación.

**Aceptación.** Regenerar una propuesta cambia su versión y conserva su número de semana. Aprobar el reemplazo de una semana con entrenamientos registrados instala la nueva planificación y elimina los registros de esa semana. Si se cancela o falla antes de aplicar el reemplazo, siguen disponibles la planificación y los registros anteriores. Durante una respuesta lenta, el indicador **Claude está pensando…** permanece visible después de la confirmación del servicio. Un ejercicio nuevo válido queda en el catálogo tras aprobar una vez, y una referencia inválida indica qué hay que corregir.

## 3. Guiar la sesión desde la app, la notificación y el reloj

### Notificación de la barra y panel flotante

- **Notificación inline** significa la notificación persistente de AutoGym que aparece al bajar la barra de notificaciones de Android. Actualizar el estilo de sus vistas compacta y expandida siguiendo el sistema visual de AutoGym. Dar prioridad al estado, el temporizador y la acción principal, con contraste y controles legibles en los temas claro y oscuro.
- Mostrar los **segundos restantes reales** en la cabecera de descanso, tanto en la vista compacta como en la expandida. El contador debe avanzar sin interacción, coincidir con la sesión y recuperarse correctamente al volver a abrir la barra o tras pausar y reanudar. Aplicar el mismo criterio a la cuenta regresiva del cardio cuando se muestre allí. Evaluar el cronómetro nativo de `RemoteViews`, que el checkout ya usa en la vista expandida de descanso. [Referencia de RemoteViews](https://developer.android.com/reference/android/widget/RemoteViews)
- Durante el descanso, mostrar la **siguiente serie que se va a ejecutar**, aunque sea del mismo ejercicio. Si cambia el ejercicio, mostrar el nombre del siguiente, peso con unidad y base, y repeticiones previstas. Si sigue un cardio, mostrar modalidad y duración. Usar los mismos valores que verá el usuario al iniciar y actualizar la vista si se modifica el orden. Debe poder consultarse en la pantalla de sesión, la burbuja y la notificación expandida.
- En el panel flotante, ubicar **Minimizar** y **Cerrar (×)** en esquinas opuestas de la cabecera. Ambas acciones deben distinguirse y poder pulsarse sin interferir con el arrastre del panel.

### Aviso háptico y confirmación de inicio

- El único disparador de la vibración es que el contador de descanso llegue a cero. En ese momento, mostrar **OK** para iniciar la siguiente serie o ejercicio y mantener los pulsos mientras se espera la confirmación. El descanso sigue acumulando tiempo real durante la espera.
- Al pulsar **OK** desde la burbuja, la notificación o el reloj, finalizar y guardar el descanso con su duración real, apagar la vibración e iniciar el siguiente paso. La acción es la misma en las tres superficies. Si el dispositivo tiene la vibración desactivada, el aviso y el botón permanecen visibles. [Referencia de Vibrator](https://developer.android.com/reference/android/os/Vibrator)
- En el **Samsung Galaxy Watch6 Classic**, reproducir el aviso háptico, mostrar qué toca y permitir confirmar desde el reloj. La confirmación actualiza teléfono y reloj, detiene los dos avisos y solo puede iniciar una vez la misma serie. Una confirmación atrasada o duplicada no puede avanzar otra serie. Si se pierde la conexión, el teléfono sigue siendo utilizable y el reloj debe mostrar que la acción no se confirmó. Evaluar Wear OS Data Layer para coordinar estado y acciones, y Health Services para consultar frecuencia cardíaca durante el cardio. [Samsung Galaxy Watch6 Classic](https://news.samsung.com/global/samsung-galaxy-watch6-and-galaxy-watch6-classic-inspiring-your-best-self-day-and-night) · [Wear OS Data Layer](https://developer.android.com/training/wearables/data/client-types) · [Health Services para sesiones activas](https://developer.android.com/health-and-fitness/health-services/active-data)

### RIR preseleccionado

- Al iniciar la primera serie de trabajo de un ejercicio, usar el RIR registrado en la primera serie de trabajo de ese ejercicio en la semana inmediatamente anterior si coinciden la carga y las repeticiones. Comparar con la carga y las repeticiones que se registraron realmente. Si falta esa referencia, cambió la carga o cambiaron las repeticiones, dejar el RIR de registro en blanco. Esto incluye la primera semana del ciclo cuando carece de una referencia anterior.
- En las siguientes series de trabajo del mismo ejercicio, precargar el RIR guardado en la serie de trabajo anterior de hoy. Si el usuario guarda **RIR 2** en la primera y **RIR 1** en la segunda, la segunda arranca con **2** y la tercera con **1**. Las series de aproximación se tratan aparte y quedan excluidas de ambas reglas de preselección. Al pasar a otro ejercicio, volver a aplicar la regla de la primera serie de trabajo. El valor precargado se puede editar antes de guardar y el objetivo del plan permanece visible como referencia.

**Aceptación.** Con otra app abierta, el usuario baja la barra y ve el contador avanzar segundo a segundo. Puede preparar la siguiente máquina con el peso y las repeticiones visibles y ajustar el descanso con **−15 s** o **+15 s**. Al llegar a cero, teléfono y reloj avisan y el descanso sigue acumulando tiempo. Pulsar **OK** desde cualquiera de las superficies guarda el descanso real, inicia exactamente una serie y apaga los avisos. Una aproximación con RIR registrado deja intacta la preselección de las series de trabajo. Probar la barra en un Samsung con One UI y las acciones, vibración y pulso en el **Galaxy Watch6 Classic** físico.

## 4. Medir la duración y mostrar el cierre

- Guardar y mostrar la secuencia real de la sesión, con la duración de cada serie, tramo de cardio, descanso y pausa. Si hay una pausa dentro de una serie, el desglose muestra el trabajo antes y después de la pausa y el tiempo que duró la pausa. Todo el tiempo transcurrido forma parte del total de la sesión. Una serie omitida o un registro anterior sin medición aparece sin duración registrada.
- Medir el descanso desde que comienza hasta el inicio real de la siguiente serie o ejercicio. La espera tras llegar a cero forma parte de ese descanso. Si se programaron 75 s y el usuario pulsa **OK** 20 s después del aviso, guardar **95 s de descanso real**. Aplicar el mismo principio a las pausas y al cardio, que se cuentan una sola vez.
- Mostrar el desglose por ejercicio y día con el tiempo de actividad, descanso y pausa, y el total real que los incluye. En el resumen semanal, mostrar el promedio de duración real de las sesiones con tiempos disponibles y cuántas sesiones entran en el cálculo.

### Resumen de entrenamiento completado

- Actualizar el resumen que aparece al completar el entrenamiento. Agrupar por ejercicio y, para cada serie, comparar **Peso objetivo**, **Peso real**, **RIR objetivo** y **RIR real**. Mostrar la unidad de cada peso. Tomar los objetivos de la prescripción con la que se inició la sesión y los valores reales del registro guardado. Para series añadidas, omitidas o con un dato ausente, indicar el estado o el dato que falta.
- Incluir en ese mismo resumen el tiempo total y el desglose real por ejercicio, con la duración de cada serie, tramo de cardio, descanso y pausa. Mostrar duraciones transcurridas en minutos y segundos, con horas cuando corresponda. El descanso incluye toda la espera hasta pulsar **OK**.
- En la pantalla de la sesión completada, cambiar el botón **Cerrar sesión y volver al demo** por **Finalizar entrenamiento**. Al pulsarlo, cerrar la sesión y regresar directamente a **Hoy**, la pantalla de inicio de AutoGym. El entrenamiento finalizado debe quedar disponible en el historial.
- En modo burbuja, mantener el panel abierto, expandirlo si estaba minimizado y mostrar una animación breve de éxito, un mensaje de felicitación y el mismo resumen. **Cerrar** descarta el panel tras consultar el resultado. Guardar el resultado antes de mostrar el éxito. Un cierre anticipado muestra el resultado parcial con su estado correspondiente.

**Aceptación.** Una serie prescrita con 40 kg y RIR 2 que se registra con 37,5 kg y RIR 1 muestra los cuatro valores con sus etiquetas. Con una serie de 30 s, un descanso programado de 75 s, 20 s de espera tras el aviso y otra serie de 25 s, el resumen muestra **Serie 30 s → Descanso 95 s → Serie 25 s**, con un total de **2 min 30 s**. **Finalizar entrenamiento** lleva a **Hoy** y el entrenamiento se puede consultar en el historial. La burbuja sigue visible hasta pulsar **Cerrar**. El historial y los cálculos semanales conservan los tiempos después de volver a abrir la app.

## 5. Registrar comentarios para el agente semanal

- Permitir guardar comentarios durante el entrenamiento y desde el resumen de cierre. Un comentario puede ser general de la sesión o estar asociado a un ejercicio o serie. Conservar el texto, la fecha y su relación con el programa, la semana y la sesión para que pueda consultarse en el historial. Reutilizar las notas de serie y cardio que la app ya permite guardar.
- Al generar o regenerar una semana, incluir en el contexto del agente los comentarios guardados que correspondan al programa y a sus ejercicios. Las sugerencias que sigan aplicando deben estar disponibles en semanas posteriores sin tener que volver a escribirlas. El agente usa el comentario para ajustar la propuesta cuando corresponda y explica qué ajustó a partir de él en la revisión.

**Aceptación.** Si el usuario registra «En este remo prefiero el agarre neutro», el comentario queda asociado al ejercicio. La siguiente generación lo recibe con ese contexto y, si cambia el agarre prescrito, la revisión indica que el cambio responde al comentario. El comentario sigue disponible para futuras generaciones que incluyan ese ejercicio.

Los puntos de partida del repositorio son [planes de periodización](../PLAN_OUTLINE.md), [generación en la app](../IN_APP_AGENT.md), [revisión de propuestas](../PROPOSAL_REVIEW.md), [catálogo](../EXERCISE_CATALOG.md) y [registro de sesiones](../PLANNED_RECORDING.md). Al incorporar nuevos datos, actualizar los contratos, validadores y flujos de intercambio que los usan, incluidos JSON y MCP, y comprobar que se sigan leyendo los planes y registros anteriores.
