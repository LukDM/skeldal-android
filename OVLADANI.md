# Brány Skeldalu (Android) – ovládání

Přehled ovládání Android portu: dotyk, gamepad a hardwarová klávesnice. Hra se ovládá stejně
jako na PC, tedy myší a klávesami. Dotykové ovládání a gamepad tyto vstupy jen napodobují.

Všechno se nastavuje v launcheru v **Nastavení**.

---

## Dotyk

### Gesta na obrazu hry

| Gesto | Odpovídá na PC | Použití |
|---|---|---|
| **Klepnutí** | levé tlačítko myši | výběr, použití, otevření inventáře (klepnutí na portrét) |
| **Podržení prstu** (asi 0,5 s) | pravé tlačítko myši | zpět, zavření okna, otočení předmětu; jde vypnout v Nastavení |
| **Tažení prstem** | levé tlačítko držené | přenášení předmětů, posouvání |

Kurzor se vždy přesune tam, kam klepneš.

### Výchozí tlačítka

| Tlačítko | Klávesa | Funkce |
|---|---|---|
| ↑ / ↓ | šipky | krok vpřed / vzad (držením jde postava dál) |
| ↺ / ↻ | ← / → | otočení vlevo / vpravo |
| ← / → (spodní řada) | End / PgDn | úkrok vlevo / vpravo |
| **ESC** | Esc | menu „Konec“, zavření okna |
| **MAPA** | Tab | mapa |
| **AKCE** | mezerník | akce na stěně (páka, dveře), v boji další postava |
| **BOJ** | Enter | začít boj, potvrdit, v boji „stát“ |

### Volitelná tlačítka

Zapínají se v Nastavení → Dotykové ovládání.

| Tlačítko | Klávesa | Funkce |
|---|---|---|
| ULOŽIT / NAHRÁT | F2 / F3 | uložení / nahrání pozice |
| INVENT. | I | inventář |
| KNIHA | F6 | kniha svitků |
| KOUZLA | F7 | kouzlení mimo boj |
| SPÁT | F4 | odpočinek |
| NASTAV. | F1 | nastavení hry |
| ⌨ | – | klávesnice kdykoli: zapnout / vypnout |
| CTRL | Ctrl | **zamykací**: klepnutím se zapne (tlačítko svítí), dalším klepnutím vypne |
| SLOUČIT | Insert | sloučí do jedné skupiny všechny postavy na jednom políčku |
| MAPA+ | O | průhledná mapa přes obraz (Úpravy hry) |
| KONZOLE | ` | konzole s příkazy (Úpravy hry) |

### Rozmístění a velikost tlačítek

V Nastavení → Rozmístění tlačítek:

- Šipky se přetahují jako celek, ostatní tlačítka jednotlivě.
- Klepnutím vybereš prvek a tlačítky **− Menší** / **+ Větší** měníš jeho velikost.
- Celková velikost a průhlednost všech tlačítek se nastavuje posuvníky.
- Volba **„Automaticky“** tlačítka skryje, jakmile použiješ gamepad nebo klávesnici, a znovu je ukáže po dotyku.

### Psaní textu

Klávesnice se otevře sama u každého textového pole (jméno postavy, název pozice, smlouvání,
konzole). Když ji zavřeš, objeví se nahoře uprostřed tlačítko **⌨ Klávesnice**, které ji vrátí.

---

## Gamepad

Funguje přes Bluetooth i USB a na herních handheldech, například Retroid. Mapování tlačítek se dá
změnit v Nastavení → Ovladač → Mapování tlačítek. Další tlačítka (M1, M2, zadní pádla…) se dají
naučit.

### Páčky

| Páčka | Funkce |
|---|---|
| **Levá** | nahoru / dolů = krok vpřed / vzad, doleva / doprava = otočení; s držením RB / R1 úkroky |
| **Pravá** | pohyb kurzoru myši |

Páčky jdou prohodit v Nastavení. Pro přesné míření je **pomalý kurzor**: buď akce „Pomalý
kurzor“ na libovolném tlačítku, nebo volba „zpomalit kurzor s modifikátorem“.

### Výchozí mapování

**RB / R1 je modifikátor.** Při jeho držení mají tlačítka druhou funkci (pravý sloupec).

| Tlačítko | Normálně | S držením RB / R1 |
|---|---|---|
| A / ✕ | klik (levé tlačítko) | F10 Výzbroj a inventář (boj) |
| B / ○ | pravé tlačítko (zpět) | F9 Pohyb (boj) |
| X / □ | Enter (začít boj, potvrdit) | F7 Kouzla |
| Y / △ | mezerník (akce na stěně) | F8 Útok (boj) |
| Back / Share | F1 Nastavení | F2 Uložit hru |
| Start / Options | Esc (menu, zavřít) | F3 Nahrát hru |
| Guide / PS | F1 Nastavení | Esc |
| LB / L1 | Backspace (zrušit akci v boji) | F4 Odpočinek |
| R3 (stisk pravé páčky) | Ctrl + klik (rozdělit skupinu) | – |
| D-pad ↑ | Mapa | F11 Útěk (boj) |
| D-pad ↓ | Spojit skupinu | F6 Kniha |
| D-pad ← / → | úkrok vlevo / vpravo | Backspace / F12 Žádná akce (boj) |
| LT / L2, RT / R2 | úkrok vlevo / vpravo | úkrok vlevo / vpravo |

Na Androidu se tlačítko Guide / PS často nedostane do hry, protože si ho bere systém. Proto je
menu na Start.

V mapování jsou navíc akce **Oddělit postavu 1–6**, které jedním tlačítkem přidají postavu
do skupiny nebo ji z ní odeberou.

---

## Skupina: rozdělení a sloučení

Rozdělování funguje jako na PC, kde se drží **Ctrl** a klikne se na portrét postavy:

| Akce | Dotyk | Gamepad | Klávesnice |
|---|---|---|---|
| přidat postavu do skupiny / odebrat ji | **CTRL** (zapnout) + klepnout na portrét | kurzor na portrét + **R3** | Ctrl + klik, nebo **1–6** |
| přepnout na jinou skupinu | klepnout na člena skupiny | kurzor + A / ✕ | klik |
| sloučit všechny postavy na políčku | **SLOUČIT** | **D-pad ↓** | Insert |

Vybraná skupina má jména bíle a portréty prosvětlené. Ostatní skupiny mají vlastní barvu.

---

## Hardwarová klávesnice (Bluetooth / USB)

| Klávesa | Funkce |
|---|---|
| šipky | chůze a otáčení |
| End / PgDn | úkroky |
| Tab | mapa |
| mezerník | akce na stěně |
| Enter | začít boj |
| Esc | menu, zavřít |
| F1 | nastavení hry |
| F2 / F3 | uložit / nahrát |
| F4 | odpočinek |
| F5 | rychlé (automatické) uložení |
| F6 | kniha |
| F7 | kouzla |
| I | inventář |
| 1–6 | přidat / odebrat postavu ve skupině |
| Insert | sloučit skupinu |
| **V boji:** F8 / F9 / F10 / F11 / F12 / Backspace | útok / pohyb / výzbroj / útěk / žádná akce / zrušit |
| O | průhledná mapa |
| ` | konzole |

---

## Úpravy hry (nad rámec originálu)

V Nastavení → Úpravy hry:

- **Minimapa v rohu i mimo boj**: malá mapa vlevo nahoře, jinak je jen v boji.
- **Životy nepřátel**: číslo, proužek nebo obojí nad nepřítelem. Číslo je stejné jako u kouzla Stav nepřítele.
- **Průhledná mapa**: zapíná se tlačítkem MAPA+ nebo klávesou O.
  - Ukazuje prozkoumanou část patra přes celý pohled.
  - Je otočená podle směru chůze a družina je uprostřed.
  - Obraz pod ní jde ztmavit.
- **Rychlost hry**: normální, rychlá nebo velmi rychlá.
- **Konzole**: tlačítko KONZOLE nebo klávesa `.
  - Příkazy: `hunter-instinct on` (životy), `true-seeing on` (skryté stěny), `iron-skin on` (nesmrtelnost), `ghost-walls on` (procházení zdí).
  - Vypnutí slovem `off`. Konzoli zavřeš tlačítkem ESC nebo znovu KONZOLE.

Uložené pozice zůstávají kompatibilní s PC verzí.

---

## Pozastavení a ukládání

- Když odejdeš z aplikace, hra se pozastaví a čas ve hře se zastaví.
- Volitelně se před tím hra automaticky uloží (F5) do pozice „autosave“ aktuální kampaně.
  Funguje to jen při průzkumu, ne v boji ani v menu.
- Tlačítko Zpět funguje jako Esc.
