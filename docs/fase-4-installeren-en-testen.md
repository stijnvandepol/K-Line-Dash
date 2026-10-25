# Fase 4: installeren en testen

Fase 4 voegt **waarschuwingen**, het **opnemen van ritten** en de **export** toe, en bouwt de navigatie
om. Nieuw in de app:

- **Waarschuwingen op elke pagina:** een kader rond het scherm en een balk boven de onderbalk, plus een
  piep (de service speelt die) voor waarschuwingen waarbij geluid aan staat.
- **TRIPS-pagina:** ritten opnemen als CSV-bestand (met de hand of automatisch zodra de motor draait),
  de ritlijst, een rit verwijderen, kopiëren naar een USB-stick of SD-kaart en een Wi-Fi-export met PIN.
- **SETUP-pagina:** menu met **Connection** (het oude Connection-scherm), **Warnings** (nieuw) en
  **Debug** (het oude Debug-scherm).
- **Dashboardkleuren volgen de waarschuwingsgrenzen:** de waarden voor water en accu kleuren op
  dezelfde grenzen als SETUP > Warnings. Er is één plek waar die grenzen staan.

De app is volledig Engelstalig; deze handleiding blijft Nederlands en noemt de schermteksten zoals ze in
de app staan. Screenshots van de simulator staan in `docs/screenshots/fase-4-*.png`.

## APK bouwen en installeren

Hetzelfde als in fase 1 tot en met 3 (zie `docs/fase-1-installeren-en-testen.md`):

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Of kopieer `app/build/outputs/apk/debug/app-debug.apk` naar een USB-stick en open hem op de head unit.

Controle vooraf op de pc (alle vier moeten slagen):

```bash
./gradlew :obd-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## De nieuwe navigatie

De balk onderaan heeft vijf pagina's: **DASH · SENSORS · DIAG · TRIPS · SETUP**. Rechts staat nog
steeds de verbindingsstatus.

**SETUP** heeft links een eigen menu (240 dp breed): **Connection**, **Warnings**, **Debug**. De pagina
onthoudt de sectie die je het laatst gebruikte zolang de app draait: ga je van SETUP > Warnings naar DASH
en weer terug, dan sta je weer op Warnings. Een knop **Go to Connection** op SENSORS of DIAG brengt je naar
SETUP > Connection. Zie `fase-4-setup-connection.png` en `fase-4-setup-debug.png`.

## Waarschuwingen

Zolang er minstens één waarschuwing actief is, zie je op **elke pagina**:

- Een **kader van 6 dp** rond het hele scherm: amber bij *caution*, rood bij *danger*. De inhoud van de
  pagina schuift 6 dp naar binnen zolang het kader er is, het kader bedekt dus niets.
- Een **balk van 56 dp** direct boven de onderbalk in dezelfde kleur (zwarte tekst op amber, witte tekst
  op rood). De balk zegt wat en hoeveel: `WATER 106 °C`, `BATTERY 11.4 V`, `NEW FAULT CODE: CHECK DIAG`,
  `CHECK ENGINE LIGHT ON`, `ADAPTER CONNECTION LOST`. Zijn er meer waarschuwingen, dan staat de
  ergste vooraan (bij gelijke ernst de oudste) en staat rechts `+1`, `+2`, enzovoort.
- **Tik op de balk** om hem te verbergen. Het kader blijft staan. De balk komt terug als die waarschuwing
  eerst weggaat en daarna opnieuw optreedt, of als hij ernstiger wordt (van amber naar rood).
- Er knippert niets en er komt geen venster. Het geluid komt van de service (alarmvolume van Android,
  een korte piep bij *caution*, een dubbele bij *danger*).

Zie `fase-4-warning-overlay.png` (rood) en `fase-4-dashboard.png` (amber, met het water-getal in amber).

### SETUP > Warnings

Eén rij per waarschuwing (zie `fase-4-setup-warnings.png`):

| Rij | Aan/uit | Sound | Grenzen |
|---|---|---|---|
| Coolant too hot | schakelaar | schakelaar | **CAUTION** en **DANGER** (stap 1 °C) |
| Battery voltage low | schakelaar | schakelaar | **RUNNING** en **ENGINE OFF** (stap 0.1 V) |
| Battery voltage high | schakelaar | schakelaar | **RUNNING** (stap 0.1 V) |
| New fault code | schakelaar | schakelaar | |
| Check engine light on | schakelaar | schakelaar | |
| Adapter connection lost | schakelaar | schakelaar | |

- Een grens stel je in met **−** en **+** (grote knoppen van 72 x 72 dp). **Houd een knop ingedrukt** om
  te blijven stappen: 20 graden is dan twee seconden werk, geen twintig tikken. Er is geen toetsenbord.
- De grenzen hebben een bereik: coolant 60 tot 130 °C, **caution blijft altijd onder danger** (minstens
  1 °C verschil), battery running low 11.0 tot 14.0 V, running high 14.0 tot 16.5 V, engine off low 10.0
  tot 12.8 V. Aan het eind van het bereik wordt de knop grijs.
- Staat een waarschuwing uit, dan zijn zijn **Sound**-schakelaar en zijn grenzen grijs en niet bedienbaar.
- **Reset to defaults** (onderaan) zet alles terug: coolant caution 103 en danger 108 °C, running low
  13.2 V, running high 15.0 V, engine off low 12.0 V, alle waarschuwingen aan, geluid bij Coolant, New
  fault code en Check engine light.
- De dashboardkleuren volgen deze grenzen: water **amber** vanaf de caution-grens en **rood** vanaf de
  danger-grens; accu **amber** buiten de running-grenzen terwijl de motor draait en onder de
  engine-off-grens terwijl hij uit staat. Dit geldt ook op SENSORS.

## De TRIPS-pagina

Zie `fase-4-trips.png` en `fase-4-trips-export.png`.

**Links (opnemen):**

- Bovenaan de toestand: **Recording** met een rode stip en de bestandsnaam, of **Not recording** met wat je
  moet doen (**Recording starts when the engine runs.**, **Press Record to start a trip.** of
  **Not connected. Connect under Setup, Connection.**).
- **TIME** (looptijd, telt elke seconde) en **SAMPLES** (aantal rijen in het bestand; een rij per
  seconde). Staat er `--`, dan neemt de app niet op.
- **Record** (wit) of **Stop** (met rand). **Record** is grijs zolang de auto niet verbonden is.
- De schakelaar **Record automatically when the engine runs**: aan = een rit begint vanzelf zodra het
  toerental boven de 400 komt en eindigt twee minuten nadat de motor stopt of de verbinding weg is.
  Stop je een rit zelf terwijl de motor draait, dan begint er pas weer een nieuwe nadat de motor één keer
  uit is geweest.
- Een rode regel onder de knop (bijvoorbeeld "Could not create the trip file. Check the free storage of
  the head unit.") is de reden dat een rit niet begon of vroegtijdig eindigde. Hij verdwijnt bij de
  eerstvolgende geslaagde start.
- **Export:** **Copy to USB or SD card** en **Start Wi-Fi export** (zie hieronder).

**Rechts (ritlijst):** nieuwste bovenaan, per rit begintijd, duur, afstand en hoogste toerental in vaste
kolommen. Achter elke rit staat **DELETE** (rood); de rit die nu loopt toont **RECORDING** en geen knop.
Een lege lijst zegt "No trips yet. Recording starts when the engine runs." (of, met automatisch opnemen
uit, "No trips yet. Press Record to start one.").

**Verwijderen** vraagt één keer: "Delete trip of 22 Oct 07:55? The CSV file is removed." met **Cancel**
(wit, heeft de focus) en **Delete** (rode rand). Na **Delete** zijn rij en bestand weg.

**Waar staan de bestanden?** In de app-map `Android/data/nl.stijnvdpol.klinedash/files/trips/`, bestandsnaam
`trip-JJJJMMDD-UUMMSS.csv`. De eerste 20 seconden van een rit staan nog in het geheugen: de kopregel wordt
pas geschreven als bekend is welke sensoren de auto beantwoordt. Een rit zonder één meting verdwijnt weer
uit de lijst.

## Eerst testen zonder auto (simulator)

De simulator start met een koelwater van **46 °C** dat in een paar minuten naar ongeveer 90 °C loopt, en
heeft **de check-engine-lamp aan** (twee opgeslagen codes, zie fase 3). Na het verbinden zie je dus meteen
een amber kader en de balk **CHECK ENGINE LIGHT ON**.

1. **Verbinden.** Open **SETUP > Connection**, kies **Simulator** en tik **Connect** (met **Connect at
   start** aan gebeurt dat bij het starten vanzelf).
2. **Waarschuwing voor te hoog koelwater.** Kijk op **DASH** wat **WATER** nu toont (bijvoorbeeld 70).
   Ga naar **SETUP > Warnings** en houd bij **Coolant too hot** de **−** links bij **CAUTION** ingedrukt
   tot de grens lager is dan de watertemperatuur (het minimum is 60). Ga naar **DASH**: **WATER** is
   amber en onderaan staat **WATER 70 °C** (eerst staat er misschien **CHECK ENGINE LIGHT ON +1**, de
   oudste waarschuwing gaat voor). Zie `fase-4-dashboard.png`.
3. **Balk verbergen.** Tik op de balk: hij verdwijnt, het kader blijft. Staat er nog een waarschuwing, dan
   neemt die zijn plaats in.
4. **Rood.** Zet bij **DANGER** de grens onder de watertemperatuur (hij blijft altijd boven caution): het
   kader en de balk worden rood met witte tekst en het water-getal rood. Zie `fase-4-warning-overlay.png`.
5. **Terug.** Tik **Reset to defaults**: kader en kleur zijn terug op 103/108 °C. Zet een waarschuwing uit
   met de schakelaar: de **Sound**-schakelaar en de grenzen van die rij worden grijs.
6. **Verbinding weg.** Tik op **SETUP > Connection > Disconnect**. Verwacht een rood kader en de balk
   **ADAPTER CONNECTION LOST**, ook op andere pagina's. (Dit gebeurt ook bij een bewuste Disconnect.)
   Tik **Connect** om verder te gaan.
7. **Een rit opnemen.** Open **TRIPS**. De simulatormotor draait, dus de app begint na een paar seconden
   zelf een rit: **Recording**, **TIME** loopt en **SAMPLES** groeit, rechts staat de rit met **RECORDING**.
   Wil je dit zelf doen: tik **Stop** en daarna **Record**. Laat hem een minuut lopen en tik **Stop**.
   De rij toont nu duur, afstand en hoogste toerental.
8. **Bestand bekijken.** `adb pull /sdcard/Android/data/nl.stijnvdpol.klinedash/files/trips` en open een
   bestand (kolommen onder meer `time_iso`, `elapsed_s`, `rpm (rpm)`, `speed (km/h)`, `coolant (°C)`).
9. **Verwijderen.** Tik **DELETE** bij een oude rit, lees de vraag, tik eerst **Cancel** (er gebeurt niets)
   en daarna nogmaals **DELETE** en **Delete**: de rij is weg. Tijdens het opnemen kan de lopende rit niet
   verwijderd worden (er staat **RECORDING** in plaats van een knop).
10. **Wi-Fi-export.** Tik **Start Wi-Fi export**. Verwacht onder de knoppen een adres (bij de emulator
    `http://10.0.2.15:8080`), een **PIN** van vier cijfers en "Stops by itself after 10 minutes without
    use." De knop heet nu **Stop Wi-Fi export**.
    - **Vanaf een telefoon** op hetzelfde netwerk (of hotspot) als de head unit: open het adres in de
      browser, typ de PIN, download een bestand of **Download all as ZIP**.
    - **Vanaf de pc met de emulator:** de emulator is niet bereikbaar vanaf het netwerk, gebruik:
      ```bash
      adb forward tcp:8080 tcp:8080
      ```
      en open `http://127.0.0.1:8080` in de browser (niet `localhost`: de export accepteert alleen een IP-adres) op de pc. Verwacht de pagina "K-Line Dash export"
      met een PIN-veld; na de juiste PIN de ritlijst.
    - Tik **Stop Wi-Fi export**: de pagina is niet meer bereikbaar. Een nieuwe start geeft een **nieuwe PIN**.
11. **Kopiëren naar USB of SD.** Tik **Copy to USB or SD card**. Het systeem opent zijn mappenkiezer
    (bij de emulator "Files on sdk_gphone64_x86_64"). Kies een map en tik **Use this folder**. Verwacht onder
    de knoppen eerst "Copying 1 of 4…" en daarna bijvoorbeeld **Copied 4 trips**. Doe het nog eens met
    dezelfde map: "Nothing new to copy: 4 already there." (bestaande bestanden met dezelfde grootte worden
    overgeslagen). Annuleer je de kiezer, dan gebeurt er niets.
    Heeft de head unit geen mappenkiezer, dan zegt de app "This head unit has no folder picker. Use the
    Wi-Fi export to get the trips off the head unit." en crasht niet.
12. **Automatisch opnemen uit.** Zet de schakelaar **Record automatically when the engine runs** uit, ga
    naar **SETUP > Connection** en zet **Simulator engine** uit en weer aan: er begint geen rit meer
    vanzelf, **Record** blijft werken.

## Testen in de auto

Adapter: Konnwei KW905 (Bluetooth LE). Neem bij elk probleem het debuglog mee (**SETUP > Debug > Export
log**).

1. **Verbinden** zoals in fase 2 (SETUP > Connection) en wacht op **Connected**.
2. **Automatisch opnemen.** Laat **Record automatically when the engine runs** aan staan en start de
   motor. Verwacht binnen enkele seconden **Recording** op TRIPS. Rij een stuk. Zet de motor uit: de rit
   loopt nog twee minuten door en eindigt dan zelf. Controleer in de lijst duur, afstand en toerental.
3. **CSV in Excel.** Haal het bestand op via de Wi-Fi-export of de USB-stick. Het bestand is UTF-8 met een
   BOM (de graden-tekens in de kopregel horen goed te staan), komma-gescheiden, met een punt als
   decimaalteken. Op een Nederlandse Excel opent dubbelklikken vaak alles in één kolom: gebruik
   **Gegevens > Uit tekst/CSV**, kies bestandsoorsprong **UTF-8**, scheidingsteken **komma** en
   (via Gegevens laden) decimaal **Engels**. Controleer: staat er één rij per seconde, kloppen toerental,
   snelheid en koelwater met wat je zag, en zijn de trage kolommen (accu, fuel trims) gevuld?
4. **Waarschuwingstoon.** Zet de grens **Coolant too hot > CAUTION** tijdelijk onder de huidige
   watertemperatuur (met **Sound** aan) en luister: komt er een **hoorbare piep** uit de speakers van de
   head unit? Zo niet, **laat het me weten**: de service gebruikt nu het alarmvolume (`STREAM_ALARM`) en
   sommige head units sturen alleen het mediavolume naar de versterker. Dan moet de stream in de service
   (`ToneGeneratorPlayer` in `AlertMonitor.kt`) naar `STREAM_MUSIC`. Zet daarna **Reset to defaults**.
5. **Accu.** Kijk of **BATT** op DASH wit blijft met draaiende motor (normaal 13.8 tot 14.5 V) en
   amber wordt als je de grens **Battery voltage low > RUNNING** tijdelijk boven de gemeten waarde zet.
   Controleer ook de **ENGINE OFF**-grens met het contact aan en de motor uit (een gezonde accu: 12.4
   tot 12.8 V).
6. **Check engine en nieuwe code.** Brandt de lamp, dan hoort **CHECK ENGINE LIGHT ON** te verschijnen en
   na **DIAG > Clear codes** weer te verdwijnen. Een **nieuwe code tijdens het rijden** geeft **NEW FAULT
   CODE: CHECK DIAG**; noteer of dat klopt met DIAG.
7. **Verbinding weg.** Trek tijdens het rijden de adapter uit het OBD-poortje (of zet Bluetooth uit):
   verwacht rood kader en **ADAPTER CONNECTION LOST**. Steek hem terug: verwacht dat de verbinding vanzelf terugkomt
   en de waarschuwing verdwijnt; noteer het als dat niet zo is.
8. **Wi-Fi-export vanaf je telefoon.** Zet de hotspot van je telefoon aan en verbind de head unit ermee.
   Tik op TRIPS **Start Wi-Fi export**, open het getoonde adres (bijvoorbeeld `http://192.168.43.50:8080`)
   in de browser van de telefoon, typ de PIN en download een rit. Staat er **No Wi-Fi or hotspot network
   found...**, dan heeft de head unit geen netwerk.
9. **USB/SD.** Steek een USB-stick of SD-kaart in de head unit, tik **Copy to USB or SD card**, kies de
   stick en **Use this folder**. Noteer of de head unit een mappenkiezer heeft en of de stick te
   kiezen is.

## Wat is er nieuw ten opzichte van fase 3

- De balk onderaan heeft **TRIPS** en **SETUP** in plaats van **CONNECTION** en **DEBUG**; die twee zijn
  secties van SETUP geworden (nu in de resterende breedte van 1040 dp).
- Waarschuwingen met kader, balk en piep, instelbaar onder **SETUP > Warnings**.
- Ritten opnemen, bekijken, verwijderen en exporteren.
- Waardekleuren voor water en accu volgen de waarschuwingsgrenzen. De oude vaste grenzen (water 100/105,
  accu 12/15 V) zijn weg; de standaardwaarden zijn nu 103/108 °C en 13.2/15.0 V (motor draait).
- Een accu onder de **engine off**-grens (standaard 12.0 V) is nu ook amber terwijl de motor uit staat.

## Bekende beperkingen fase 4

- Het kopiëren naar USB/SD is op de emulator alleen met de mappenkiezer van het systeem te bedienen; de
  kopieerlogica zelf (geen dubbele bestanden, foutmeldingen per bestand) is met unittests gedekt.
- Wi-Fi-export is gewoon HTTP, bewust (zie CODE GUIDELINES.md, "Known oddities"): alleen aan als jij het
  start, met PIN, alleen lezen, en hij stopt na 10 minuten zonder gebruik.
- Een bewuste **Disconnect** geeft ook **ADAPTER CONNECTION LOST** (rood). De waarschuwing komt uit de
  service; er is geen onderscheid tussen een weggevallen en een bewust verbroken verbinding.
- Het bestand van een lopende rit is de eerste ~20 seconden nog leeg (de kopregel volgt daarna); haal een
  lopende rit dus niet op voordat hij een halve minuut loopt.
- Waarschuwingen staan los van de dashboardkleuren: zet je een waarschuwing uit, dan kleurt het getal nog
  steeds op de grenzen.
