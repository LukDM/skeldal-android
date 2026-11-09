# Návod (walkthrough) shown in the app

Text and pictures of the walkthrough and tips come from [ceskehry.net](https://www.ceskehry.net/navody/brany_skeldalu)
(walkthrough: Score, edits: Majel007, map scans: dungeony.cz, tips: Paladin) and are used with the
permission of the site's operators. They are **not** covered by the MIT license of the code.
The chapters Základy hry, Ovládání na Androidu, Další tipy and Taktika v souboji were written for
the Android port.

- `content.txt` – the text (markup described at the top of `build.py`)
- `img/` – the original pictures (`img_hd/` with upscaled ones is used when present)
- `fonts/` – Almendra (SIL Open Font License 1.1)

Rebuild the page in the app after changing the text (needs Python 3 with Pillow):

```
python3 tools/navod/build.py app android/app/src/main/assets/navod
```

`python3 tools/navod/build.py` writes `navod.html` (A4 layout) that can be printed to PDF.
