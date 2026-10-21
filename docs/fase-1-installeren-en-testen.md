# Fase 1: installeren en testen

Fase 1 bevat de verbinding met de adapter (Bluetooth Classic, BLE, wifi, simulator), de ELM327-init
met protocoldetectie en het debugscherm met de ruwe communicatie. Er zijn nog geen gauges: het
dashboard toont alleen de verbindingsstatus.

## APK bouwen

```bash
./gradlew :app:assembleDebug
```

De APK staat daarna in `app/build/outputs/apk/debug/app-debug.apk`.

## Installeren op de head unit

### Optie A: USB-stick (geen pc in de auto nodig)

1. Kopieer `app-debug.apk` naar een USB-stick.
2. Steek de stick in de head unit en open de APK met de bestandsbeheerder van de unit.
3. Sta "installeren uit onbekende bronnen" toe als daarom gevraagd wordt.

### Optie B: adb over wifi (handig bij vaak updaten)

1. Head unit: Instellingen > Over het apparaat > tik 7× op "Buildnummer" om ontwikkelaarsopties aan
   te zetten. Zet daarna in Ontwikkelaarsopties "USB-foutopsporing" en (indien aanwezig)
   "Draadloze foutopsporing" aan. Op FYT-units zit dit soms achter het fabrieksmenu (code vaak `8888`
   of `3368`).
2. Zet de head unit en de pc op hetzelfde wifi-netwerk (bijvoorbeeld de hotspot van je telefoon).
3. Zoek het IP-adres van de head unit (Instellingen > Wifi > netwerkdetails) en verbind:

```bash
adb connect 192.168.43.50:5555
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` staat in `%LOCALAPPDATA%\Android\Sdk\platform-tools`. Op Android 12/13 met "Draadloze
foutopsporing" moet je eerst koppelen met `adb pair <ip>:<poort>` en de code die de unit toont.

## Eerst testen zonder auto (simulator)

1. Open de app. Geef de Bluetooth- en meldingsrechten.
2. Ga naar **Verbinding**, kies **Simulator** en tik **Verbinden**.
3. Verwacht: status gaat via "Verbinden" en "Initialiseren" naar **Verbonden – ISO 9141-2**.
   De eerste keer duurt dat een paar seconden ("SEARCHING..."), daarna gebruikt de app het
   opgeslagen protocol.
4. Ga naar **Debug**: je ziet ATZ, ATE0, … , 0100, ATDPN als TX/RX-regels. Probeer de knoppen
   `010C` (toerental) en `03` (foutcodes: P0171 en P1349 in de simulator).
5. Tik **Exporteren** en controleer dat er een tekstbestand wordt opgeslagen.

## Testen in de auto

Neem voor elke stap het debuglog mee (Exporteren) als iets niet werkt: dat is precies wat ik nodig
heb om de clone-quirks van jouw adapter in te bouwen.

1. **Koppelen.** Adapter in de OBD-poort (onder het dashboard links van het stuur), contact aan
   (stand II, motor mag uit). Koppel de adapter in de Bluetooth-instellingen van de head unit
   (PIN meestal `1234` of `0000`). Lukt dat niet via Android-instellingen, probeer dan het
   OBD-/Bluetooth-menu van de unit zelf.
2. **Kiezen.** In de app: Verbinding > Bluetooth Classic > kies de adapter (OBD-achtige namen staan
   bovenaan). Bij een BLE-adapter: Bluetooth LE > Zoeken.
3. **Eerste verbinding.** Tik Verbinden. Verwacht binnen ~10–20 s "Verbonden". Noteer welk protocol
   er staat (verwacht ISO 9141-2 of KWP2000).
   - Blijft hij hangen op "Initialiseren: Trying …": de app loopt de K-line-protocollen af en als
     laatste het Toyota-profiel. Wacht tot het eind en exporteer het log.
   - "Bluetooth adapter niet beschikbaar voor apps op deze head unit": de unit laat apps niet bij
     Bluetooth. Dan is een wifi-adapter de uitweg.
4. **Tweede verbinding.** Tik Verbreken en weer Verbinden. Nu moet het merkbaar sneller gaan
   (opgeslagen protocol, geen SEARCHING).
5. **Handmatige commando's** (Debug-scherm, motor draaiend): `010C`, `010D`, `0105`, `ATRV`, `03`.
   Elk antwoord hoort als *Data* geclassificeerd te worden.
6. **Herverbinden.** Trek de adapter eruit terwijl hij verbonden is. Verwacht binnen enkele
   seconden "Opnieuw verbinden over … s". Steek hem terug: hij moet vanzelf weer verbinden.
7. **Contact uit en aan.** Contact uit: de app gaat herverbinden met oplopende wachttijd (max. 30 s).
   Contact weer aan: verbinding moet vanzelf terugkomen.

## Bekende beperkingen fase 1

- Nog geen gauges, polling of foutcodescherm (fase 2 en 3).
- Geen autostart na ACC/boot (fase 5).
- Bij een wifi-adapter zonder internet kan Android het verkeer via een ander netwerk sturen; meld
  het als wifi niet verbindt.
