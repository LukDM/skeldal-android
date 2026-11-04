# Brány Skeldalu – Android

Neoficiální port klasického českého RPG **Brány Skeldalu** (Gates of Skeldal) pro Android.
Vychází z otevřeného, multiplatformního enginu
[ondra-novak/gates_of_skeldal](https://github.com/ondra-novak/gates_of_skeldal) – herní logika,
zvuk, mapy i uložené pozice jsou stejné jako v PC verzi, Android přidává launcher, dotykové
ovládání a podporu ovladačů.

> **Aplikace neobsahuje žádná herní data.** Potřebujete vlastní legálně získanou kopii hry
> (CD/DOS, Windows, Steam nebo GOG) a data si do aplikace naimportujete sami.

*English: an unofficial Android port of Gates of Skeldal built on the open-source engine above.
No game data included – you need your own copy of the original game. See [ANDROID.md](ANDROID.md)
(Czech) and [README.engine.md](README.engine.md) for the engine itself.*

## Co umí

- **Import dat** ze složky nebo ZIP archivu (např. složka hry ze Steamu zkopírovaná do telefonu),
  kontrola úplnosti a rozpoznání verze.
- **Dotykové ovládání** – šipky pohybu, tlačítka Menu / Mapa / Akce / Boj a volitelná tlačítka
  (Uložit, Nahrát, Inventář, Kniha, Kouzla, Odpočinek). Rozmístění i velikost každého tlačítka
  se dá upravit v editoru. Klepnutí = levé tlačítko myši, podržení = pravé.
- **Ovladače** (Bluetooth/USB, i herní handheldy typu Retroid) – vlastní mapování tlačítek
  včetně analogových spouští a dalších tlačítek, zpomalený kurzor.
- **Obraz** – zachování poměru stran, celočíselné škálování nebo roztažení, velikost obrazu
  (kvůli zaobleným okrajům displeje), VSync, CRT filtr.
- **Uložené pozice** – kompatibilní s PC verzí, export/import (např. přes Google Disk),
  automatické zálohy, volitelné uložení při odchodu z aplikace.
- Pauza hry na pozadí, správa zvuku, čeština i angličtina, log a hlášení chyb.

Minimální verze: **Android 8.0**. Telefony i tablety, na šířku.

## Instalace

1. Stáhněte APK ze stránky [Releases](../../releases) (až bude k dispozici) nebo si ho sestavte.
2. Při instalaci povolte „Instalovat neznámé aplikace“.
3. V aplikaci zvolte **Importovat hru** a vyberte složku nebo ZIP s daty původní hry.

Podrobný návod, umístění dat a řešení problémů jsou v [ANDROID.md](ANDROID.md).

## Sestavení

Android Studio → *File → Open* → složka `android/`, nebo z příkazové řádky:

```
cd android
gradlew assembleDebug        # Linux/macOS: ./gradlew assembleDebug
```

Potřebné nástroje (SDK, NDK, CMake) a podepsaný release popisuje [ANDROID.md](ANDROID.md).
Desktopový build enginu je beze změny – viz [README.engine.md](README.engine.md).

## Licence

Zdrojový kód je pod licencí **MIT** (viz [LICENSE](LICENSE)), stejně jako původní engine.
Herní data (grafika, zvuky, hudba, mapy, texty, videa) jsou chráněna autorským právem a nejsou
součástí tohoto repozitáře – nenahrávejte je sem ani je nešiřte. Knihovny třetích stran:
[android/THIRD_PARTY_NOTICES.txt](android/THIRD_PARTY_NOTICES.txt).

Původní hra a zdrojový kód © Napoleon Games (MIT). Multiplatformní engine: Ondřej Novák a přispěvatelé.
