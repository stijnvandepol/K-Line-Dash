# Fase 6: installeren en testen

Fase 6 voegt **GPS**, **rondentijden**, een **ritdetail** en **export voor de pc** toe, en laat de app werken met
de gangbare Bluetooth-OBD-adapters en met CAN-auto's. Nieuw in de app:

- **GPS-teken in de onderbalk:** "GPS" naast de verbindingsstatus. Groen met het aantal satellieten bij een
  fix, grijs terwijl hij zoekt, doorgestreept als locatie uit staat of niet is toegestaan. Tikken opent
  **SETUP > GPS & Tracks**.
- **SETUP > GPS & Tracks:** de toestand van de GPS, de toestemming, de gesimuleerde ronde voor tests en de
  lijst met opgeslagen circuits (hernoemen, verwijderen, kiezen).
- **TRIPS** is vernieuwd: links de **sessie** (rit starten, rondensessie starten, live cijfers en rondetijd),
  rechts de ritlijst met circuit, aantal ronden en beste ronde. Een tik op een rit opent het **ritdetail**.
- **Ritdetail:** route op een kaart zonder kaartlagen, statistieken, rondetabel, sectortijden en een
  snelheids- en toerentalgrafiek per ronde, plus **Export for PC** (VBO, GPX, CSV).
- **Adapters:** zie "Adapters" hieronder.

De app is volledig Engelstalig; deze handleiding blijft Nederlands en noemt de schermteksten zoals ze in de
app staan. Screenshots van de emulator staan in `docs/screenshots/fase-6-*.png`.

## APK bouwen en installeren

Hetzelfde als in fase 1 tot en met 5 (zie `docs/fase-1-installeren-en-testen.md`):

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

## Adapters

De app spreekt gewone ELM327-tekst, dus elke adapter die met algemene apps (Torque, Car Scanner, OBD Fusion)
werkt, hoort ook hier te werken. De lijst staat in `README.md` ("Supported adapters"), de achtergrond in
`docs/research/popular-obd-adapters.md`.

- **Werkt goed:** OBDLink LX, MX+ en CX (de CX is alleen BLE), Vgate vLinker MC+/BM+/FS/MS, Vgate iCar Pro 2S,
  Veepeak OBDCheck BLE+ en VP11. Vooral voor auto's met CAN (vanaf ongeveer 2008): de app vraagt dan
  meerdere waarden in één verzoek.
- **Werkt, met kanttekeningen:** de goedkope ELM327-"v1.5"- en "v2.1"-klonen (Konnwei KW902/KW905, Viecar,
  BAFX, Panlong, Vgate iCar2 en iCar Pro BLE). Sommige klonen antwoorden `?` op optionele opdrachten; de app
  slaat die over. Koppel Bluetooth Classic-adapters eerst in de systeeminstellingen (PIN 1234, 0000 of 6789)
  en druk zo nodig op de knop van de adapter. De KW905 werkt alleen via BLE.
- **Werkt niet (vergrendeld):** dongles die alleen met hun eigen app praten: BlueDriver, ThinkDiag, Launch
  Golo/EasyDiag, TOPDON TopScan, ANCEL BD200 en Autel MaxiAP. De app meldt dan dat de adapter geen standaard
  OBD-opdrachten beantwoordt en laat onder **SETUP > Connection** de lijst "Adapters that work" zien. In de
  apparatenlijsten krijgen bekende vergrendelde namen het label **May not work**; ze blijven kiesbaar.
  FIXD is niet geverifieerd.

Een adapter is voor de GPS niet nodig: een rit starten kan ook zonder adapter, zodra er een GPS-fix is
(telefoongebruik). De OBD-kolommen in het bestand blijven dan leeg.

## GPS en toestemming

De app vraagt pas om locatie als jij erom vraagt: open **SETUP > GPS & Tracks** (of tik op het GPS-teken
onderin). Staat er **Status: Not allowed**, tik dan **ALLOW LOCATION** en kies in het systeemvenster
**Precise** en **While using the app**. Daarna springt de status naar **Searching** en, met een vrij zicht op
de lucht, na een tot enkele minuten naar **Fix**.

De rijen onder **GPS**:

- **Status:** **Fix**, **Searching**, **Starting** (de GPS start zodra de app in beeld is), **Not allowed** of
  **Off** (locatie staat uit in de systeeminstellingen, of de unit heeft geen GPS).
- **Satellites**, **Accuracy** (in meter, 68 %-straal) en **Fix rate** (gemeten fixes per seconde).
  Een gewone telefoon of head unit geeft 1 per seconde; zie bij de in-car-tests wat jouw unit doet.

De GPS draait alleen als de app in beeld is, en daarna nog twee minuten; **tijdens een opname blijft hij
doorlopen**. Dat komt door Android: een achtergronddienst mag alleen locatie gebruiken als de app zichtbaar was
toen de dienst dat type kreeg.

## De TRIPS-pagina

Links de sessie, rechts de ritlijst. De sessie heeft drie toestanden.

### Niet aan het opnemen

Zie `fase-6-trips-lap-session.png` voor de rondensessie en `fase-6-trips-recording.png` voor een gewone rit.

- **START TRIP** (wit): een gewone rit. Hij werkt met een adapter of met een GPS-fix. Zonder beide staat
  eronder waarom ("Not connected and no GPS fix. Connect under Setup, Connection, or wait for the GPS.").
- **START LAP SESSION** (omlijnd): een rit waarbij de rondetijd loopt. Daaronder staat **TRACK: ...** met het
  circuit dat gebruikt wordt (het laatst gebruikte, of **Choose a track**). Tik erop om een ander opgeslagen
  circuit te kiezen.
- **Record automatically:** **When the engine runs** (zoals in fase 4) en **When the car moves (no OBD
  needed)**. De tweede werkt voor een telefoon zonder adapter: een rit start als de GPS-snelheid vijf
  seconden boven 10 km/h blijft en eindigt twee minuten nadat je stilstaat. Hij hoort bij de eerste schakelaar
  en staat grijs als die uit is.
- Daaronder de **Export**-knoppen van fase 4 (**Copy to USB or SD card**, **Start Wi-Fi export**). De kopie
  en de Wi-Fi-export nemen ook de PC-bestanden mee die je met **Export for PC** hebt gemaakt.

### Een rit opnemen

Een gewone rit toont 2 x 3 velden: **TIME**, **DISTANCE**, **AVG KM/H**, **MAX KM/H**, **MAX RPM** en **FUEL**
(een schatting uit de luchtmassa, "L est."; de rit vraagt zelf de luchtmassa en de gaspedaalstand op en schrijft
ze als kolommen `maf (g/s)` en `throttle (%)` in het ritbestand). Onder de velden **STOP** en **START LAP TIMING**: dat laatste maakt
van de lopende rit een rondensessie, waarna je de startlijn zet.

### Een rondensessie

Zie `fase-6-trips-lap-session.png`. Eerst, zolang er geen start/finish-lijn is:

- **Set start/finish here** met de tekst "Drive across the line at a steady speed and tap when you pass it."
  De knop werkt alleen met een GPS-fix van **15 m of beter** en terwijl je **7 km/h of sneller** rijdt (de app
  moet je rijrichting kennen). Anders staat er waarom onder de knop, bijvoorbeeld "The GPS position is not
  accurate enough (22 m, needs 15 m or better)."

Daarna het rondeklokje:

- **CURRENT LAP** groot (m:ss.s), daaronder **LAST** en **BEST** (m:ss.sss), **DELTA** (verschil met de beste
  ronde op dezelfde plek, **groen** en met min als je voor ligt, **rood** en met plus als je achter ligt) en
  **LAPS** (voltooide ronden).
- Eronder de **sectortijden** van de lopende ronde (**S1**, **S2**, ...). Een sectorlijn zet je met
  **ADD SECTOR HERE**, op dezelfde manier als de startlijn; de klok loopt daarbij gewoon door. Na het
  zetten van een nieuwe startlijn begint de klok opnieuw: het moment waarop je de lijn zet (je tikt terwijl je
  er overheen rijdt) is meteen de start van ronde 1, en de klok toont direct een lopende ronde.
- **STOP** beëindigt de rit en daarmee de sessie; het circuit blijft bewaard als "laatst gebruikt".

Hoe de ronden geteld worden (zoals in de kern, zie `docs/research/gps-laps-export.md`): de lijn telt alleen in de
richting waarin je hem zette; een ronde korter dan 10 seconden (of korter dan de helft van de beste) telt niet;
na een passage moet je 50 m bij de lijn vandaan zijn voor de volgende telt; fixes slechter dan 20 m worden
genegeerd; een GPS-onderbreking van meer dan 3 seconden maakt die ronde **ongeldig** (in de tabel **NO GPS**,
nooit de beste). De ronden worden niet opgeslagen maar steeds uit het ritbestand berekend, dus een gewijzigde
lijn herberekent oude ritten. Alleen de rondensessie telt: bij een lijn die midden in een rit wordt gezet bewaart
de rit het moment van de laatste meting ervoor, en passeringen van die plek daarvóór (een opwarmronde, of
rijden langs de plek voordat je de sessie startte) geven geen ronden. Dat geldt voor de live klok, de ritlijst,
het ritdetail en de exports. Ritten van vóór deze regel hebben dat moment niet en tellen vanaf het begin. Zet je
midden in een rit een andere lijn, dan telt de nieuwe sessie vanaf dat moment en vervalt de vorige.

### De ritlijst

Nieuwste eerst: datum, duur en afstand, en voor een rondensessie eronder het circuit, het aantal ronden en de
beste ronde ("Simulated circuit, 7 laps, best 1:57.110"). Een tik op een rij opent het ritdetail. **DELETE**
vraagt één keer bevestiging. De rit die nu opneemt toont **RECORDING** en kan niet geopend of verwijderd worden.

## Het ritdetail

Zie `fase-6-trip-detail.png` en `fase-6-trip-detail-stats.png`.

- **Bovenste strook:** **BACK**, de datum en het circuit, **EXPORT FOR PC** en **DELETE** (met bevestiging).
- **Links:** de route als lijn op een grijs vlak, noord boven, zonder kaartlagen (de app werkt offline). De start/finish-
  en sectorlijnen zijn korte balken in de accentkleur. Is een ronde gekozen, dan is haar deel van de route in
  de accentkleur en de rest dim. Heeft de rit geen GPS-data, dan staat er "This trip has no GPS data, so there
  is no route."
- **Rechts, boven:** de statistieken: **DURATION**, **DISTANCE**, **MOVING TIME**, **AVG SPEED**, **AVG MOVING**,
  **MAX SPEED**, **MAX RPM**, **MAX COOLANT**, **FULL THROTTLE** (aandeel van de tijd met gas boven 90 %) en de
  brandstofschatting. Wat niet gemeten is, staat als `--`, nooit als 0 (zonder adapter is het toerental dus `--`).
- **Rondetabel** (alleen met een circuit): ronde, tijd, verschil met de beste, hoogste snelheid en toerental,
  full-throttle-percentage en een markering: **BEST** in groen, **NO GPS** voor een ongeldige ronde. Een tik op een rij
  kiest de ronde; de beste ronde is bij het openen gekozen. Onder de tabel staan de sectortijden van die ronde
  en twee grafieken, **KM/H** en **RPM** over de rondetijd, in dezelfde stijl als de grafiek-cel op het dashboard.
  De pagina scrolt.
- Zonder circuit staat er "No track was set for this trip, so there are no laps."

## Export voor de pc

Tik in het ritdetail op **EXPORT FOR PC** en kies een formaat. De app schrijft het bestand naast het
ritbestand en toont de bestandsnamen:

| Cel | Bestand | Inhoud | Openen met |
|---|---|---|---|
| **VBO** | `trip-JJJJMMDD-UUMMSS.vbo` | Racelogic-formaat met GPS en alle OBD-kanalen, plus de ronden | Racelogic **Circuit Tools** (gratis, Windows), **RaceChrono** (telefoon), RaceRender |
| **GPX** | `trip-....gpx` | De route met snelheid en richting (GPX 1.1) | **GPS Visualizer** (website), **Google Earth Pro** (Bestand > Openen), elke kaart- of fietsapp |
| **CSV** | `trip-....pc.csv` en `trip-....laps.csv` | De hele rit met een `lap`-kolom, en één rij per ronde met tijden, sectoren en statistieken | **Excel**, LibreOffice, Python |

Een formaat zonder gegevens wordt overgeslagen met een reden eronder ("This trip has no GPS data.", "No track
was set for this trip, so there are no laps."). De bestanden worden steeds opnieuw gemaakt, want een gewijzigde
lijn geeft andere ronden; ze verdwijnen ook samen met de rit. Ze komen op dezelfde manier van de head unit af
als de ritbestanden: **Start Wi-Fi export** (ze staan in de lijst en in de ZIP) of **Copy to USB or SD card**.

Excel (zoals in fase 4): het bestand is UTF-8 met BOM, komma-gescheiden, punt als decimaalteken. Op een
Nederlandse Excel: **Gegevens > Uit tekst/CSV**, bestandsoorsprong **UTF-8**, scheidingsteken **komma**, en
bij laden decimaal **Engels**.

**Let op:** de VBO is volgens de specificatie geschreven maar nog niet met Circuit Tools en RaceChrono zelf
gecontroleerd. Dat is een van de testen hieronder; meld wat er gebeurt.

## SETUP > GPS & Tracks

Zie `fase-6-setup-gps-tracks.png` en `fase-6-gps-status.png` (de drie toestanden van het GPS-teken).

- Bovenin de GPS-rijen en, als de toestemming ontbreekt, **ALLOW LOCATION**.
- **Use the simulated circuit** staat er alleen bij de **Simulator**-verbinding. De gesimuleerde auto rijdt dan
  een stadionvormig circuit van 2 km (twee rechte stukken en twee halve cirkels) met de snelheid van de
  simulator, zodat je ronden kunt testen zonder auto. Zonder deze schakelaar geldt ook bij de simulator
  gewoon de GPS van het apparaat.
- **Saved tracks:** per circuit de naam, wat erop staat ("Start/finish and 1 sector line") en het aantal sessies, en
  drie cellen:
  - **USE** kiest het circuit voor de volgende rondensessie; **ACTIVE** (zo lang een sessie loopt) zet de
    rondetijd weer uit. Is een circuit actief, dan krijgt elke rit die begint (ook een automatische) rondetijden.
    Een rondensessie geeft het circuit bij het einde van de rit weer vrij.
  - **RENAME** opent een tekstveld. Het toetsenbord is bij het rijden onhandig, dus de knop is **grijs terwijl de
    auto rijdt** (GPS-snelheid boven ongeveer 3,6 km/h).
  - **DELETE** vraagt bevestiging: "Delete track ...? Its trips are kept but lose their laps." De ritten blijven,
    maar hun ronden en de gegenereerde PC-bestanden verdwijnen.

## Eerst testen zonder auto (emulator)

De emulator "headunit" (1280 x 720) en de simulator zijn genoeg voor bijna alles. Op Windows werkt
`adb` zo vanuit Git Bash: `export MSYS_NO_PATHCONV=1`, anders worden paden als `/sdcard/...` herschreven.

1. **Verbinden.** **SETUP > Connection**, kies **Simulator**, tik **Connect**. De simulatormotor draait, dus de
   app begint zelf een rit (zie fase 4).
2. **Toestemming.** Tik onderin op **GPS** (doorgestreept): **SETUP > GPS & Tracks** opent met **Not allowed**.
   Tik **ALLOW LOCATION**, kies **While using the app**. Verwacht **Searching**: de emulator geeft zelf geen fix.
3. **Gesimuleerde ronde.** Zet **Use the simulated circuit** aan. Binnen een paar seconden: **Fix**, 12
   satellieten, nauwkeurigheid 3 m, ongeveer 5 fixes per seconde, en onderin een groen **GPS 12**.
4. **Rondensessie.** Tik **STOP** op TRIPS (als er een automatische rit loopt) en daarna **START LAP SESSION**.
   Het scherm toont **Lap session**, "No start/finish line yet" en **SET START/FINISH HERE**. De simulatorauto
   staat soms stil (de rit heeft rustmomenten); de knop werkt alleen als hij rijdt: staat er "The driving
   direction is not known...", wacht dan een paar seconden en probeer opnieuw. Tik de knop: er staat "Start/finish
   line set. Lap 1 starts when you cross it." en het circuit krijgt de naam "Circuit 22 Oct 10:21".
5. **Ronden.** Wacht tot de auto de lijn passeert: **CURRENT LAP** loopt. Een ronde van de simulator duurt ongeveer
   twee minuten (de auto trekt op, remt en staat stil). Na de eerste ronde staan **LAST**, **BEST** en **DELTA** er; de delta wordt
   groen als je op dezelfde plek eerder bent dan je beste ronde, rood als je later bent. Laat minstens
   drie ronden lopen.
6. **Sector.** Tik tijdens een ronde, terwijl de auto rijdt, **ADD SECTOR HERE**: "Sector line added." De klok
   loopt door (hij begint niet opnieuw) en onder de cijfers staat **S1 --**; na de volgende passage verschijnt de
   sectortijd.
7. **Stop.** Tik **STOP**. In de lijst staat de rit met circuit, ronden en beste ronde. Onder **SETUP > GPS & Tracks**
   staat **USE** weer bij het circuit.
8. **Hernoemen.** Wacht tot de auto stilstaat (de knop **RENAME** wordt wit), tik hem, typ een naam en
   **SAVE**. De naam staat meteen in de lijst en in de ritten.
9. **Ritdetail.** Tik op de rit. Controleer: route met twee balken (start/finish en sector), de rondetabel met
   **BEST** in groen, de sectortijden onder de tabel, de twee grafieken. Kies een andere ronde: de sectoren en de
   grafieken wisselen mee.
10. **Export.** Tik **EXPORT FOR PC** en daarna **VBO**, **GPX** en **CSV** (wacht tussen de tikken tot de
    bestandsnamen verschijnen). Controleer op de emulator:
    ```bash
    adb shell ls /sdcard/Android/data/nl.stijnvdpol.klinedash/files/trips
    ```
    Verwacht naast `trip-....csv` ook `.vbo`, `.gpx`, `.pc.csv` en `.laps.csv`.
11. **Wi-Fi-export.** Tik op TRIPS **Start Wi-Fi export** en noteer de PIN. Op de pc:
    ```bash
    adb forward tcp:8080 tcp:8080
    curl -c jar.txt -d "pin=1234" http://127.0.0.1:8080/login
    curl -b jar.txt http://127.0.0.1:8080/
    ```
    (vervang 1234 door de PIN). De lijst moet de vier PC-bestanden van de rit tonen en `/download/<nummer>/<bestandsnaam>` (de links op de pagina) geeft
    ze. De `.laps.csv` heeft de kolommen `lap,valid,start_iso,lap_time (s),delta_best (s),distance (km), ...,
    sector_1 (s), sector_2 (s)`.
12. **Verwijderen.** Tik in het ritdetail **DELETE**, lees de vraag, tik **CANCEL**; doe het nog eens en bevestig:
    de rit en alle bestanden zijn weg en de lijst staat weer voor je.
13. **Zonder toestemming.** Haal de toestemming weg (`adb shell pm revoke nl.stijnvdpol.klinedash
    android.permission.ACCESS_FINE_LOCATION`) en open de app weer: het GPS-teken is doorgestreept, **Set start/finish
    here** staat grijs met "Location is not allowed. Allow it under Setup, GPS & Tracks.".

De GPS van de emulator zelf (`adb emu geo fix <lengtegraad> <breedtegraad>`) is alleen geschikt om te zien dat de
status naar **Fix** gaat: een vaste positie heeft geen richting of snelheid, dus de startlijn kan er niet mee
gezet worden. Gebruik daarvoor de gesimuleerde ronde.

## Testen in de auto

Neem bij elk probleem het debuglog mee (**SETUP > Debug > Export log**) en noteer wat de unit doet.

### Voorbereiding

1. Installeer de app, open hem één keer met de hand en geef toestemming voor **Nearby devices** en meldingen
   (zoals in fase 5).
2. Open **SETUP > GPS & Tracks** en tik **ALLOW LOCATION**. Noteer of de unit een systeemvenster toont. Heeft de
   unit geen venster voor locatie, dan staat er **Off** of **Not allowed**: noteer dat.
3. Zet de auto buiten op een open plek en wacht op **Fix**. Noteer na hoeveel tijd, het aantal satellieten, de
   **Accuracy** (15 m of beter is nodig voor een lijn) en de **Fix rate**: 1,0 is normaal; hoger is meegenomen
   winst.

### Tests

1. **GPS-teken.** Het teken onderin is groen met het aantal satellieten bij een fix, grijs bij zoeken. Schakel
   locatie in de systeeminstellingen uit: **Off**, doorgestreept; weer aan: terug naar **Searching**/**Fix**.
2. **Gewone rit met GPS.** Verbind met de adapter, rij een stuk en kijk bij het ritdetail naar de route. Klopt de
   vorm met wat je reed? Klopt de afstand met de kilometerteller (binnen een paar procent)? Staat **MAX SPEED**
   dicht bij wat je zag?
3. **Op het circuit (trackday).** Ga naar **TRIPS > START LAP SESSION**. Rij het circuit op en zet de lijn terwijl je
   op een rechte stuk met **stabiele snelheid** (ruim boven 7 km/h) over de start/finish rijdt: tik **SET
   START/FINISH HERE** op het moment dat je erdoor gaat, of laat een bijrijder tikken. Wacht niet tot je stilstaat:
   de lijn gaat door je positie in je rijrichting en staat 30 m breed. Werkt de knop niet, lees dan de reden
   (nauwkeurigheid onder 15 m nodig). Rij drie ronden en vergelijk de **LAST**-tijd met een stopwatch op de
   telefoon: een afwijking tot ongeveer 0,2 seconde per ronde is bij 1 fix per seconde normaal, en de **verschillen
   tussen ronden** zijn nauwkeuriger dan de tijden zelf. Controleer ook of een ronde meetelt als je de lijn
   breed neemt (de lijn is 30 m breed; rij je ernaast, dan mist de ronde).
4. **Sectoren.** Zet onderweg een of twee sectorlijnen met **ADD SECTOR HERE** (ook weer rijdend). Controleer de
   sectortijden in het ritdetail en dat ze optellen tot de rondetijd.
5. **Verschillende snelheid.** Rij een langzame ronde en een snelle: de **DELTA** moet tijdens de ronde logisch
   meelopen (groen als je voor ligt).
6. **Auto-start door beweging (telefoon).** Zet op een telefoon zonder adapter **Record automatically > When
   the engine runs** en **When the car moves** aan, en rij weg: na ongeveer vijf seconden boven 10 km/h moet er
   een rit starten, en twee minuten na stilstaan eindigen. Het toerental staat in zo'n rit als `--`.
7. **Ritdetail.** Open de rit en controleer de statistieken, de rondetabel (is de beste ronde de snelste?) en de
   grafieken (valt de snelheidslijn samen met wat je reed: aanzetten, remmen?).
8. **Export voor pc.** Tik **EXPORT FOR PC** en maak **VBO**, **GPX** en **CSV**. Haal de bestanden op via de
   Wi-Fi-export (hotspot van je telefoon, zoals in fase 4) of een USB-stick. Op de pc:
   - **VBO:** open `.vbo` in **Racelogic Circuit Tools** en/of importeer hem in **RaceChrono**. Noteer of hij opent,
     of de route klopt, welke kanalen (rpm, speed, coolant) je ziet en of de ronden er staan. Dit is de test die
     nog openstaat.
   - **GPX:** upload `.gpx` op gpsvisualizer.com (kaart of profiel) of open hem in **Google Earth Pro**. Noteer of de
     route de weg volgt en of de snelheid erbij staat.
   - **CSV:** open `.pc.csv` en `.laps.csv` in **Excel** (zie hierboven voor de import). Controleer de `lap`-kolom, dat de
     `laps.csv` één rij per ronde heeft en of de tijden kloppen met de app.
9. **Adapters.** Probeer, als je er meer dan één hebt, ook de andere (zie "Adapters"): verbindt hij, hoe snel is
   het verzoektempo (rechtsonder), en meldt de app bij een vergrendelde dongle de juiste reden?
10. **Contact uit.** Zet het contact uit terwijl een rondensessie loopt. De rit eindigt netjes, het bestand is
    compleet en de ronden staan in het detail.

## Wat is er nieuw ten opzichte van fase 5

- Een GPS-teken in de onderbalk en een nieuwe sectie **SETUP > GPS & Tracks** (de menu-sectie staat tussen **Startup** en **Debug**).
- **TRIPS** heeft een nieuwe indeling: sessie links (rit, rondensessie, live cijfers), ritlijst rechts. De knop
  **Record** heet nu **START TRIP**; de lijst toont geen **Max RPM** meer (dat staat in het ritdetail).
- De schakelaar **Record automatically when the engine runs** is nu een kopje **Record automatically** met twee
  schakelaars: **When the engine runs** en **When the car moves (no OBD needed)**.
- De samenvatting na **Copy to USB or SD card** telt nu **bestanden** ("Copied 3 files"): de PC-bestanden gaan mee.
- Ritdetail, rondentijden, export voor de pc (VBO, GPX, CSV).
- Een nieuwe rit bovenaan de lijst springt in beeld (de lijst scrolt naar boven, tenzij je zelf naar beneden gescrold hebt).

## Bekende beperkingen fase 6

- Een rit start zonder adapter alleen met een GPS-fix; zonder beide meldt de app waarom.
- Een nieuwe startlijn tijdens een rit zet de live rondeklok terug naar nul (de eerstvolgende passage van de startlijn
  begint ronde 1); een toegevoegde sectorlijn niet. In het ritdetail zijn de ronden opnieuw uit het hele bestand
  berekend en staan de eerdere ronden er dus bij.
- Een circuit kiezen tijdens een gewone rit kan alleen via **USE** onder **SETUP > GPS & Tracks**; de rondeklok begint dan
  op dat moment. Bij het starten van een rondensessie gebruik je de keuze bij **TRACK**.
- Een ritdetail toont de route en de grafiek van één ronde per keer; er is geen vergelijking van twee ronden.
- De VBO is nog niet met Circuit Tools en RaceChrono gecontroleerd (zie de test hierboven). Het GPS-teken en de
  rondetijden hangen af van de GPS van de unit: bij 1 fix per seconde is een rondetijd ongeveer 0,2 seconde onnauwkeurig.
- Schakel je tijdens een rit tussen de gesimuleerde ronde en de GPS van het apparaat, dan telt de sprong in positie
  niet als afstand mee, en de ronde die op dat moment liep vervalt (de volgende passage van de startlijn begint een
  nieuwe ronde). Een losse foute GPS-positie (een stap die meer dan 300 km/h, of meer dan drie keer de gemeten
  snelheid, zou betekenen) of een positie met een nauwkeurigheid slechter dan 20 m telt ook niet mee als afstand; een
  ronde waarin zo'n sprong zit staat in het ritdetail als **NO GPS**.
