# Nutrition data import

Builds the bundled long-tail nutrition asset
`app/src/main/assets/nutrition/foods.v1.json.gz`, loaded at runtime by
`BulkNutritionStore` and queried by `NutritionDatabase.match()` *after* the 66
curated records (which stay the source of truth for common Taiwanese dishes).

## Why a bundled asset (not web scraping)
Nutrition needs **authoritative, redistributable** data with reliable **sodium**
(the pivot nutrient for BP coaching). Web scraping violates site ToS, can't be
redistributed, and rarely has consistent per-100g sodium. We use open datasets:

| Source | Coverage | License |
|--------|----------|---------|
| **TFDA #8543** 台灣食品營養成分資料庫 | ~2,180 Taiwanese foods/ingredients | 政府資料開放授權條款 v1 (reuse + redistribution, attribution required) |
| USDA FoodData Central (FNDDS/SR) | generic/international (optional, not yet imported) | CC0 public domain |

TFDA is mostly **ingredients / whole foods**; composite restaurant dishes
(雞肉飯, 牛肉麵, 滷肉飯…) are covered by the curated layer. The two complement.

## Pipeline (deterministic, reproducible)
```
fetch_tfda.py            # download #8543 ZIP -> pivot long->wide -> data/tfda_wide.csv (committed)
build_nutrition_asset.py # tfda_wide.csv (+overrides, +curated exclusions) -> foods.v1.json.gz
build_nutrition_asset.py --check   # rebuild to a buffer, diff vs committed asset, exit 1 if drift
```
- `data/tfda_wide.csv` is committed (≈240 KB) so a rebuild needs no network.
- The build drops: rows with no numeric **sodium**; rows whose normalised name
  collides with a curated canonical/alias; bare-noun English aliases
  (`vegetable`, `egg`, `rice`, …) that caused substring false-matches.
- Output is byte-deterministic (records sorted, gzip mtime=0, fixed
  `generatedAt`) so `--check` is a stable CI guard.

## Asset format (`foods.v1.json.gz`)
```json
{"schemaVersion":1,"generatedAt":"...","sources":["TFDA-8543 ..."],
 "records":[{"c":"<canonical 中文>","a":["alias",...],
             "na":<sodium mg/100g>,"kc":<kcal>,"p":<protein g>,
             "f":<fat g>,"cb":<carb g>,"g":<default serving g>,"u":<highSodiumUncertainty>}]}
```
Short keys keep the asset small (~80 KB gz). Mapped to `NutritionRecord` by
`NutritionAssetRecord.toRecord()`.

## Heuristics (sources give per-100g, not servings)
- `g` (defaultPortionGrams): per TFDA 食品分類, seeded from curated values.
- `u` (highSodiumUncertainty): true for 調味料/加工調理 categories or names with
  soup/braised/pickled/sauced/canned keywords (CJK + latin).

## Verification
Content is validated by JVM unit tests (not just the generator):
`BulkNutritionStoreTest` (loads the real asset, asserts sodium present on every
row, no curated collision, no bare-noun alias) and
`NutritionDatabaseCuratedPriorityTest` (curated still wins; the 滷味 fix holds).

## Attribution
Surfaced in-app on the Settings → Open data & licenses screen.
