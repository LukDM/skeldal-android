# Brány Skeldalu – Android

Android port je další platforma existujícího enginu (ne nový engine). Herní logika, renderer
(SDL2), zvuk, mapy i uložené pozice jsou společné s PC verzí; Android část tvoří launcher
v Kotlinu, malý JNI most a platformní vrstva `platform/android/`.

**Aplikace neobsahuje žádná herní data.** Uživatel si je importuje ze své legálně získané
kopie (DOS/CD, Windows, Steam, GOG).

## Požadavky

| Nástroj | Verze |
|---|---|
| Android Studio | aktuální stabilní (s JDK 17+) |
| Android SDK Platform | 35 |
| Android NDK | 27.0.12077973 |
| CMake (z SDK) | 3.22.1 |
| Gradle | 8.14.3 (wrapper, stáhne se sám) |
| SDL2 | 2.30.9 (stáhne se při buildu přes CMake FetchContent z GitHubu) |

NDK a CMake nainstalujete v Android Studiu: *Settings → Languages & Frameworks → Android SDK →
SDK Tools* (zaškrtněte „Show Package Details“).

Minimální Android: 8.0 (API 26). ABI: arm64-v8a, armeabi-v7a, x86_64.

## Build

Android Studio: *File → Open* → složka `android/`.

Příkazová řádka:

```bash
cd android
./gradlew assembleDebug            # Windows: gradlew.bat assembleDebug
./gradlew testDebugUnitTest        # unit testy (bez zařízení, bez herních dat)
./gradlew bundleRelease            # AAB pro release
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### Podepsaný release

Vytvořte `android/keystore.properties` (je v `.gitignore`, nikdy ho necommitujte):

```properties
storeFile=/cesta/k/mujklic.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Bez tohoto souboru vznikne nepodepsaný release.

### Desktop

Desktopový build se nemění (Android části jsou oddělené `if(ANDROID)` v CMake):

```bash
mkdir build && cd build && cmake .. && make && ctest
```

## Instalace a import dat

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

1. Zkopírujte složku s instalací hry do telefonu (např. do *Stažené*), případně ji zabalte do ZIP.
2. V launcheru: **Importovat hru ze složky** nebo **Importovat hru ze ZIP**.
3. Launcher najde kořen hry (složku se `SKELDAL.DDL`), ověří instalaci a zkopíruje herní data do
   interního úložiště aplikace. Spustitelné soubory, knihovny (`.exe`, `.dll`, `steam_api*.dll`),
   `.ini` a `temp/` se nekopírují.

Kontrola instalace (podle zdrojového kódu enginu):

| Soubor | Význam |
|---|---|
| `SKELDAL.DDL` | povinný (bez něj engine skončí) |
| `maps/LESPRED.MAP` | výchozí mapa; chybí-li, instalace je „neúplná“ (lze spustit s varováním) |
| `music/*.MUS`, `video/INTRO.MGF`, `lang/<jazyk>/` | volitelné |

## Obraz a zadávání textu

- **Velikost obrazu** (Nastavení → Obraz, 50–100 %): zmenší obraz hry, když okraje
  displeje (zaoblené rohy, výřez kamery) zakrývají jeho část. U celočíselného
  škálování se nepoužije. V `skeldal.ini` jako `[video] picture_scale=`.
- **Klávesnice**: otevře se sama u textového pole (jméno postavy, název pozice).
  Když ji zavřete, nahoře uprostřed je tlačítko „⌨ Klávesnice“, které ji otevře
  znovu. Klepnutí do hry zůstávají myší.

- **Na výšku**: volba „Povolit hru na výšku“ (výchozí zapnuto, respektuje zámek otáčení
  systému). Obraz je nahoře, místo pod ním je pro dotykové ovládání. Vypnuto = jen na šířku.
- **Pruhy ve videích**: původní hra kreslí každý druhý řádek videa tmavší; na Androidu je to
  ve výchozím stavu vypnuté (`[video] video_scanlines=`).

Aplikace má ID balíčku `cz.gates.skeldal`.

## Umístění dat v zařízení

| Cesta (interní úložiště aplikace) | Obsah |
|---|---|
| `files/game/` | importovaná herní data |
| `files/saves/` | uložené pozice (stejný formát jako PC) |
| `files/saves_backups/` | automatické zálohy před importem pozic (5 posledních) |
| `files/skeldal.ini` | konfigurace generovaná launcherem z nastavení |
| `files/logs/game.log`, `game.prev.log` | log posledního a předposledního spuštění |
| `files/installations/default.properties` | metadata instalace |

Automatická záloha Androidu (Google) zálohuje jen `saves/` a nastavení.

## Architektura

```
Android launcher (Kotlin, Views)        import, nastavení, mapování, pozice, logy
        │  píše files/skeldal.ini, spouští GameActivity (proces :game)
        ▼
GameActivity (SDLActivity) + TouchOverlayView
        │  dotyk → klávesy/myš SDL, další tlačítka ovladače → JNI
        ▼
libskeldal.so: SDL_main (platform/android/app_start.cpp)
        ▼
Skeldal engine (game/, libs/) ── platformní vrstva (platform/, platform/sdl/)
        ▼
SDL2 (okno, renderer, zvuk, vstup, lifecycle)
```

- Hra běží ve vlastním procesu `:game`; po skončení hry proces skončí (engine má globální stav).
- Pozadí/zamknutí: herní hodiny se zastaví a herní vlákno čeká (`game_set_inactive`), SDL
  pozastaví zvuk. Audio focus ztlumí hru přes událost ve smyčce SDL.
- Ovladač: SDL GameController, standardní rozložení, připojení za běhu; spouště jako tlačítka
  21/22, další tlačítka 23–31 (viz `platform/platform.h`).

## Ladění

```bash
adb logcat -s SDL SDL/APP skeldal AndroidRuntime libc DEBUG
```

V aplikaci: *Nastavení → Export debug logu* uloží soubor s verzemi, zařízením, instalací,
nastavením a logy. Po pádu launcher nabídne *Spustit znovu / Otevřít log / Zkontrolovat instalaci*.

## Testování ovladače

*Nastavení → Ovladač* ukazuje připojený ovladač. Po připojení hra jednou požádá o stisk
libovolného tlačítka. Mapování: *Upravit mapování tlačítek* (bez modifikátoru / s
modifikátorem, další tlačítka se „naučí“ stiskem). V logu je vidět
`Joystick N: '...'` a `Using game controller: '...'`.

## Testy

- `android/app/src/test/` – JVM unit testy (detekce instalace, import, ZIP, Zip Slip, nastavení,
  konfigurace, mapování, dotyk, gesta, uložené pozice, pády).
- `platform/tests/timer_pause_test.cpp` – pozastavitelné herní hodiny (`ctest`).
- CI: `.github/workflows/build.yml` (Linux desktop + Android debug, bez herních dat).

## Licence

Kód je pod licencí MIT (viz `LICENSE`). Herní data jsou chráněna autorským právem a nejsou
součástí repozitáře ani aplikace. Seznam knihoven a licencí: `android/THIRD_PARTY_NOTICES.txt`
(v aplikaci *Informace → Licence*).
