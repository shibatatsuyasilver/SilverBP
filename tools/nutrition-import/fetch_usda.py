#!/usr/bin/env python3
"""Fetch + normalise USDA FoodData Central (FNDDS + SR Legacy) into usda_wide.csv.

USDA covers generic / international foods to complement the Taiwan TFDA set
(便當 dishes stay in the curated layer; ingredients in TFDA). We take:
  - Survey (FNDDS)  — prepared / mixed dishes ("Rice, white, cooked", ...)
  - SR Legacy       — generic foods / ingredients
We SKIP Branded Foods (1M+ rows; that is the Open Food Facts barcode path).

Each food's per-100g energy/protein/fat/carb/sodium is joined from
food_nutrient.csv (nutrient ids 1008/1003/1004/1005/1093) onto food.csv, then
written in the same wide schema build_nutrition_asset.py consumes. The build
step applies the sodium gate, curated-collision drop, dedup and heuristics.

Source : USDA FoodData Central — https://fdc.nal.usda.gov/download-datasets
License: Public Domain (CC0 1.0); crediting FoodData Central is requested.

Requires: pandas. Usage: python3 fetch_usda.py
"""
import os
import subprocess
import tempfile
import zipfile

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "data/usda_wide.csv")
BASE = "https://fdc.nal.usda.gov/fdc-datasets/"
DATASETS = [
    ("sr", "FoodData_Central_sr_legacy_food_csv_2018-04.zip"),
    ("fndds", "FoodData_Central_survey_food_csv_2024-10-31.zip"),
]
# food_nutrient.nutrient_id keys differ by dataset: SR Legacy uses the
# nutrient.csv `id` (1008, 1003, ...), FNDDS uses the `nutrient_nbr` (208, 203,
# ...). The two value spaces are disjoint (ids >=1000, nbrs <1000) so accepting
# both for each target nutrient resolves both datasets uniformly.
NUTRIENT_KEYS = {
    "1008": "kcal", "208": "kcal",
    "1003": "protein", "203": "protein",
    "1004": "fat", "204": "fat",
    "1005": "carb", "205": "carb",
    "1093": "sodium", "307": "sodium",
}


def fetch_zip(filename):
    # urllib's SSL store is unreliable on some setups; curl is dependable here.
    tmp = os.path.join(tempfile.gettempdir(), filename)
    subprocess.run(["curl", "-sSL", "-m", "300", "-o", tmp, BASE + filename], check=True)
    return zipfile.ZipFile(tmp)


def read_csv_in_zip(zf, basename):
    name = next(n for n in zf.namelist() if n.endswith("/" + basename) or n == basename)
    with zf.open(name) as f:
        return pd.read_csv(f, dtype=str, keep_default_na=False)


def parse_dataset(tag, filename):
    zf = fetch_zip(filename)
    food = read_csv_in_zip(zf, "food.csv")[["fdc_id", "description"]]
    fn = read_csv_in_zip(zf, "food_nutrient.csv")[["fdc_id", "nutrient_id", "amount"]]
    fn = fn[fn["nutrient_id"].isin(NUTRIENT_KEYS)]
    fn["col"] = fn["nutrient_id"].map(NUTRIENT_KEYS)
    fn["amount"] = pd.to_numeric(fn["amount"], errors="coerce")
    # One amount per (food, nutrient): take the first.
    wide = fn.pivot_table(index="fdc_id", columns="col", values="amount", aggfunc="first")
    wide = wide.reset_index()
    merged = food.merge(wide, on="fdc_id", how="inner")
    rows = []
    for _, r in merged.iterrows():
        rows.append({
            "id": f"usda-{tag}-{r['fdc_id']}",
            "name": (r["description"] or "").strip(),
            "alias": "",
            "name_en": "",      # description IS the English canonical
            "category": "",     # portion falls back to default; uncertainty via name keywords
            "kcal": r.get("kcal", ""),
            "protein": r.get("protein", ""),
            "fat": r.get("fat", ""),
            "carb": r.get("carb", ""),
            "sodium": r.get("sodium", ""),
        })
    return rows


def main():
    all_rows = []
    for tag, filename in DATASETS:
        rows = parse_dataset(tag, filename)
        print(f"{tag}: {len(rows)} foods")
        all_rows += rows
    out = pd.DataFrame(all_rows, columns=["id", "name", "alias", "name_en", "category",
                                          "kcal", "protein", "fat", "carb", "sodium"])
    out = out.sort_values("id")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out.to_csv(OUT, index=False, encoding="utf-8")
    with_sodium = out["sodium"].astype(str).str.len().gt(0).sum()
    print(f"wrote {OUT}: {len(out)} foods ({with_sodium} with sodium)")


if __name__ == "__main__":
    main()
