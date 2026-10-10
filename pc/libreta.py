"""La libreta de Nova en la PC: memoria, listas y gastos, guardados en datos/libreta.json."""
import datetime as dt
import json
import threading

import config
from voz import normalizar

_ARCHIVO = config.DATOS / "libreta.json"
_lock = threading.Lock()


def _cargar():
    try:
        return json.loads(_ARCHIVO.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _guardar(d):
    _ARCHIVO.write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")


def _pesos(n):
    return "$" + f"{n:,.0f}".replace(",", ".")


# ------------------------------------------------------------ MEMORIA
def recordar(dato):
    with _lock:
        d = _cargar()
        mem = d.setdefault("memoria", [])
        if any(normalizar(m["dato"]) == normalizar(dato) for m in mem):
            return "Eso ya lo sabía."
        mem.append({"dato": dato.strip(), "fecha": f"{dt.date.today():%d/%m/%Y}"})
        _guardar(d)
    return "Anotado en mi memoria."


def olvidar(algo):
    with _lock:
        d = _cargar()
        antes = len(d.get("memoria", []))
        d["memoria"] = [m for m in d.get("memoria", []) if normalizar(algo) not in normalizar(m["dato"])]
        _guardar(d)
    borrados = antes - len(d["memoria"])
    return f"Listo, me olvidé {borrados} cosa(s)." if borrados else "No tenía nada sobre eso."


def memoria_para_cerebro():
    return "\n".join("- " + m["dato"] for m in _cargar().get("memoria", [])[-60:])


# ------------------------------------------------------------ LISTAS
def lista_agregar(lista, items):
    with _lock:
        d = _cargar()
        l = d.setdefault("listas", {}).setdefault(normalizar(lista), [])
        existentes = {normalizar(x) for x in l}
        nuevos = [i.strip() for i in items if i.strip() and normalizar(i) not in existentes]
        l.extend(nuevos)
        _guardar(d)
    return f"Agregué {', '.join(nuevos)} a {lista}." if nuevos else "Ya estaba en la lista."


def lista_quitar(lista, item):
    with _lock:
        d = _cargar()
        l = d.get("listas", {}).get(normalizar(lista))
        if not l:
            return f"No tengo una lista {lista}."
        for x in l:
            if normalizar(item) in normalizar(x):
                l.remove(x)
                _guardar(d)
                return f"Saqué {x}."
    return f"No encontré {item} en {lista}."


def lista_ver(lista=None):
    listas = _cargar().get("listas", {})
    if not lista:
        return "\n".join(f"{n} ({len(v)}): {', '.join(v)}" for n, v in listas.items()) or "No tenés listas."
    l = listas.get(normalizar(lista))
    return f"{lista}: {', '.join(l)}" if l else f"La lista {lista} está vacía."


def lista_borrar(lista):
    with _lock:
        d = _cargar()
        d.get("listas", {}).pop(normalizar(lista), None)
        _guardar(d)
    return f"Borré la lista {lista}."


# ------------------------------------------------------------ GASTOS
def _inicio(periodo):
    hoy = dt.datetime.now()
    p = normalizar(periodo or "mes")
    if p == "hoy":
        return hoy.replace(hour=0, minute=0, second=0)
    if p == "semana":
        return hoy - dt.timedelta(days=7)
    if p in ("ano", "año"):
        return hoy.replace(month=1, day=1, hour=0, minute=0, second=0)
    return hoy.replace(day=1, hour=0, minute=0, second=0)


def gasto_anotar(monto, descripcion, categoria=None):
    cat = (categoria or "otros").strip().lower()
    with _lock:
        d = _cargar()
        d.setdefault("gastos", []).append({"monto": float(monto), "desc": descripcion.strip(), "cat": cat,
                                           "t": dt.datetime.now().isoformat(timespec="minutes")})
        _guardar(d)
    total = sum(g["monto"] for g in d["gastos"] if dt.datetime.fromisoformat(g["t"]) >= _inicio("mes"))
    return f"Anotado: {_pesos(float(monto))} en {cat}. Este mes llevás {_pesos(total)}."


def gastos_resumen(periodo="mes", categoria=None):
    desde = _inicio(periodo)
    gastos = [g for g in _cargar().get("gastos", [])
              if dt.datetime.fromisoformat(g["t"]) >= desde and (not categoria or categoria.lower() in g["cat"])]
    if not gastos:
        return "No hay gastos anotados en ese período."
    por_cat = {}
    for g in gastos:
        por_cat[g["cat"]] = por_cat.get(g["cat"], 0) + g["monto"]
    return (f"Total: {_pesos(sum(por_cat.values()))}\nPor categoría: "
            + ", ".join(f"{c} {_pesos(v)}" for c, v in sorted(por_cat.items(), key=lambda x: -x[1]))
            + "\nÚltimos: " + "; ".join(f"{g['t'][:10]} {g['desc']}: {_pesos(g['monto'])}" for g in gastos[-8:]))


def gasto_borrar_ultimo():
    with _lock:
        d = _cargar()
        if not d.get("gastos"):
            return "No hay gastos."
        g = d["gastos"].pop()
        _guardar(d)
    return f"Borré el último gasto: {g['desc']} {_pesos(g['monto'])}."


# ------------------------------------------------------------ RUTINAS POR VOZ
def rutina_crear(frase, hacer):
    with _lock:
        d = _cargar()
        reglas = d.setdefault("rutinas", [])
        reglas[:] = [r for r in reglas if normalizar(r["frase"]) != normalizar(frase)]
        nuevo_id = max([r["id"] for r in reglas] or [0]) + 1
        reglas.append({"id": nuevo_id, "frase": frase.strip(), "hacer": hacer.strip()})
        _guardar(d)
    return f"Creada la rutina [{nuevo_id}]: cuando digas «{frase}» → {hacer}"


def rutinas_ver():
    return "\n".join(f"[{r['id']}] «{r['frase']}» → {r['hacer']}" for r in _cargar().get("rutinas", [])) or "No hay rutinas."


def rutina_borrar(id):
    with _lock:
        d = _cargar()
        antes = len(d.get("rutinas", []))
        d["rutinas"] = [r for r in d.get("rutinas", []) if int(r["id"]) != int(id)]
        _guardar(d)
    return "Borrada." if len(d["rutinas"]) < antes else "No existe esa rutina."


def rutina_de_voz(dicho):
    """'modo estudio', 'activá modo estudio', 'rutina estudio' → la rutina, si existe."""
    import re
    p = re.sub(r"^(activa|activar|pone|poner|arranca|inicia|empeza|rutina|modo)\s+", "", normalizar(dicho)).strip(" .!¡")
    for r in _cargar().get("rutinas", []):
        f = normalizar(r["frase"])
        if p in (f, f.removeprefix("modo ").strip()) or f"modo {p}" == f:
            return r
    return None


# ------------------------------------------------------------ ESTUDIO
def estudio_registrar(minutos, tema):
    with _lock:
        d = _cargar()
        d.setdefault("estudio", []).append({"min": int(minutos), "tema": tema or "general",
                                            "t": dt.datetime.now().isoformat(timespec="minutes")})
        _guardar(d)


def estudio_resumen(periodo="semana"):
    p = normalizar(periodo or "semana")
    desde = _inicio("hoy") if p == "hoy" else _inicio("mes") if p == "mes" else dt.datetime.now() - dt.timedelta(days=7)
    por_tema = {}
    for e in _cargar().get("estudio", []):
        if dt.datetime.fromisoformat(e["t"]) >= desde:
            por_tema[e["tema"]] = por_tema.get(e["tema"], 0) + e["min"]
    if not por_tema:
        return "No hay tiempo de estudio registrado en ese período."
    total = sum(por_tema.values())
    return f"Estudiaste {total // 60} h {total % 60} min. " + ", ".join(f"{t}: {m} min" for t, m in por_tema.items())
