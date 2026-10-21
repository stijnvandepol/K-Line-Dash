# Fase 2: installeren en testen

Fase 2 voegt het echte dashboard toe: de poller die de sensoren uitleest, de **DASH**-pagina met
toerentalbalk, drie grote velden (RPM, KM/H, WATER) en zes kleine velden, de **SENSORS**-pagina met
alle sensoren die de auto ondersteunt, en het race-uiterlijk (zie `docs/design-system.md`). De app
is volledig Engelstalig; deze handleiding blijft Nederlands en noemt de schermteksten zoals ze in
de app staan.

Screenshots van de simulator staan in `docs/screenshots/fase-2-*.png`.

## APK bouwen en installeren

Hetzelfde als in fase 1 (zie `docs/fase-1-installeren-en-testen.md`):

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Of kopieer `app/build/outputs/apk/debug/app-debug.apk` naar een USB-stick en open hem op de head unit.

Controle vooraf op de pc (alle drie moeten slagen):

```bash
./gradlew :obd-core:test :app:testDebugUnitTest :app:lintDebug
```

## Eerst testen zonder auto (simulator)

1. Open de app, kies **Connection** > **Simulator** en tik **Connect**.
2. Ga naar **DASH**. Verwacht na een paar seconden: rechtsonder in de balk staat een groene stip met
   het protocol en een getal zoals "5.2/s"; de segmenten van de toerentalbalk lichten tot rond 800
   tot 1 000 rpm op en de balk beweegt vloeiend, niet in stappen; de zes kleine velden vullen zich
   (INTAKE, THROTTLE, LOAD, IGN, BATT, MAF). Tot de eerste waarde er is staat er "--" in dezelfde plek.
   Het simulatorverloop komt ook boven 6 200 rpm: die segmenten zijn amber, vanaf 7 800 rood.
3. Ga naar **SENSORS**. Alle sensoren van de simulator staan in een tabel, op een breed scherm in twee
   helften naast elkaar: naam links, waarde rechts (cijfers staan onder elkaar), eenheid grijs erachter
   en rechts hoe vaak de waarde per seconde binnenkomt. Snelle signalen (Engine speed, Vehicle speed, Throttle position) ververst de poller
   vaak, traag veranderende (Coolant temperature, fuel trims) af en toe. Een signaal dat nog niet is
   uitgelezen toont "--".
4. Ga terug naar **CONNECTION** en tik **Disconnect**. Open **DASH**: de status rechtsonder wordt
   **Not connected** met een grijze stip en de waarden worden donkergrijs (oud, niet weg). **SENSORS**
   zegt "Not connected yet. Connect under Connection." met een knop **Go to Connection**.
5. Verbind weer. Zet bij **Connection** (Simulator gekozen) de schakelaar **Simulator engine** uit en
   wacht ongeveer 3 minuten. Verwacht op het dashboard: **Engine off, saving power** in de balk
   onderaan en RPM op 0. Zet de motor weer aan: na enkele seconden is alles terug.
6. Open **DEBUG**: met de motor aan zie je afwisselend `010C1`, `010D1`, `0111` enzovoort langskomen.
   Verlaat je DASH en SENSORS (bijvoorbeeld naar Debug of Connection), dan stopt
   het pollen van die signalen; alleen het toerental blijft af en toe gecontroleerd worden.

## Testen in de auto

Adapter: Konnwei KW905 (Bluetooth LE). Neem bij elk probleem het debuglog mee (**Export log** op het
Debug-scherm).

1. **Adapter aanzetten.** Steek de adapter in de OBD-poort en zet het contact aan (motor mag uit).
   Druk op de knop van de KW905 totdat het lampje brandt. Zonder brandend lampje is hij niet te vinden.
2. **Kiezen.** **Connection** > **Bluetooth LE** > **Scan**. Kies de KW905 uit de lijst (de rij
   krijgt een witte balk links). Tik **Connect**. Verwacht binnen ongeveer 10 tot 20 s: **Connected**
   met protocol (verwacht ISO 9141-2) en de accuspanning.
3. **Sensorlijst controleren.** Open **SENSORS**. De auto meldt eerst welke sensoren er zijn
   ("The car is still reporting which sensors it has…"). Verwacht daarna de ondersteunde PID's
   `01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 14 15 1C 20 21`, zichtbaar als deze 18 regels:
   Engine speed, Vehicle speed, Throttle position, Coolant temperature, Intake air temperature,
   Calculated engine load, Timing advance, Mass air flow, Short term fuel trim bank 1, Long term fuel
   trim bank 1, O2 sensor B1S1 voltage, O2 sensor B1S1 fuel trim, O2 sensor B1S2 voltage, O2 sensor
   B1S2 fuel trim, Malfunction indicator lamp, Stored fault code count, Distance with MIL on en
   Battery voltage. (PID 03, 12, 13, 1C en 20 hebben geen eigen regel.) Staat er een regel te veel of
   te weinig, exporteer dan het log: de lijst komt uit de 0100/0120-antwoorden.
4. **Stationair toerental.** Start de motor. Verwacht op **DASH** ongeveer 830 rpm (vijf
   segmenten branden, net onder de 1 op de schaal) en snelheid 0 km/h.
5. **Verversingssnelheid.** Rechtsonder in de balk staat het gemeten aantal verzoeken per seconde
   (verwacht ongeveer 2/s op K-line). Op **Sensors** zie je per sensor hoe vaak hij ververst:
   omdat de snelle signalen (rpm, speed, throttle) samen één verzoekronde delen, ligt elk daarvan
   rond de 0.4 tot 0.5 per sec. Noteer je cijfers; zo kunnen we de verdeling afstellen.
6. **Soepelheid.** Geef rustig gas. De balk moet doorlopend meelopen, zonder terugspringen, en
   het grote RPM-getal hoort niet te verspringen (alleen de laatste cijfers veranderen). Bij het
   passeren van 1 000 rpm komt er een cijfer bij: dat is normaal, het getal staat rechts uitgelijnd.
7. **Koelwater.** Bij een koude motor staat **WATER** laag en loopt hij tijdens het rijden op
   richting 85 tot 95 °C; vanaf 100 °C wordt het getal amber, vanaf 105 °C rood. Een traag signaal kan tot 15 s oud zijn voordat het grijs wordt.
8. **Grijs worden.** Trek de adapter uit de OBD-poort (of zet het contact uit): na
   ongeveer 5 s worden toerental en snelheid donkergrijs, de status gaat naar **Reconnecting**. De
   waarden blijven staan; er verspringt niets.
9. **Motor uit, spaarstand.** Zet de motor uit maar laat het contact aan. Na ongeveer 3 minuten
   zonder toerental verschijnt **Engine off, saving power**; de app vraagt dan nog maar eens per 5 s
   het toerental. Start de motor: het pollen hervat vanzelf.
10. **Pagina's verlaten.** Ga naar **DEBUG**. Verwacht dat de verzoeken voor de velden stoppen
    (alleen nog een rpm-controle). Terug op **DASH** hervatten ze.

## Wat is er nieuw ten opzichte van fase 1

- Dashboard, Sensors en het race-uiterlijk (thema "Race": zwart, witte cijfers, kleur alleen voor
  waarschuwingen); de navigatie is een balk onderaan met vier knoppen (DASH, SENSORS, CONNECTION,
  DEBUG) en rechts de verbindingsstatus.
- Het simulatormotor-schakelaartje en de adapterdetails (ELM-versie, protocol) zijn verhuisd:
  de schakelaar naar **Connection**, de details naar **Debug**.
- Alle schermteksten en foutmeldingen zijn Engels; getallen gebruiken een decimale punt.

## Bekende beperkingen fase 2

- Waarden worden grijs na 5 s (snelle signalen), 15 s (middel) of 45 s (trage), niet overal na 5 s:
  de poller ververst die signalen maar om de 3 s en 15 s.
- Nog geen waarschuwingen, foutcodes of loggen van ritten (fase 3 en later).
- Geen autostart na ACC of boot (fase 5).
