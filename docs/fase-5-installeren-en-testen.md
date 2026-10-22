# Fase 5: installeren en testen

Fase 5 voegt **autostart en opnieuw verbinden na slaapstand**, **thema's**, **dashboardpagina's** en een
**trackpagina** toe. Nieuw in de app:

- **SETUP > Startup:** de app start zelf mee met de head unit, verbindt opnieuw zodra de unit wakker
  wordt, en laat zien welk "contact aan/uit"-signaal jouw unit stuurt.
- **SETUP > Display:** vier ingebouwde thema's (Race, Minimal, Retro, Day), eigen thema's als
  JSON-bestand, een dag/nacht-schakeling en een accentkleur. Zie `docs/themes.md` voor het bestandsformaat.
- **SETUP > Dashboard:** het dashboard is een rij pagina's waar je tussen veegt: **Main**, **Grid** en
  **Track**. Je stelt per pagina in welke signalen erop staan.
- **Trackpagina:** groot toerental, shift light en piekwaarden voor circuitdagen.

De app is volledig Engelstalig; deze handleiding blijft Nederlands en noemt de schermteksten zoals ze in
de app staan. Screenshots van de emulator staan in `docs/screenshots/fase-5-*.png`.

## APK bouwen en installeren

Hetzelfde als in fase 1 tot en met 4 (zie `docs/fase-1-installeren-en-testen.md`):

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

Open de app na de eerste installatie **één keer met de hand** en geef toestemming voor meldingen en
voor Nearby devices (Bluetooth). Een app die nooit is geopend staat bij Android (en bij veel head
units) in de "gestopte" staat en krijgt geen enkele broadcast, ook niet bij het opstarten.

## Autostart en opnieuw verbinden: hoe het werkt

Een head unit werkt anders dan een telefoon. Bij contact uit telt de unit ongeveer 20 tot 30 seconden af
en **stopt dan alle apps die niet op zijn eigen whitelist staan** (FYT, Microntek en andere). Een
gestopte app krijgt geen enkele broadcast meer, ook geen `BOOT_COMPLETED`, tot je hem met de hand opent.
Bij een wake-up uit slaapstand (geen echte herstart) stuurt Android helemaal geen `BOOT_COMPLETED`.
Daarom werkt de app met drie lagen:

1. **De whitelist van de unit (jij doet dit eenmalig).** Zonder dit helpt niets van wat hieronder staat.
2. **Start na een herstart.** Een receiver voor `BOOT_COMPLETED` start de service, en ook na een
   app-update (`MY_PACKAGE_REPLACED`). Dit zijn de enige twee broadcasts waarmee Android 12 en hoger een
   achtergrondservice laat starten. `LOCKED_BOOT_COMPLETED` doet de app bewust niet: de instellingen
   staan in een database die pas na de eerste ontgrendeling leesbaar is. Een head unit zonder
   schermvergrendeling ontgrendelt bij het opstarten, dus `BOOT_COMPLETED` komt gewoon.
3. **Wake-up terwijl de service draait.** De service luistert dan (dynamisch geregistreerd, dat is de
   enige manier voor fabrikant-broadcasts) naar de lijst "contact aan" en "contact uit" uit SETUP >
   Startup, plus de Android-signalen `SCREEN_ON` en `USER_PRESENT`.
   - **Wake** (contact aan, scherm aan, gebruiker aanwezig): wacht de verbinding op een nieuwe poging of
     was hij gesloten, dan bouwt de app hem **meteen** opnieuw op, zonder de wachttijd af te maken. Een
     verbinding die al werkt of net wordt opgezet blijft met rust (de signalen komen vaak tegelijk),
     en een verbinding die jij met **Disconnect** sloot, blijft dicht.
   - **Sleep** (contact uit): de app sluit de verbinding meteen (adapter en autoaccu) en pauzeert; de
     service blijft draaien. De melding zegt dan "Car off. Waiting for the unit to wake up." Open je de
     app, tik je **Connect** of komt er een wake-signaal, dan verbindt hij weer.
   - Staat **Reconnect when the unit wakes up** uit, dan doen de signalen niets en gedraagt de app zich
     als in fase 4 (hij blijft zelf opnieuw proberen).
   - Een scherm dat uitgaat (`SCREEN_OFF`) doet bewust niets: sommige mensen dimmen het scherm tijdens het
     rijden, en dan moet het opnemen van de rit doorlopen.

Er is geen vaste lijst van signalen die elke unit stuurt; geen fabrikant documenteert dit. Daarom staat
de lijst in de app, je kunt hem aanpassen, en toont de app per signaal **wanneer het voor het laatst
aankwam**. Zo ontdek je in de auto welke string jouw unit stuurt (bron en achtergrond:
`docs/research/headunit-autostart.md`).

## SETUP > Startup

Een scrollende pagina (zie `fase-5-setup-startup.png`, onderaan `fase-5-setup-startup-keepalive.png`):

- **Start with the head unit:** de service start na een herstart van de unit en na een app-update.
  Standaard aan.
- **Reconnect when the unit wakes up:** de wake/sleep-logica hierboven. Standaard aan.
- **Open the dashboard on start:** na een herstart komt de app zelf naar voren. Standaard uit. Dit werkt
  alleen met de Android-toestemming **Display over other apps**. Ontbreekt die, dan staat onder de
  schakelaar "Opening the dashboard on its own needs "Display over other apps", which is not allowed
  yet." met een knop **ALLOW** die het systeemscherm voor die toestemming opent. De app doet verder geen
  trucs om op de voorgrond te komen.
- **Wake and sleep signals:** twee lijsten, **WAKE (IGNITION ON)** en **SLEEP (IGNITION OFF)**. Per rij
  de actie in vaste letters, **LAST RECEIVED** (datum en tijd, of **never** zolang het signaal niet kwam)
  en **REMOVE**. De tijden blijven bewaard.
  - Standaard wake: `com.fyt.boot.ACCON`, `com.glsx.boot.ACCON`,
    `android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT`, `com.microntek.startApp`,
    `com.cayboy.action.ACC_ON`, `android.intent.action.QUICKBOOT_POWERON`, `QUICKBOOT_POWERON`,
    `BOOT_IPO`.
  - Standaard sleep: `com.fyt.boot.ACCOFF`, `com.glsx.boot.ACCOFF`,
    `android.intent.action.ACTION_MT_COMMAND_SLEEP_IN`.
  - **Toevoegen:** typ de actie in het veld en tik **ADD AS WAKE** of **ADD AS SLEEP**. Toegestaan:
    letters, cijfers, punten en underscores, 1 tot 128 tekens, geen dubbelen. Anders staat er een rode
    regel boven het veld ("Use letters, digits, dots and underscores only." en dergelijke, zie
    `fase-5-setup-startup-error.png`).
  - **RESET TO DEFAULTS** zet beide lijsten terug naar de standaard.
  - Een wijziging werkt direct: de service luistert meteen naar de nieuwe lijst.
- **Keep the app alive:** de app zegt welk type unit hij denkt dat het is (**Detected:** FYT / UIS head
  unit, MTCD / MTCE (Microntek) head unit of Other Android head unit) en wat je daar moet doen, plus
  twee knoppen: **BATTERY OPTIMIZATION** (Android-batterijoptimalisatie uitzetten voor de app) en
  **DISPLAY OVER OTHER APPS**. Heeft de unit zo'n systeemscherm niet, dan staat er "This head unit has no
  screen for that setting. Use the settings of the unit itself instead." De detectie is een gok op basis
  van systeemapps en chipnamen; klopt hij niet, dan zijn de instructies voor "Other" wat je zoekt.

## Eerst testen zonder auto (emulator of een telefoon met adb)

De signalen kun je zelf versturen. De service moet draaien (open de app, kies **Simulator** en tik
**Connect**).

1. **Wake-signalen komen aan.** Open SETUP > Startup en stuur:
   ```bash
   adb shell am broadcast -a com.fyt.boot.ACCON
   adb shell am broadcast -a com.fyt.boot.ACCOFF
   ```
   Verwacht: bij **com.fyt.boot.ACCON** en **com.fyt.boot.ACCOFF** staat nu de tijd van nu in plaats
   van **never**. Het werkt met en zonder `-p nl.stijnvdpol.klinedash`.
2. **Contact uit sluit de verbinding.** Na `ACCOFF` zegt de meldingenbalk "Car off. Waiting for the
   unit to wake up." en rechtsonder in de app staat **Not connected**. Een tweede `ACCOFF` doet niets.
   Liep er een rit, dan is die meteen afgesloten: onder TRIPS staat hij met duur en afstand, niet als
   **Recording**.
3. **Contact aan verbindt opnieuw.** Stuur `adb shell am broadcast -a
   android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT`: binnen een paar seconden staat er weer
   **Connected** en heeft die actie een tijd. In `adb logcat -s ObdService` staat per signaal wat de app
   besloot, bijvoorbeeld `Signal com.fyt.boot.ACCOFF (ACC_OFF) while Connected: SUSPEND`.
4. **Scherm uit en aan.** `adb shell input keyevent 26` (twee keer, met een pauze ertussen) na een
   `ACCOFF`: bij scherm aan verbindt de app weer (`SCREEN_ON` in het log).
5. **Lijst aanpassen.** Voeg `my.Test_ACC` toe met **ADD AS WAKE**, stuur
   `adb shell am broadcast -a my.Test_ACC` en kijk of de rij een tijd krijgt. Typ `bad action` of laat
   het veld leeg en tik **ADD AS WAKE**: er staat een rode foutregel en er komt niets bij. Tik
   **REMOVE** bij je eigen regel en **RESET TO DEFAULTS** om de standaardlijst terug te krijgen.
6. **Herstart.** `adb reboot`. Op de emulator komt `BOOT_COMPLETED` pas ruim een halve minuut na het
   opstarten bij apps aan; daarna draait de service weer (`adb shell dumpsys activity services
   nl.stijnvdpol.klinedash | grep -E "isForeground|allowStartForeground"` toont `allowStartForeground=BOOT_COMPLETED`; in `adb logcat` staat `BootReceiver:
   android.intent.action.BOOT_COMPLETED: BootPlan(startService=true, ...)`). Nogmaals installeren met
   `adb install -r` start de service ook, via `MY_PACKAGE_REPLACED`.
7. **Dashboard open bij het starten.** Zet **Open the dashboard on start** aan, geef de toestemming
   (`adb shell appops set nl.stijnvdpol.klinedash SYSTEM_ALERT_WINDOW allow`), ga naar het homescherm en
   herstart: na het opstarten komt de app zelf naar voren. Zonder de toestemming niet, en de melding
   onder de schakelaar blijft staan.

Wat je met `adb` **niet** kunt nabootsen: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
-n nl.stijnvdpol.klinedash/.service.BootReceiver` (met `adb root`) bereikt de receiver wel, maar Android
laat daaruit geen achtergrondservice starten omdat de broadcast niet van het systeem komt. De app logt
dan een waarschuwing en crasht niet. Gebruik `adb reboot`.

## Testen in de auto

Adapter: Konnwei KW905 (Bluetooth LE). **Doe de voorbereiding eerst; zonder whitelist slaagt de rest
vaak niet.** Neem bij elk probleem het debuglog mee (**SETUP > Debug > Export log**) en noteer wat er
onder SETUP > Startup staat.

### Voorbereiding

1. Installeer de APK, open de app met de hand, geef de toestemmingen en verbind met de adapter (**SETUP >
   Connection**). Wacht op **Connected**.
2. Open **SETUP > Startup > Keep the app alive** en noteer wat er bij **Detected:** staat.
3. **Zet de app op de lijst van de unit** die apps in leven houdt (dit is de belangrijkste stap):
   - FYT / UIS (de meeste "Android 12/13"-units van Joying, Dasaita en dergelijke): fabrieksinstellingen,
     de **sleep whitelist**. De fabriekscode is vaak `8888`, maar verschilt per firmware. Heeft jouw
     firmware daar geen lijst (nieuwere firmware heeft dat niet), gebruik dan de **FYT Factory**-tool.
   - MTCD / MTCE (Microntek): de lijst "Whitelist of programs to run after sleep" (Malaysk-ROM) of de
     app BootControl.
   - Andere units: zoek in de eigen instellingen van de unit naar autostart, sleep whitelist of
     do-not-kill.
4. Tik **BATTERY OPTIMIZATION** en sta achtergrondgebruik toe. (Optioneel: **DISPLAY OVER OTHER APPS**
   voor het openen van het dashboard na een herstart.)
5. Open de app nog één keer met de hand na het aanpassen van de lijst.

### Tests

1. **Contact uit en weer aan (de belangrijkste test).** Zet de motor uit en het contact uit, wacht een
   minuut en zet het contact weer aan, zonder de app aan te raken.
   - Verwacht: de app heeft binnen een paar seconden weer **Connected** (de melding en de balk
     onderaan), en er is weer een rit begonnen zodra de motor draait.
   - Open daarna **SETUP > Startup**: welke acties tonen een tijd? **Noteer welke** (bij wake en bij
     sleep). Dat is wat jouw unit stuurt. Staat er overal **never**, ga dan door naar test 3.
2. **Wake zonder signaal.** Komt de verbinding terug terwijl alle acties **never** tonen, dan deden
   `SCREEN_ON` of `USER_PRESENT` het werk. Dat is prima.
3. **De app is gestopt.** Komt de verbinding niet terug en staat alles op **never**, dan is de app door de
   unit gestopt: de whitelist (voorbereiding 3) werkt niet of is niet opgeslagen. Voer
   `adb shell dumpsys activity broadcasts | grep -iE "acc|sleep|boot"` uit na een uit/aan-cyclus (wifi-adb
   of de unit aan de pc) en stuur me de uitvoer, dan voeg ik de juiste actie toe aan de standaardlijst.
4. **Koude start.** Zet het contact uit, wacht tot de unit helemaal uit is (bij FYT kan dat minuten
   duren; of start de unit opnieuw via het fabrieksmenu) en zet het contact aan.
   - Verwacht: de melding "K-Line Dash" verschijnt vanzelf en de app verbindt zonder dat je hem opent.
   - Staat **Open the dashboard on start** aan en is de toestemming gegeven: de app komt zelf naar voren.
   - Gebeurt er niets, kijk dan of **Start with the head unit** aan staat en of de app op de whitelist staat.
5. **Contact uit sluit de verbinding.** Zet het contact uit terwijl je naar het scherm kijkt (de unit
   blijft bij FYT soms nog enkele minuten aan). Verwacht: de melding wordt "Car off. Waiting for the unit
   to wake up." en de adapter-led wordt rustig. Dit is ook wat je autoaccu spaart.
6. **App-update.** Installeer een nieuwe versie over de oude: de service start vanzelf.
7. **Uitzetten.** Zet **Reconnect when the unit wakes up** uit en herhaal test 1: de app probeert dan
   zelf opnieuw (zoals in fase 4) en sluit niets bij contact uit.

Noteer na deze tests: (a) wat bij **Detected** stond, (b) welke wake- en sleep-acties een tijd toonden,
(c) of een koude start de app vanzelf starte, (d) of de whitelist nodig bleek.

## Thema's (SETUP > Display)

Zie `fase-5-setup-display.png` en de vier thema's in `fase-5-theme-race.png`, `fase-5-theme-minimal.png`,
`fase-5-theme-retro.png` en `fase-5-theme-day.png`. Links het thema, rechts dag/nacht en de accentkleur.

- **Thema kiezen.** Tik een van de ingebouwde thema's: **Race** (zwart, wit; standaard), **Minimal**
  (rustiger, grijze tekst), **Retro** (amber, als een instrumentenpaneel uit de jaren tachtig) of **Day**
  (licht, voor fel zonlicht). Het hele scherm verandert direct. Naast elk thema staan kleine vakjes met
  de kleuren.
- **Accentkleur.** Acht kleurvlakken en **Theme default**: de kleur van de brandende toerentalbalk en de
  markering van de actieve pagina. Statuskleuren (groen, amber, rood) veranderen nooit van betekenis.
- **Dag en nacht.** **Manual** (één thema, altijd), **Schedule** (het dagthema tussen twee tijden; stel
  **Day starts** en **Night starts** in met **−** en **+**) of **Follow head unit** (Android-nachtmodus,
  die veel units koppelen aan de koplampen). In de automatische standen kies je met de schakelaar
  **DAY THEME / NIGHT THEME** boven de lijst welk thema bij dag en welk bij nacht hoort. **NOW SHOWING**
  zegt welk thema nu actief is.
- **Eigen thema's.** Onder **YOUR THEMES** staat de map waar je `*.json`-bestanden in kopieert (op de
  meeste units `/storage/emulated/0/Android/data/nl.stijnvdpol.klinedash/files/themes`). Tik **Reload
  themes** of open de pagina opnieuw. Een ongeldig bestand komt onder **Ignored files** te staan met de
  reden (bijvoorbeeld te weinig contrast voor de labels). Formaat en regels: `docs/themes.md`.

**Test in de auto:** zet **Follow head unit** aan, kies bij **NIGHT THEME** een donker thema, doe de
koplampen aan en kijk of de app omschakelt. Doet hij dat niet, gebruik dan **Schedule** (sommige units
zetten de Android-nachtmodus niet) en **laat het me weten** welk gedrag je zag. Controleer ook of
**Day** goed leesbaar is in de zon.

## Dashboardpagina's en trackpagina (SETUP > Dashboard)

Het dashboard is een rij pagina's waar je horizontaal tussen veegt. Er zijn geen puntjes; na een veeg
staat de naam van de pagina een seconde bovenaan. Standaard zijn er drie pagina's: **Main** (het
dashboard zoals in eerdere fasen), **Track** en een **Grid** van 3 x 2.

Onder **SETUP > Dashboard** (zie `fase-5-setup-dashboard.png`) staat links de lijst **Pages** en rechts
de editor van de gekozen pagina. De pagina's zelf staan in `fase-5-dashboard-main.png` en `fase-5-page-grid.png`:

- **Move left / Move right** verplaatst de pagina, **Add page** voegt er een toe (**Main**, **Grid** of
  **Track**), **Delete page** haalt de gekozen pagina weg na een vraag. Er is altijd minstens één pagina
  en er zijn er hooguit 12.
- **Main:** tik een veld en kies uit de lijst welk signaal erin staat (drie grote en zes kleine velden).
- **Grid:** kies **2 x 2**, **3 x 2** of **3 x 3**. Tik een cel, kies het signaal en of de cel een
  **Number** of een **Graph** (de laatste 60 seconden als lijn) toont. De getallen links van de grafiek
  zijn de onder- en bovenkant van de as, op ronde stappen; een watertemperatuur die 2 °C beweegt is
  een bijna vlakke lijn.
- **Track:** stel het **Shift point** in (standaard 7 800 rpm, stappen van 100).
- Wijzigingen werken direct. Is de auto niet verbonden, dan toont de lijst alle signalen; wat de auto
  niet kent staat als `--`.

### De trackpagina

Zie `fase-5-page-track.png` en `fase-5-track-shift.png` (shift light aan).

Het toerental staat groot links, de toerentalbalk bovenaan, water en snelheid rechts en daaronder de
**piekwaarden**: **MAX RPM** en **MAX KM/H** sinds de laatste reset, met de knop **RESET PEAKS**. Vanaf
het shift point knipperen de hele balk en een kader rond het scherm in rood, vier keer per seconde. Dit
is de enige plek waar de app knippert, bewust: op een circuit is dat de afspraak, en het gebeurt alleen
boven jouw eigen shift point.

**Test:** vee met de simulator naar **Track** (de simulator rijdt een rondje door de versnellingen en
komt daarbij ver boven de 4 000 rpm). Zet bij **SETUP > Dashboard** het **Shift point** op 4 000 en kijk
of de balk en het kader rood knipperen zodra het toerental erboven komt, en weer stoppen eronder. Tik
**RESET PEAKS**: de pieken beginnen opnieuw vanaf het huidige toerental en de huidige snelheid.

## Wat is er nieuw ten opzichte van fase 4

- SETUP heeft drie nieuwe secties: **Dashboard**, **Display** en **Startup** (naast Connection, Warnings
  en Debug).
- De service start na een herstart en na een app-update, verbindt opnieuw bij wake-up en sluit bij contact
  uit. Dit komt bij de bestaande foutafhandeling: een geweigerde start van de service (Android 12+) wordt
  gelogd en laat de app niet crashen.
- Nieuwe permissies in de manifest: `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW` (alleen voor het
  openen van het dashboard, en alleen als jij het toestaat) en `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## Bekende beperkingen fase 5

- **De whitelist van de unit blijft jouw taak.** Als de unit de app bij contact uit stopt en hem niet
  op een lijst heeft staan, kan geen enkele app zichzelf herstarten. De app laat alleen zien wat er
  binnenkomt.
- `LOCKED_BOOT_COMPLETED` wordt niet gebruikt (zie hierboven). De app is dus pas actief na de eerste
  ontgrendeling na het opstarten.
- De standaardlijst met acties komt uit verslagen van anderen op forums; niets daarvan is op jouw unit
  getest. `com.microntek.startApp`, `com.cayboy.action.ACC_ON`, `QUICKBOOT_POWERON` en `BOOT_IPO` zijn
  de minst zekere. Alleen de tijden onder **LAST RECEIVED** laten zien wat echt aankomt.
- Fabrikant-broadcasts zijn voor elke app op de unit te versturen (de receiver is bewust niet
  afgeschermd, want de zender is een andere app). Een andere app kan dus de verbinding laten sluiten of
  herstellen. Er gaat geen data mee en de verbinding is zonder gevolgen weer te herstellen met **Connect**.
- De detectie van het type unit is een gok. **Other Android head unit** is de veilige terugval.
- Tijdens contact uit sluit de app de verbinding op het eerste signaal, ook als de unit nog minuten aan
  blijft. Het opnemen van een rit eindigt dan na de gewone wachttijd van twee minuten.
