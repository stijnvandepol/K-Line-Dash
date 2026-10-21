# Fase 3: installeren en testen

Fase 3 voegt de **DIAG**-pagina toe: foutcodes uitlezen (stored, pending en permanent), de
check-engine-lamp, de readiness-monitors, het freeze frame, de VIN en de ECU-naam, en foutcodes
wissen met een dubbele bevestiging. Alles werkt offline: de omschrijvingen van de codes zitten in
de app. De app is volledig Engelstalig; deze handleiding blijft Nederlands en noemt de schermteksten
zoals ze in de app staan.

Screenshots van de simulator staan in `docs/screenshots/fase-3-*.png`.

## APK bouwen en installeren

Hetzelfde als in fase 1 en 2 (zie `docs/fase-1-installeren-en-testen.md`):

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

## Wat staat er op de DIAG-pagina

- **Bovenbalk:** **CHECK ENGINE** (**ON** in rood, **OFF** in wit), **STORED CODES** (aantal),
  **LAST READ** (tijd van de laatste uitlezing) en twee knoppen: **Read** (wit) en **Clear codes**
  (rode rand en rode tekst).
- **Links:** de codelijsten **STORED**, **PENDING** en **PERMANENT**, elk met het aantal rechts in de
  kop. Per code staan de code groot, de omschrijving en (grijs) een opmerking voor deze auto.
  Een lijst die de auto niet kent zegt "Not supported by this car"; een lege lijst "No codes".
  Onderaan staat **VEHICLE** met **VIN** en **ECU**, of "Not reported by this car".
- **Rechts:** **READINESS** met de monitors in twee kolommen ("Ready" wit, "Not ready" amber, "N/A"
  grijs) en een samenvatting zoals "6 OF 7 READY" (N/A telt niet mee), en daaronder **FREEZE FRAME**
  met de waarden op het moment dat de gekozen code is opgeslagen.
- De pagina leest **één keer automatisch** uit zodra je hem opent terwijl de verbinding er is, en
  verder alleen als je **Read** tikt. Nooit in een lus.

## Eerst testen zonder auto (simulator)

De simulator heeft deze foutcodes: **P0171** en **P1349** (stored), **P0300** (pending), geen
permanent-lijst, een freeze frame voor P0171, gemengde monitors (alles klaar behalve Evaporative
system) en geen VIN.

1. Open de app, kies **CONNECTION** > **Simulator** en tik **Connect**.
2. Ga naar **DIAG**. Verwacht: "Reading the car…" met een teller in seconden, daarna de volledige
   pagina. Zie `fase-3-diag.png`:
   - **CHECK ENGINE** staat op **ON** (rood), **STORED CODES** op 2, **LAST READ** toont de tijd.
   - **STORED** toont P0171 ("System Too Lean (Bank 1)") en P1349 ("VVT System Malfunction (Bank
     1)") met een grijze opmerking over de 2ZZ-GE; **PENDING** toont P0300; **PERMANENT** zegt "Not
     supported by this car" met "--" als aantal.
   - **READINESS** zegt "6 OF 7 READY"; **Evaporative system** is amber "Not ready", vier monitors
     zijn "N/A" (grijs).
   - **FREEZE FRAME** (kop met "P0171") toont onder meer RPM 2100, Speed 56, Coolant 85.
   - **VEHICLE**: VIN en ECU zeggen "Not reported by this car".
3. **Freeze frame per code.** Tik op de rij P1349. De rij krijgt de witte balk links en het freeze
   frame maakt plaats voor "No freeze frame for P1349. The car keeps one, stored for P0171." (de auto
   bewaart er maar één). Tik op P0171: de waarden zijn terug. Zie `fase-3-diag-freeze-frame.png`.
4. **Read.** Tik **Read**: de pagina toont weer "Reading the car…", de knoppen zijn grijs en na enkele
   seconden staat de pagina er weer, met een nieuwe tijd bij **LAST READ**.
5. **Wissen met draaiende motor.** De simulatormotor staat standaard aan. Tik **Clear codes**.
   Verwacht een venster **Turn the engine off first** met "Codes can only be cleared with the
   ignition on and the engine off…". Tik **OK**: er is niets gewist.
6. **Motor uit.** Ga naar **CONNECTION** en zet de schakelaar **Simulator engine** uit. Ga terug naar
   **DIAG** en wacht een paar seconden zodat het toerental op 0 staat.
7. **Stap 1 van het wissen.** Tik **Clear codes**. Verwacht het venster **Clear fault codes?** met drie
   blokken: **WHAT HAPPENS** (codes, freeze frame en readiness-monitors worden gewist, de
   check-engine-lamp gaat uit), **AFTERWARDS** (de monitors hebben een rijcyclus nodig, de codes komen
   terug als de fout er nog is) en **REQUIRED** ("Ignition on, engine off."). Knoppen: **Cancel**
   (focus) en **Continue**. Zie `fase-3-clear-step1.png`. Probeer ook **Cancel**: er gebeurt niets.
8. **Stap 2.** Tik opnieuw **Clear codes** en dan **Continue**. Verwacht **Clear codes now?** met een
   rode balk bovenaan, een witte knop **Cancel** (focus) en een rode knop **Clear codes now**. Zie
   `fase-3-clear-step2.png`.
9. **Wissen.** Tik **Clear codes now**. Verwacht "Clearing codes…" en daarna de nieuwe uitlezing, zie
   `fase-3-diag-cleared.png`: **CHECK ENGINE** staat op **OFF**, **STORED CODES** op 0, STORED en
   PENDING zeggen "No codes", de monitors staan op "Not ready" ("0 OF 7 READY"), "No freeze frame
   stored", en bovenaan de codelijst staat "Codes cleared. The readiness monitors start over…".
10. **Terug naar de beginstand.** Tik **CONNECTION** > **Disconnect** en dan **Connect**: de simulator
    begint opnieuw met zijn codes. Op **DIAG** na een verbreking staat "Not connected yet…" met de
    knop **Go to Connection**; na opnieuw verbinden leest de pagina vanzelf weer uit.
11. **Terugkeren naar een pagina onderbreekt het wissen.** Open **Clear codes**, ga met de balk onderaan
    naar een andere pagina en kom terug: het venster is weg en er is niets gewist.

## Testen in de auto

Adapter: Konnwei KW905 (Bluetooth LE). Uitlezen op K-line duurt langer dan in de simulator: reken op
enkele tot tientallen seconden. Neem bij elk probleem het debuglog mee (**Export log** op het
Debug-scherm).

1. **Verbinden.** Zet het contact aan (motor uit), verbind zoals in fase 2 en wacht op **Connected**.
2. **Uitlezen.** Open **DIAG**. Verwacht "Reading the car…" en daarna de pagina. Blijft het bij een
   foutmelding ("The read did not finish…"), controleer het contact en de adapter en tik **Read again**.
3. **Vergelijken met de lamp.** Brandt de check-engine-lamp op het dashboard, dan hoort **CHECK
   ENGINE** op **ON** te staan en moet er minstens één code onder **STORED** staan. Brandt hij niet,
   dan hoort er geen code bij **STORED** te staan (pending kan wel; die laat de lamp nog niet branden).
   Noteer afwijkingen.
4. **Codes lezen.** Zoek elke code op in de lijst. Staat er een code zonder omschrijving, dan kent de
   offline lijst hem niet; noteer de code.
5. **Freeze frame.** Tik de opgeslagen codes aan en kijk of de waarden (toerental, snelheid,
   koelwater, belasting, fuel trims) passen bij een rit waarin de fout optrad.
6. **Readiness.** Noteer welke monitors "Ready", "Not ready" en "N/A" zijn.
7. **VIN en ECU.** Een auto van dit bouwjaar meldt soms geen VIN; "Not reported by this car" is dan
   normaal.
8. **Wissen met draaiende motor.** Start de motor en tik **Clear codes**: de app moet **Turn the
   engine off first** zeggen en niets wissen.
9. **Wissen, alleen als je dat echt wilt.** Motor uit, contact aan, tik **Clear codes**, lees beide
   vensters en bevestig pas bij **Clear codes now**. Verwacht "Codes cleared…". Komt de fout terug, dan
   staat hij na de eerstvolgende rit weer bij **STORED** of **PENDING**.
10. **Geweigerd.** Weigert de auto het wissen, dan zegt de pagina "Codes not cleared." met de reden;
    de codes staan er nog. Er is geen automatische tweede poging.

**Let op bij wissen:** het wissen zet ook alle readiness-monitors terug naar "Not ready". Ze worden
pas weer klaar na een of meer rijcycli. Een APK-keuring (en elke emissietest) kan in die toestand
afgekeurd worden. **Wis dus niet vlak voor de APK**, en wis nooit alleen om de lamp uit te krijgen.

## Wat is er nieuw ten opzichte van fase 2

- Pagina **DIAG** in de balk onderaan (nu vijf knoppen: DASH, SENSORS, DIAG, CONNECTION, DEBUG; de
  status staat nog rechts).
- Foutcodes met offline omschrijving, readiness-monitors, freeze frame, VIN en ECU-naam.
- Wissen alleen na twee bevestigingen, alleen met het contact aan en de motor uit (de app
  controleert het toerental, ook nog eens bij de laatste bevestiging).

## Bekende beperkingen fase 3

- Permanente codes (service 0A) zijn pas verplicht vanaf ongeveer 2010; deze auto zegt vrijwel zeker
  "Not supported by this car".
- Het freeze frame is er één per auto; het hoort bij de code die het heeft veroorzaakt.
- De omschrijvingen zijn een offline lijst en niet volledig: een onbekende code wordt zonder tekst getoond.
- Het uitlezen blokkeert het live pollen kort; het dashboard staat daarna weer op de normale
  verversing. Tijdens een uitlezing zijn de knoppen grijs.
- Een verbroken verbinding wist wat op de DIAG-pagina stond: gegevens van een oude verbinding blijven
  niet staan.
- Geen opslag of export van uitleesresultaten (fase 4 en later).
