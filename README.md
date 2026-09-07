# Yammbo KDS

Pantalla de cocina para Android. Envuelve la pantalla de comandas de
`pos.yammbo.com/kds/<token>` y le añade lo que una pestaña de navegador no puede
hacer: hablar con la impresora térmica, seguir viva por detrás y avisar **encima
de otras apps** cuando entra un pedido en línea.

Pensada para una tablet colgada en la pared de una cocina, encendida meses.

## Por qué es una app y no una web

La interfaz de las comandas **sigue viviendo en el servidor**: la app solo la
carga en un WebView. Así el diseño se cambia en el worker y todas las cocinas lo
ven al recargar, sin recompilar ni repartir un APK.

Lo que añade el envoltorio nativo:

| | Navegador | Esta app |
|---|---|---|
| Aviso **encima de Loyverse** | Imposible | Sí (`SYSTEM_ALERT_WINDOW`) |
| Sobrevivir en segundo plano | Si Android mata Chrome, se pierde el pedido en silencio | Servicio en primer plano |
| Sonido que atraviesa el silencio | Limitado | Tono de alarma (`USAGE_ALARM`) |
| Impresora térmica | No | Bluetooth, red o USB |
| Volver sola tras un corte de luz | No | Sí |

## Avisos

Cuando entra un pedido **en línea**: notificación, sonido de alarma y un cartel
centrado dibujado sobre lo que haya delante (normalmente la caja de Loyverse).
Se cierra tocando en cualquier sitio, o solo a los 20 segundos.

**Las ventas del TPV no avisan.** Aparecen en la pantalla del KDS como
cualquier comanda, pero no suenan ni interrumpen: esa venta la acaba de teclear
el propio cajero y avisarle de lo que él mismo cobró es ruido — y el ruido
inútil enseña a la gente a ignorar los avisos que sí importan.

## Impresión

Ticket ESC/POS con el número de comanda a doble altura, los platos en grande y
la nota del pedido enmarcada. Tres transportes:

- **Bluetooth (SPP)** — se empareja una vez en los ajustes de Android.
- **Red / WiFi** — TCP al puerto 9100. El más fiable para una cocina fija: la
  impresora cuelga del router y no depende de ninguna tablet.
- **USB (OTG)** — cable directo.

Papel de 58 mm (32 columnas) u 80 mm (48).

## Cuota de la API

`/kds/<token>/data` consulta a Loyverse en cada llamada, y el límite es **300
peticiones / 5 min por comercio**. Una pantalla a 5 s ya gasta 60.

Por eso la app **no añade un segundo sondeo**: en primer plano se engancha al
`fetch` que la propia página ya hace, y solo toma el relevo el servicio cuando
la app pasa a segundo plano — o cuando la página lleva 20 s sin traer datos,
por si el worker, el wifi o el token han caído y se ha quedado muda.

## Actualizaciones

La app mira una vez al día si hay versión nueva y ofrece instalarla.

La versión se pregunta a `pos.yammbo.com/kds/version.json`, **no** a la API de
GitHub: sin autenticar está limitada a 60 peticiones/hora **por IP**, y en redes
móviles con NAT compartido esa cuota se agota por culpa de terceros → 403 → la
detección falla en silencio. GitHub queda de respaldo, y el APK sí se descarga
de su CDN.

> Android **no permite instalar en silencio** a una app normal: haría falta ser
> propietario del dispositivo. La app descarga el APK y abre el instalador del
> sistema, donde alguien confirma una vez.

Todas las versiones van firmadas con la misma clave; si cambiara, Android
rechazaría la actualización.

## Instalar

Descarga el APK de la [última
release](https://github.com/yammbocom/Yammbo-KDS/releases/latest) e instálalo.
Google Play Protect avisa de que no conoce al desarrollador — es lo normal con
cualquier app instalada fuera de Play; toca *Instalar de todas formas*.

Al abrirla por primera vez pide el **enlace de cocina**, que sale del panel en
**Cocina › Copiar**. Ese enlace *es* la llave: no pide contraseña, solo ve las
comandas de ese local, y se revoca desde el panel generando uno nuevo.

Permisos que conviene conceder: notificaciones, *mostrar sobre otras apps* (sin
él no hay cartel) y Bluetooth si la impresora va por ahí.

## Compilar

```bash
./gradlew :app:assembleDebug     # APK de pruebas
./gradlew :app:assembleRelease   # APK firmado
./gradlew :app:testDebugUnitTest # el renderizador del ticket
```

La firma se lee de `keystore.properties` (ver `keystore.properties.ejemplo`), de
variables `SIGNING_*`, o del llavero compartido de Yammbo Music. **Nunca** va en
el repositorio.

El test del ticket lo renderiza a texto y comprueba que ninguna línea se pase
del papel en 58 y 80 mm: es la única forma de revisar el ajuste de línea sin una
térmica delante.
