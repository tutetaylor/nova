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
- El **límite por hora** (`LIMITE_POR_HORA`, por defecto 60) reparte la cuota para que no la gastes toda de golpe.
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

**Conversación libre:** Nova habla de cualquier tema (sexo, drogas, política, religión, salud, plata, temas oscuros o polémicos), de frente y sin sermones. Si le pedís su opinión, te la da. Además, los filtros de contenido de Gemini están al mínimo que deja Google. Lo único que Google bloquea siempre, por más que se lo pidas: ahí Nova te avisa que no se lo dejan responder.

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
