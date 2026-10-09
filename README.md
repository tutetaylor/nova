# Nova — tu asistente de voz personal

Dos asistentes que comparten personalidad y cerebro (Claude):

| | **Nova para PC** (`pc/`) | **Nova para Android** (`android/`) |
|---|---|---|
| Lenguaje | Python | Kotlin (Android Studio) |
| Activación | Decís "Nova" (offline, Vosk) | Decís "Nova", botón de accesibilidad o "Hablar" en la notificación |
| Voz | Neural es-AR (Elena / Tomás) | Voz de Google del celular |
| Control | Programas, navegador, teclado, volumen, música, capturas, apagar/bloquear | **El celular entero**: lee la pantalla de cualquier app y toca, escribe y desliza |
| Extras | Recordatorios por voz, notas, clima | WhatsApp, SMS, llamadas, alarmas, temporizadores, calendario, linterna, Maps, Spotify, ajustes, batería |

Funcionan separados: el de Android anda en cualquier lugar, sin la PC.

---

## 1. Conseguí la clave de Claude

1. Entrá a https://console.anthropic.com → **API Keys** → **Create Key**.
2. Cargá algo de crédito en **Billing**. Uso normal de un asistente: pocos dólares por mes.
3. Guardá la clave (`sk-ant-...`). No la subas a GitHub.

---

## 0. La forma fácil: GitHub compila todo por vos

Subí esta carpeta a un repo de GitHub (privado). Con cada cambio, **Actions** arma solo:

- **`Nova.exe`** para Windows → en Releases, "Nova para PC". Doble clic y listo, sin instalar Python.
  La primera vez te pide la clave, el nombre y la ciudad, y baja el modelo de voz. Tu configuración,
  notas y recordatorios quedan en `C:\Users\<vos>\Nova\`.
- **`Nova.apk`** para Android → en Releases, "Nova - última versión". Abrilo desde el celular e instalalo.

Si Windows muestra "Windows protegió su PC": **Más información → Ejecutar de todas formas** (pasa con
cualquier programa propio sin firma digital). Para que arranque con Windows: `Win+R` → `shell:startup`
→ pegá ahí un acceso directo a `Nova.exe`. Para el modo texto: abrí una terminal y corré `Nova.exe --texto`.

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
python main.py            # modo voz: decí "Nova" y hablale
python main.py --texto    # modo texto, para probar sin micrófono
```

Probá: *"Nova, ¿qué clima hay mañana?"*, *"abrí Spotify"*, *"poné música"*, *"subí el volumen"*,
*"recordame en 20 minutos sacar la ropa"*, *"anotá que tengo que llamar al dentista"*, *"buscá en YouTube recetas de pizza"*.

**Que arranque solo con Windows:** `Win+R` → `shell:startup` → creá un acceso directo a
`.venv\Scripts\pythonw.exe main.py` con "Iniciar en" = la carpeta `pc`.

**Agregar habilidades:** en `herramientas.py`, escribí una función con `@herramienta(...)`. Claude la descubre sola.

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
- Decí **"Nova"** y esperá el tono, o todo junto: *"Nova, poné una alarma a las 7"*.
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

## Límites honestos (frente a Siri o Alexa)

- **La batería:** escuchar siempre gasta. Siri y Alexa usan un chip dedicado a la palabra de activación;
  acá se usa el reconocedor de Google en loop. Si te molesta, apagá Nova y usá el botón de accesibilidad.
- **Pantalla bloqueada:** escucha con la pantalla apagada, pero Android no deja tocar apps detrás del
  bloqueo con PIN. Desbloqueá y volvé a pedir.
- **Android 14+:** después de reiniciar el celular tenés que abrir Nova y tocar **Iniciar** (Android no
  permite que el micrófono arranque solo).
- **Bip:** algunos celulares hacen un bip al reiniciar la escucha; la app lo silencia, pero no en todos los modelos.
- **WhatsApp:** arma el número en formato internacional (Argentina = 549 + área + número). Si tenés contactos
  de otro país, guardalos con `+`.
- No se puede publicar en Google Play así (las reglas de SMS/llamadas/accesibilidad lo impiden); es para uso personal.

---

## Cómo está hecho

```
voz ──► [palabra "Nova"] ──► reconocimiento ──► Claude (+ herramientas) ──► voz
                                                   │
                         PC: herramientas.py  ◄────┤
                    Android: Herramientas.kt  ◄────┘──► ControlAccesibilidad.kt (ve y toca la pantalla)
```

- `pc/main.py` loop principal · `voz.py` oído y boca · `cerebro.py` Claude · `herramientas.py` acciones
- `android/.../AsistenteService.kt` escucha y habla · `Cerebro.kt` Claude · `Herramientas.kt` acciones ·
  `ControlAccesibilidad.kt` control de pantalla · `MainActivity.kt` configuración

El modelo por defecto es `claude-sonnet-5-5` (rápido y preciso). Lo podés cambiar en `.env` o en la app.
