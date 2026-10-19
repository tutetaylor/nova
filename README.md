# Nova — tu asistente de voz personal

Dos asistentes que comparten personalidad y cerebro: **Gemini, con la capa gratuita de Google. Todo el proyecto es 100% gratis.**

| | **Nova para PC** (`pc/`) | **Nova para Android** (`android/`) |
|---|---|---|
| Lenguaje | Python | Kotlin (Android Studio) |
| Activación | Decís "Nova" (offline, Vosk) | Decís "Nova", botón de accesibilidad o "Hablar" en la notificación |
| Voz | Neural es-AR (Elena / Tomás) | Voz de Google del celular |
| Control | Programas, navegador, teclado, volumen, música, capturas, apagar/bloquear | **El celular entero**: lee la pantalla de cualquier app y toca, escribe y desliza |
| Extras | Recordatorios por voz, notas, clima | WhatsApp, SMS, llamadas, alarmas, temporizadores, calendario, linterna, Maps, Spotify, ajustes, batería |

Funcionan separados: el de Android anda en cualquier lugar, sin la PC.

---

## 1. Conseguí tu clave gratis de Gemini

1. Entrá a https://aistudio.google.com/apikey con tu cuenta de Google.
2. Tocá **Create API key**. No pide tarjeta.
3. Guardá la clave (empieza con `AIza...`). No la subas a GitHub.

**Cómo se mantiene gratis:**
- No actives la facturación (*billing*) en ese proyecto de Google. Sin facturación, Google nunca te cobra:
  cuando se termina la cuota gratis, simplemente responde "sin cuota" hasta que se renueva.
- Si el modelo principal se queda sin cuota, Nova prueba solo con otros modelos gratis (`MODELOS_RESPALDO`).
- El **límite por hora** (`LIMITE_POR_HORA`) viene en 0 = sin límite propio (solo corre el de la capa gratis de Google). Si querés repartir la cuota, poné un número.
  Preguntale *"¿cuánto uso me queda?"* y te contesta sin gastar nada.
- La cuota exacta de cada modelo la ves en https://aistudio.google.com/rate-limit.
- En la capa gratis, Google puede usar lo que le mandás para mejorar sus productos. No le dictes datos sensibles.

---

## 0. La forma fácil: GitHub compila todo por vos

Subí esta carpeta a un repo de GitHub (privado). Con cada cambio, **Actions** arma solo:

- **`Nova.exe`** para Windows → en Releases, "Nova para PC". Doble clic y listo, sin instalar Python.
  Se abre una ventana con la estrella. La primera vez se abren los ajustes para que pegues la clave gratis de Gemini; al encenderla baja sola la voz offline. Tu configuración,
  notas y recordatorios quedan en `C:\Users\<vos>\Nova\`.
- **`Nova.apk`** para Android → en Releases, "Nova - última versión". Abrilo desde el celular e instalalo.

Si Windows muestra "Windows protegió su PC": **Más información → Ejecutar de todas formas** (pasa con
cualquier programa propio sin firma digital). Para que arranque con Windows: `Win+R` → `shell:startup`
→ pegá ahí un acceso directo a `Nova.exe`. 

Si preferís correrlo desde el código, seguí con las secciones de abajo.

---

## 2. Nova para PC (desde el código)

Requisitos: Python 3.10+ y micrófono.

```bash
cd pc
python -m venv .venv
.venv\Scripts\activate          # Windows   (Mac/Linux: source .venv/bin/activate)
pip install -r requirements.txt
python instalar_modelo.py       # baja el modelo offline para escuchar "Nova" (~40 MB)
copy .env.example .env          # Mac/Linux: cp .env.example .env
```

Abrí `.env`, pegá tu clave y personalizá nombre, voz y ciudad. Después:

```bash
python main.py            # con ventana: la estrella, la charla y los ajustes
python main.py --consola  # solo voz, en la terminal
python main.py --texto    # escribiendo en la terminal, sin micrófono
```

Probá: *"Nova, ¿qué clima hay mañana?"*, *"abrí Spotify"*, *"poné música"*, *"subí el volumen"*,
*"recordame en 20 minutos sacar la ropa"*, *"anotá que tengo que llamar al dentista"*, *"buscá en YouTube recetas de pizza"*.

**Que arranque solo con Windows:** `Win+R` → `shell:startup` → creá un acceso directo a
`.venv\Scripts\pythonw.exe main.py` con "Iniciar en" = la carpeta `pc`.

**Agregar habilidades:** en `herramientas.py`, escribí una función con `@herramienta(...)`. El cerebro la descubre sola.

---

## 3. Nova para Android

Requisitos: Android 8 o superior, app de Google instalada (reconocimiento de voz) y Android Studio en la PC.

### Instalar
1. Abrí la carpeta `android/` en Android Studio (**File → Open**). Esperá que termine el *Gradle sync*.
2. En el celular: **Ajustes → Acerca del teléfono → tocá 7 veces "Número de compilación"**, después
   **Opciones de desarrollador → Depuración USB**.
3. Conectá el celular por USB y apretá ▶ **Run**. La app queda instalada.

### Configurar (en la app Nova)
1. Pegá la clave, elegí el nombre y tu ciudad → **Guardar**.
2. **Dar permisos** → aceptá todo.
3. **Activar control de pantalla** → buscá **Nova** en Accesibilidad → activalo.
   Si Android 13+ dice "configuración restringida": Ajustes → Apps → Nova → ⋮ → **Permitir configuración restringida**, y volvé a intentar.
4. **Sacar optimización de batería** → Nova → *Sin restricciones*. Sin esto Android lo duerme.
5. **Iniciar Nova**. Queda una notificación fija: está escuchando.

### Usarlo
- Decí **"Nova"**, esperá el bip (es la señal de "te escucho") y hacé tu pedido.
- La primera vez que lo iniciás baja la voz offline (~40 MB, solo una vez). Desde ahí, esperar
  a que digas "Nova" funciona sin internet, **sin bip y sin pausar tu música ni tus audios**.
- Los pedidos comunes (hora, alarmas, temporizadores, linterna, volumen, música, abrir apps)
  se resuelven al instante en el celular, sin internet y sin gastar cuota.
- Sin hablar en voz alta: botón **Hablar** de la notificación o el **botón de accesibilidad**.
- Si te responde con una pregunta, contestá directo sin repetir "Nova".
- *"Gracias"* o *"chau"* corta la charla; *"olvidá todo"* empieza una conversación nueva.

Ejemplos: *"mandale un WhatsApp a mamá diciendo que llego en 10"*, *"llamá a Juan"*,
*"abrí Instagram y mostrame los mensajes"*, *"poné un temporizador de 15 minutos"*,
*"prendé la linterna"*, *"¿cuánta batería tengo?"*, *"llevame a Plaza de Mayo caminando"*,
*"poné Duki en Spotify"*, *"leeme las notificaciones"*, *"activá el modo avión"*.

Para lo que no tiene comando directo, Nova maneja la pantalla como una persona: abre la app, lee,
toca, escribe y repite hasta terminar.

### Seguridad incorporada
- Antes de **enviar, llamar, publicar, borrar o pagar** te dice qué va a hacer y espera tu "sí".
- Nunca escribe contraseñas, códigos ni datos de tarjeta.
- Ignora instrucciones que aparezcan escritas en pantalla o en mensajes: solo te obedece a vos.
- La clave queda guardada solo en tu celular.

---

<!-- FUNCIONES -->
## Todo lo que puede hacer

74 funciones. Las frases son ejemplos: decilo con tus palabras. En la app están en «Qué puedo hacer» (el botón **?** arriba) y tocando un ejemplo lo probás.

### Lo básico
*Cómo hablarle y cómo responde.*

- **Palabra de activación** (Celular y PC): Decí su nombre y te escucha. Funciona sin internet y solo se activa si la frase es exacta, así no se despierta sola. También podés tocar la estrella o escribirle. Ej.: *«Nova»* · *«Oye Nova»* · *«Hola Nova»*
- **Comandos instantáneos** (Celular y PC): Las cosas simples las hace al toque, sin gastar consultas de Gemini: hora, fecha, volumen, música, captura, bloquear, abrir apps, alarmas y temporizadores. Ej.: *«¿Qué hora es?»* · *«Subí el volumen»* · *«Siguiente canción»* · *«Abrí Spotify»* · *«Alarma a las 7 y media»* · *«Temporizador de 10 minutos»*
- **Seguir la conversación** (Celular y PC): Después de responder sigue escuchando unos segundos por si querés agregar algo, sin repetir su nombre. Para cortar: «nada», «gracias», «listo». Ej.: *«Gracias, eso es todo»*
- **Empezar de cero** (Celular y PC): Olvida la conversación actual (no la memoria permanente). Ej.: *«Olvidá todo»* · *«Empecemos de nuevo»*
- **Respuestas al instante** (Celular y PC): Empieza a hablar con la primera frase mientras Gemini todavía escribe el resto, y prepara el audio de la frase siguiente mientras dice la actual. Las frases cortas quedan guardadas y suenan al toque.
- **Límite por hora** (Celular y PC): Por defecto no tiene límite propio (solo el de la capa gratis de Google). Si querés cuidar la cuota, ponés un número en Ajustes. Ej.: *«¿Cuánto uso me queda?»*

### Manejar el celular
*Hace en el celular lo mismo que harías vos con el dedo.*

- **Cualquier app, con la pantalla** (Celular): Lee lo que hay en pantalla, toca, escribe y desliza en cualquier app: WhatsApp, Instagram, Spotify, Mercado Libre, el banco para consultar, etc. Necesita el paso «Control de pantalla». Ej.: *«Abrí Instagram y dale like a la última foto de Juli»* · *«Poné en Spotify mi playlist de entrenar»* · *«Buscá en Mercado Libre zapatillas Nike talle 42»*
- **Abrir apps** (Celular y PC): Abre apps del celular o programas de la PC por su nombre. Ej.: *«Abrí WhatsApp»* · *«Abrí Visual Studio Code»*
- **Botones del sistema** (Celular): Atrás, inicio, recientes, notificaciones, ajustes rápidos, captura y bloquear. Ej.: *«Volvé atrás»* · *«Mostrame las notificaciones»* · *«Bloqueá el celular»*
- **Prender la pantalla** (Celular): La enciende y pide quitar el bloqueo. Si Extend Unlock lo tiene desbloqueado queda abierto; si no, te pide huella o PIN. Nunca saltea tu bloqueo. Ej.: *«Prendé la pantalla»*
- **Ajustes del celular** (Celular): Linterna, volumen, brillo, no molestar, y abre los paneles de wifi, bluetooth, datos, ubicación, sonido o batería. Ej.: *«Prendé la linterna»* · *«Brillo al 30»* · *«Activá no molestar»* · *«Abrí el wifi»*
- **Batería** (Celular): Te dice cuánta batería tenés y si está cargando. Ej.: *«¿Cuánta batería tengo?»*

### Manejar la PC
*Programas, teclado, ventanas y energía.*

- **Programas, carpetas y webs** (PC): Abre programas instalados, carpetas (Descargas, Documentos…) y páginas o búsquedas en el navegador. Ej.: *«Abrí la carpeta de descargas»* · *«Buscá en YouTube recetas de risotto»* · *«Abrí Gmail»*
- **Escribir y atajos** (PC): Escribe donde esté el cursor y aprieta teclas o combinaciones. Ej.: *«Escribí «nos vemos a las 8»»* · *«Apretá control zeta»* · *«Mostrame el escritorio»*
- **Energía** (PC): Bloquea, suspende, reinicia o apaga la PC (para reiniciar o apagar te pregunta, salvo en modo libre). Ej.: *«Bloqueá la compu»* · *«Apagá la PC en un rato»*
- **Captura** (Celular y PC): Saca una captura de pantalla y la guarda. Ej.: *«Sacá una captura»*
- **Notas rápidas** (PC): Anota ideas en la libreta de la PC y te las lee. Ej.: *«Anotá: idea para el portfolio, sección de testimonios»* · *«Leeme mis notas»*

### Llamadas y mensajes
*Hablar con la gente sin tocar el celular.*

- **Llamar** (Celular): Llama a un contacto o número. Te confirma antes, salvo en modo libre. Ej.: *«Llamá a mamá»* · *«Llamá al 11 2345 6789»*
- **WhatsApp y SMS** (Celular): Manda un WhatsApp o SMS a un contacto o número; WhatsApp lo envía solo tocando Enviar. Ej.: *«Mandale a Lucas por WhatsApp que llego en 10»* · *«Mandale un SMS a papá que ya salí»*
- **Leerte los mensajes** (Celular): Te avisa en voz alta los mensajes que llegan (se puede apagar en Ajustes) y te resume lo que te perdiste, por chat. Necesita el paso «Leer tus mensajes». Ej.: *«¿Qué me perdí?»* · *«Leeme los mensajes de WhatsApp»*
- **Responder sin abrir la app** (Celular): Responde desde la notificación, como el botón «Responder». Ej.: *«Respondele que sí, que mañana voy»* · *«Respondele a Sofi que ahora la llamo»*
- **Desde la PC** (PC): Desde la PC le pedís al celular que mande mensajes o llame. Ej.: *«Mandale un WhatsApp a Juan desde el celular»*

### Envíos masivos
*Mandar tu CV o un mensaje a muchos números a la vez.*

- **Leer números de capturas** (Celular): Lee las últimas capturas (o fotos) con visión y saca los teléfonos, con el nombre del lugar o la persona si aparece. Los pasa al formato de WhatsApp (saca el 0 y el 15, agrega el 549) y aparta los incompletos. Ej.: *«Escaneá los números de las últimas 10 capturas y mandales mi CV con el mensaje predeterminado»*
- **Siempre te pregunta antes** (Celular): Te dice cuántos encontró y de quiénes, y espera tu sí, aunque estés en modo libre. Podés sacar a alguien de la lista. Ej.: *«Sacá el tercero»* · *«Sacá al de Don Julio»* · *«Dale, mandalo»*
- **Envío de a uno, con pausas** (Celular): Manda mensaje y documento a cada uno, con 10 a 15 segundos entre contactos y hasta 40 por tanda, para cuidar tu cuenta. El celular tiene que estar desbloqueado. Te avisa al terminar quién no tiene WhatsApp. Ej.: *«¿Cómo va el envío?»* · *«Pará el envío»*
- **No repite** (Celular): Recuerda a quién le mandó cada documento y lo saltea la próxima vez, salvo que le digas lo contrario. Ej.: *«Mandalo aunque ya se lo haya mandado»*
- **Documentos guardados** (Celular): Elegís un archivo una sola vez (tu CV, un PDF) y queda guardado para mandarlo por voz. Ej.: *«Guardá mi CV»* · *«¿Qué documentos tenés guardados?»*
- **Mensajes guardados (plantillas)** (Celular): Guardás textos para reusar. El «predeterminado» se usa si no decís otro. {nombre} se cambia por el nombre del lugar. Ej.: *«Guardá como mensaje predeterminado: Hola {nombre}, soy Mateo, cocinero…»* · *«¿Qué mensajes tenés guardados?»*

### Imágenes y videos
*Crea imágenes y videos cortos con IA, a partir de lo que le describas.*

- **Generar imágenes** (Celular y PC): Hace la imagen que le pidas (fotos, ilustraciones, logos, fondos de pantalla), la abre y la guarda: en el celular en la galería (Imágenes › Nova), en la PC en Imágenes/Nova y además se ve en el chat. Tarda unos segundos. Ej.: *«Haceme una imagen de un gato astronauta estilo Pixar»* · *«Diseñame un logo minimalista para Codexa Studio»* · *«Un fondo de pantalla vertical de Buenos Aires de noche»*
- **Generar videos** (Celular y PC): Videos cortos de 4 a 8 segundos. Se hacen en segundo plano (uno o dos minutos) y te avisa cuando están. Necesitan la clave de Pollinations. Ej.: *«Haceme un video de olas rompiendo al atardecer, toma con drone»* · *«Un video vertical de 8 segundos de una pizza saliendo del horno»*
- **Mandar o compartir lo generado** (Celular): Manda la última imagen o video por WhatsApp a quien digas, o abre el menú de compartir (Instagram, Drive…). Ej.: *«Mandásela a Juli»* · *«Compartila en Instagram»*
- **Clave de Pollinations** (Celular y PC): Las imágenes y videos los hace Pollinations. Con tu clave (enter.pollinations.ai, entrás con GitHub y trae crédito de regalo) anda siempre; sin clave, las imágenes se intentan igual y los videos no. Se pega en Ajustes › Cerebro gratis.

### Ojos
*Ve la pantalla y lo que le mostrás con la cámara.*

- **Mirar la pantalla** (Celular y PC): Saca una captura y la entiende: describe fotos o videos, lee textos, explica errores, ubica botones sin texto. Ej.: *«¿Qué dice esto?»* · *«¿Qué error es este?»* · *«Resumime esta página»*
- **Mirar con la cámara** (Celular): Abre la cámara, saca una foto y te dice qué ve: objetos, carteles, menús, etiquetas, y traduce. Ej.: *«¿Qué es esto?»* · *«Leeme este cartel»* · *«Traducime este menú»*

### Memoria y datos
*Se acuerda de vos y lleva tus cosas.*

- **Memoria permanente** (Celular y PC): Se acuerda para siempre de lo que le contás y lo usa cuando viene al caso. Ej.: *«Acordate que soy alérgico al maní»* · *«¿Qué sabés de mí?»* · *«Olvidate de lo del maní»*
- **Listas** (Celular y PC): Compras, pendientes, pelis, regalos… se crean solas. Ej.: *«Agregá leche y huevos a la lista de compras»* · *«¿Qué me falta comprar?»* · *«Ya compré la leche»* · *«Borrá la lista de pelis»*
- **Gastos** (Celular y PC): Anota gastos por categoría y te dice cuánto gastaste. «Lucas» = miles. Ej.: *«Gasté 5 lucas en el súper»* · *«¿Cuánto gasté este mes?»* · *«¿Cuánto gasté en salidas esta semana?»* · *«Borrá el último gasto»*

### Agenda y avisos
*Tu día organizado.*

- **Agenda de Google** (Celular): Lee y crea eventos en el calendario del celular (el de tu cuenta de Google). Necesita el paso «Agenda». Ej.: *«¿Qué tengo hoy?»* · *«¿Qué tengo mañana?»* · *«Agendá dentista el jueves a las 10»*
- **Recordatorios** (Celular y PC): Te avisa por voz a la hora indicada, una vez o repetido (diario, semanal, de lunes a viernes). Ej.: *«Recordame en 20 minutos sacar la ropa»* · *«Avisame todos los días a las 9 que tome agua»* · *«¿Qué recordatorios tengo?»*
- **Acciones programadas** (Celular y PC): No solo avisa: a esa hora HACE algo. Ej.: *«Todos los días a las 23 activá no molestar y bajá el brillo»*
- **Rutina de buen día** (Celular y PC): Te saluda y te cuenta el clima, tus recordatorios y pendientes del día. Por voz o automática todos los días. Ej.: *«Buen día»* · *«Haceme la rutina de buen día todos los días a las 8»*
- **Alarmas y temporizadores** (Celular): Pone alarmas y temporizadores en el reloj del celular. Ej.: *«Despertame a las 7»* · *«Temporizador de 15 minutos»*

### Automatizaciones
*«Cuando pase X, hacé Y», creadas hablando.*

- **Rutinas por frase** (Celular y PC): Una frase dispara varias acciones. Ej.: *«Cuando diga «modo dormir», activá no molestar, poné alarma a las 7 y bajá el brillo»* · *«¿Qué rutinas tengo?»*
- **Al conectar algo** (Celular): Auriculares, Bluetooth del auto o un parlante, cargador o al desconectarlo. Necesita el paso «Bluetooth» para el auto. Ej.: *«Cuando conecte los auriculares, poné mi playlist»* · *«Cuando me suba al auto, activá el modo manejo»*
- **Batería baja** (Celular): Hace algo cuando la batería baja de un porcentaje. Ej.: *«Cuando la batería baje del 15, activá el ahorro de energía»*
- **Al llegar o salir de un lugar** (Celular): Guardás un lugar estando ahí y Nova reacciona al llegar o irte. Necesita el paso «Ubicación todo el tiempo». Ej.: *«Guardá este lugar como casa»* · *«Cuando salga de casa, recordame las llaves»*
- **Cuando te escribe alguien** (Celular): Reacciona a mensajes de una persona. Ej.: *«Cuando me escriba mamá, avisame aunque esté en no molestar»*

### Personalidad y charla
*Cómo es y de qué habla.*

- **Modo charla** (Celular y PC): Conversación continua de ida y vuelta, sin decir su nombre cada vez. Termina si decís «basta» o te quedás callado un rato. Ej.: *«Charlemos»* · *«Hablemos un rato»* · *«Terminamos»*
- **Conversación sin límites** (Celular y PC): Habla de lo que sea (sexo, drogas, política, religión, salud, plata, humor negro, temas oscuros, explícitos o polémicos, ficción y juegos de rol), de frente, sin sermones ni advertencias, y da su opinión. Si le pedís algo largo, se extiende sin cortarse. Los filtros de Gemini están al mínimo; lo poco que Google bloquea siempre, te lo avisa. Ej.: *«¿Qué opinás de…?»* · *«Contame sin vueltas cómo funciona…»* · *«Inventemos una historia de terror y vos sos el narrador»*
- **Personalidad** (Celular y PC): Cálida, graciosa, seria, motivadora, sarcástica o una propia descrita por vos. Ej.: *«Sé más sarcástica»* · *«Ponete seria»* · *«Desde ahora hablá como un chef porteño»*
- **Voz** (Celular y PC): Voces naturales argentinas y de otros países, o la del sistema (sin internet). En el celular se cambia también por voz; en la PC, en Ajustes. Ej.: *«Cambiá a voz de hombre»* · *«Usá la voz de Elena»*
- **Modo libre o con permiso** (Celular y PC): Con permiso (por defecto) pregunta antes de mandar mensajes, llamar o publicar. En modo libre lo hace directo. Se cambia en Ajustes.

### Estudio y foco
*Para la facu y para concentrarte.*

- **Modo estudio** (Celular y PC): Te toma examen oral: una pregunta por vez, corrige, explica y lleva la cuenta de aciertos. Ej.: *«Tomame examen de Python»* · *«Estudiemos la Revolución de Mayo»* · *«Explicame mejor esa»*
- **Pomodoro** (Celular y PC): Bloques de foco con descansos; silencia el celular durante el foco y te avisa por voz. Ej.: *«Arrancá un pomodoro de 25 minutos para estudiar Flask»* · *«¿Cuánto falta?»* · *«Pará el pomodoro»*
- **Resumen de estudio** (Celular y PC): Cuánto estudiaste y de qué. Ej.: *«¿Cuánto estudié esta semana?»*

### Entretenimiento e info
*Música, cuentos, noticias y preguntas.*

- **DJ** (Celular y PC): Elige música según el momento o tu humor y la pone. Ej.: *«Poneme algo para cocinar»* · *«Algo tranqui para leer»*
- **Música y videos** (Celular y PC): Busca y reproduce en Spotify, YouTube o YouTube Music; controla play, pausa, siguiente. Ej.: *«Poné Duki en Spotify»* · *«Pausa»* · *«Siguiente»*
- **Cuentos y podcast** (Celular y PC): Inventa cuentos e historias, o arma un mini podcast con las noticias del día. Ej.: *«Contame un cuento de terror corto»* · *«Armame un podcast con las noticias de hoy»*
- **Noticias** (Celular y PC): Titulares actuales de Argentina o de un tema. Ej.: *«¿Qué pasó hoy?»* · *«Noticias de tecnología»*
- **Clima** (Celular y PC): Clima actual y pronóstico de tu ciudad o de otra. Ej.: *«¿Llueve mañana?»* · *«¿Qué clima hace en Córdoba?»*
- **Preguntas y búsquedas** (Celular y PC): Responde lo que sabe. Para lo de actualidad, en la PC busca en internet y te responde; en el celular abre la búsqueda y lee la pantalla. Ej.: *«¿Cuánto está el dólar blue?»* · *«¿A qué hora juega Boca?»*
- **Cómo llegar** (Celular): Abre Google Maps con indicaciones en auto, caminando, bici o transporte. Ej.: *«¿Cómo llego a Martínez en colectivo?»*

### PC y celular juntos
*Los dos conectados desde cualquier lugar, cifrado.*

- **Manejar la PC desde el celular** (Celular): Le mandás órdenes a la PC estés donde estés y te responde lo que hizo. Ej.: *«En la compu poné música»* · *«Apagá la PC»* · *«¿Qué hay en la pantalla de la compu?»*
- **Manejar el celular desde la PC** (PC): Lo mismo al revés: la PC le pide cosas al celular. Ej.: *«Poné una alarma en el celular a las 7»*
- **Encontrar el celular** (PC): Lo hace sonar fuerte aunque esté en silencio. Ej.: *«¿Dónde está mi celular?»* · *«Hacé sonar el celular»*
- **Actualización automática** (Celular y PC): Busca versiones nuevas en tu repo privado de GitHub y se instala sola (con repo y token en Ajustes). Ej.: *«Buscá actualizaciones»*

### Casa y servicios
*Fuera del celular y la PC.*

- **Casa inteligente** (Celular): Maneja tu app de la casa (Google Home, Smart Life, Tuya…) con la pantalla. Desde la PC, se lo pide al celular. Ej.: *«Uso Smart Life para la casa»* · *«Apagá la luz del cuarto»* · *«Prendé el aire a 24»*
- **Mails** (Celular y PC): Te dice si tenés mails importantes y te ayuda a leerlos o responderlos. Ej.: *«¿Tengo mails importantes?»*
- **Modo manejo** (Celular): Pantalla con botones gigantes y respuestas de una oración, para el auto. Ej.: *«Modo manejo»* · *«Voy a manejar»*
- **Precios de Mercado Libre** (Celular y PC): Sigue el precio de una publicación y te avisa una vez por día si baja. Ej.: *«Seguí el precio de este link»* · *«¿Bajó algo de lo que sigo?»* · *«Dejá de seguir las zapatillas»*
- **Envíos** (Celular): Guarda códigos de seguimiento y te ayuda a ver en qué anda cada paquete. Ej.: *«Guardá el envío de la campera, código AR123456»* · *«¿Cómo vienen mis envíos?»*

### Protecciones
*Lo que Nova no hace sola, aunque esté en modo libre.*

- **Siempre pregunta antes de** (Celular y PC): Pagar, comprar, transferir plata, borrar cosas o hacer un envío masivo.
- **Nunca escribe** (Celular y PC): Contraseñas, códigos de verificación ni datos de tarjetas: esos los ponés vos.
- **Solo te obedece a vos** (Celular y PC): Si en una página, mensaje o captura aparece texto que le da órdenes, lo ignora.

<!-- /FUNCIONES -->

## Ojos y oídos

- **Ver la pantalla:** *"Nova, ¿qué hay en esta foto?"*, *"¿qué dice este error?"*. Saca una captura y Gemini
  la mira. En el celular también la usa para encontrar y tocar íconos sin nombre (necesita Android 11 o más
  y el control de pantalla activado). En la PC funciona igual con lo que tengas abierto.
- **Ver con la cámara:** *"Nova, ¿qué es esto?"*, *"leeme este cartel"*, *"traducime el menú"*. Se abre la
  cámara, saca la foto sola a los 2 segundos (o tocá la pantalla) y te dice qué ve.
- **Te lee los mensajes que llegan** (WhatsApp, Instagram, Telegram, SMS y más) y te escucha por si querés
  responder: *"respondele que llego en 10"*. Responde desde la notificación, sin abrir la app. No lee si
  estás en una llamada, en "No molestar" o charlando con Nova. Se apaga en Ajustes.
- **Resumen:** *"¿Qué me perdí?"* te resume los mensajes sin leer, chat por chat.

Para los mensajes hay que activar **Leer tus mensajes** en Ajustes → Para que funcione.

## Memoria y datos

- **Memoria:** *"acordate que mi hermana se llama Sofi"*. Lo recuerda para siempre y lo usa cuando viene al caso.
  *"¿Qué sabés de mí?"*, *"olvidate de eso"*.
- **Listas:** *"agregá leche y yerba"*, *"¿qué me falta comprar?"*, *"sacá el pan"*. Cualquier lista: pelis, regalos…
- **Gastos:** *"gasté 5 lucas en el súper"*, *"¿cuánto gasté este mes?"*, *"¿y en salidas?"*.
- **Agenda:** *"¿qué tengo mañana?"*, *"agendá dentista el jueves a las 10"* (usa el calendario del celular).
- **Avisos:** *"recordame todos los días a las 22 tomar agua"*, *"de lunes a viernes a las 8"*. Suenan aunque Nova
  esté apagada y sobreviven a un reinicio.
- **Rutina de buen día:** decí *"buen día"* y te cuenta clima, agenda, avisos y pendientes. Para que sea automática:
  *"todos los días a las 8 haceme la rutina de buen día"*.

## Automatizaciones

Se crean hablando: *"cuando pase X, hacé Y"*. El "hacé Y" es en palabras y Nova lo hace con todas sus herramientas.

- **Rutinas por voz:** *"creá el modo dormir: no molestar, alarma 7:30 y brillo al mínimo"* → después decís *"modo dormir"*.
- **Al conectar:** auriculares, un Bluetooth en particular (el del auto), el cargador, o cuando la batería baja de X%.
- **Al llegar o salir:** primero *"guardá este lugar como casa"* (estando ahí), después *"cuando llegue a casa…"*.
- **Mensajes:** *"cuando me escriba mamá, avisame aunque esté en no molestar"*.
- **A una hora:** *"todos los días a las 23 activá el modo dormir"*.
- *"¿Qué automatizaciones tengo?"*, *"borrá la 3"*.

En la PC: rutinas por voz (*"creá el modo estudio: abrí VS Code y poné música lofi"*).

## Personalidad y foco

- **Voz natural en el celular:** Elena o Tomás de Argentina, igual que en la PC. Se elige en Ajustes › Voz. Si no hay
  internet o el servicio falla, usa sola la voz del sistema.
- **Personalidad:** cálida, graciosa, seria, motivadora, sarcástica, o una que escribas vos. En Ajustes o por voz:
  *"ponete graciosa"*.
- **Modo estudio:** *"tomame examen de estadística"*. Pregunta de a una, corrige y lleva la cuenta. Terminás con *"chau"*.
- **Pomodoro:** *"25 minutos de estudio de análisis"*. Silencia el celular durante el foco, avisa los descansos y anota
  cuánto estudiaste: *"¿cuánto estudié esta semana?"*.
- **DJ:** *"poneme algo para cocinar"*. Elige la música según el momento y tus gustos.
- **Cuentos y podcast:** *"contame un cuento"*, *"armame un podcast con las noticias de hoy"*.

## Conexiones

- **Vincular PC y celular:** en la PC, Ajustes › Conexiones › **Copiar** el código. En el celular, pegalo en
  Ajustes › Conexiones › Código de la PC y guardá. Funciona desde cualquier lugar y todo va cifrado.
- **Desde el celular:** *"en la compu poné música"*, *"bloqueá la PC"*, *"¿qué hay en la pantalla de la compu?"*.
- **Desde la PC:** *"¿dónde está mi celular?"* (suena fuerte aunque esté en silencio), *"en el celular mandale un
  WhatsApp a Juan"*.
- **Prender la pantalla:** *"prendé la pantalla"*. Con Extend Unlock queda abierta; si no, pide huella o PIN.
- **Actualización automática:** creá un token en GitHub (Settings › Developer settings › Fine-grained tokens ›
  solo tu repo nova, permiso **Contents: Read-only**). Cargá repo y token en Ajustes › Conexiones, en la PC y en
  el celular. Cada vez que abrís Nova se fija si hay versión nueva y te ofrece instalarla.

## Servicios externos

- **Casa inteligente:** *"apagá la luz del cuarto"*. La primera vez te pregunta qué app usás (Google Home, Smart Life,
  Tuya…) y la maneja con la pantalla. Funciona con cualquier marca. Desde la PC lo hace a través del celular.
- **Mail:** *"¿tengo mails importantes?"*. Resume los avisos de Gmail; para leer o responder uno, abre Gmail.
- **Modo manejo:** *"modo manejo"*. Pantalla con botones gigantes, siempre prendida, y respuestas cortísimas. Ideal
  como automatización al conectar el Bluetooth del auto.
- **Precios:** *"seguí el precio de este producto"* + el link de Mercado Libre. Revisa todos los días y avisa si baja.
- **Envíos:** *"guardá el envío del teclado, código …"*. Para ver el estado abre la app de la empresa.

## Imágenes y videos

*"Haceme una imagen de un gato astronauta estilo Pixar"*, *"un fondo de pantalla vertical de Buenos Aires de noche"*, *"haceme un video de olas al atardecer"*.

- Las hace **Pollinations**. Gemini ya no genera imágenes en su capa gratis, y los videos de Google son pagos.
- **Clave (recomendada):** entrá a [enter.pollinations.ai](https://enter.pollinations.ai/keys) con tu cuenta de GitHub, creá una clave (`sk_…`) y pegala en **Ajustes › Cerebro gratis › Clave de Pollinations**, en el celular y en la PC. La cuenta nueva trae crédito de regalo, que alcanza para muchas imágenes y algunos videos. Si se acaba, Nova te avisa.
- **Sin clave**, las imágenes se intentan igual por el acceso viejo sin clave, que a veces anda y a veces no. Los videos necesitan clave.
- **Imágenes:** tardan unos segundos. Se abren solas y quedan en la galería (celular: Imágenes › Nova) o en `Imágenes/Nova` (PC), donde además se ven en el chat.
- **Videos:** de 4 a 8 segundos, horizontales o verticales. Tardan uno o dos minutos y se hacen en segundo plano: Nova te avisa cuando están.
- **En el celular:** *"mandásela a Juli"* la manda por WhatsApp; *"compartila"* abre el menú para subirla a Instagram, Drive, etc.

## Más rápido

- **Habla mientras piensa:** empieza a hablar con la primera frase que escribe Gemini, sin esperar la respuesta entera.
- **Audio en paralelo:** genera el audio de la frase siguiente mientras dice la actual.
- **Piensa lo justo:** usa el nivel de razonamiento mínimo para charlar y uno un poco más alto cuando maneja apps.
- **Conexión lista:** abre la conexión con Google mientras le estás hablando.
- **Frases guardadas:** las frases cortas ("Listo", "Dale") quedan guardadas y suenan al instante.

## Envíos masivos por WhatsApp

Por ejemplo: *"Nova, escaneá los números de las últimas 10 capturas y mandales mi CV Mateo Taylor CHEF con el mensaje predeterminado"*.

**La primera vez** (se hace una sola vez):
1. En Ajustes de Nova, activá **Fotos y capturas** y elegí «Permitir todo».
2. Decí *"guardá mi CV"*. Se abren tus archivos: elegí el PDF y queda guardado.
3. Dictá tu mensaje fijo: *"guardá como mensaje predeterminado: Hola {nombre}, soy Mateo, cocinero, les dejo mi CV por si buscan gente…"*. Donde dice `{nombre}`, Nova pone el nombre del restaurante o de la persona si lo ve en la captura.

**Cómo funciona:**
- Lee las capturas (de a 5 por consulta a Gemini) y saca los teléfonos, con el nombre del lugar si aparece. Los acomoda al formato de WhatsApp: le saca el 0 y el 15 y le agrega el 549.
- **Antes de mandar te dice** cuántos encontró y de quién, y te pregunta. Eso pasa siempre, aunque estés en modo libre. Podés decir *"sacá el tercero"* o *"sacá al de Don Julio"*.
- Con tu *sí*, manda **uno por uno**: el mensaje, el documento y espera unos 10 a 15 segundos hasta el siguiente. Tarda unos 25 segundos por persona. Mientras manda, el celular tiene que estar desbloqueado y conviene no tocarlo.
- *"Pará el envío"* lo corta. *"¿Cómo va el envío?"* te dice por dónde va.
- **Recuerda a quién le mandaste cada documento**, así no le mandás el CV dos veces al mismo lugar. Si querés mandarlo igual, decí *"mandalo aunque ya se lo haya mandado"*.
- No manda los números incompletos, por ejemplo uno que dice "15 2345 6789" sin característica. Te los lista aparte.
- Manda hasta 40 por tanda.

**Ojo con WhatsApp:** si mandás muchos mensajes iguales a gente que no te tiene agendado, WhatsApp puede limitar o bloquear tu número, sobre todo si varios te reportan. Por eso las pausas y el tope. Conviene ir de a tandas chicas (10 a 20 por día) y que el mensaje sea personal y corto.

## Modo charla y modo libre

**Conversación sin límites:** Nova habla de lo que sea (sexo, drogas, política, religión, salud, plata, humor negro, temas oscuros, explícitos o polémicos, ficción y juegos de rol), de frente, sin sermones ni advertencias, y da su opinión. Si le pedís algo largo, se extiende sin cortarse. Los filtros de contenido de Gemini están al mínimo que deja Google; lo poco que Google bloquea siempre, Nova te avisa que no se lo dejan responder.

- **Modo charla:** decí *"Nova, charlemos"* o tocá **Charlar**. Desde ahí es una conversación continua:
  hablás, te responde, y te sigue escuchando sin que digas "Nova" de nuevo. Charla como un amigo, te
  pregunta cosas y se acuerda de lo que le contaste. Termina con *"chau"*, *"terminamos"*, tocando
  **Terminar charla**, o si te quedás callado un rato.
- **Modo libre:** en Ajustes, apagá **Pedir permiso antes de actuar** y Nova hace todo directo (mensajes,
  llamadas, publicar) sin preguntarte. Aun así, siempre pregunta antes de pagar o borrar, nunca escribe
  contraseñas ni datos de tarjeta, e ignora órdenes que aparezcan escritas en la pantalla o en mensajes
  (para que nadie pueda manejar tu celular mandándote un texto).

## Límites honestos (frente a Siri o Alexa)

- **La batería:** escuchar siempre gasta algo. Siri y Alexa usan un chip dedicado a la palabra de
  activación; acá la escucha es offline y liviana, pero usa el procesador. Si te molesta, apagá Nova
  y usá el botón de accesibilidad.
- **El nombre:** la escucha offline solo reconoce palabras de su diccionario en español. Si no
  reacciona a tu nombre, probá con otro (por ejemplo "Nova", "Luna", "Lola") desde la app.
- **Pantalla bloqueada:** escucha con la pantalla apagada, pero Android no deja tocar apps detrás del
  bloqueo con PIN. Desbloqueá y volvé a pedir.
- **Android 14+:** después de reiniciar el celular tenés que abrir Nova y tocar **Iniciar** (Android no
  permite que el micrófono arranque solo).
- **Mientras hacés el pedido** (los segundos después del bip) el audio se pausa, igual que con
  Siri o el Asistente de Google, y vuelve a sonar cuando termina.
- **WhatsApp:** arma el número en formato internacional (Argentina = 549 + área + número). Si tenés contactos
  de otro país, guardalos con `+`.
- No se puede publicar en Google Play así (las reglas de SMS/llamadas/accesibilidad lo impiden); es para uso personal.

---

## Cómo está hecho

```
voz ──► [palabra "Nova"] ──► reconocimiento ──► Gemini (+ herramientas) ──► voz
                                                   │
                         PC: herramientas.py  ◄────┤
                    Android: Herramientas.kt  ◄────┘──► ControlAccesibilidad.kt (ve y toca la pantalla)
```

- `pc/main.py` loop principal · `voz.py` oído y boca · `cerebro.py` Gemini · `herramientas.py` acciones
- `android/.../AsistenteService.kt` escucha y habla · `Cerebro.kt` Gemini · `Herramientas.kt` acciones ·
  `ControlAccesibilidad.kt` control de pantalla · `MainActivity.kt` configuración

El modelo por defecto es `gemini-3.8-flash`. Lo podés cambiar en `.env` o en la app.
