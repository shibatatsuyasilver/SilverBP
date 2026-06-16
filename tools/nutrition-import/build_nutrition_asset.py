#!/usr/bin/env python3
"""Build the bundled bulk-nutrition asset from open-data intermediate CSVs.

Input  : data/tfda_wide.csv  (one row per food; see fetch_tfda.py)
         data/usda_wide.csv   (optional; same columns; not committed yet)
         data/overrides/aliases.csv, blocklist.csv (optional manual fixes)
Curated: app/.../NutritionDatabase.kt  (parsed for canonical names + aliases)
Output : app/src/main/assets/nutrition/foods.v1.json.gz

The output is the LONG-TAIL layer. The 66 curated records stay the source of
truth: any bulk food whose normalised name collides with a curated key is
dropped so the runtime never has an ambiguous tie. Sodium is the pivot nutrient
(Coach rollup / BP focus) — a food with no numeric sodium is dropped.

Deterministic: rows sorted by canonical, gzip mtime pinned to 0, generatedAt is
a fixed source-version string (not wall-clock). Same inputs -> identical bytes.

Usage:
  python3 build_nutrition_asset.py            # build from committed intermediates
  python3 build_nutrition_asset.py --check    # rebuild to a buffer and diff vs committed asset
"""
import argparse
import csv
import gzip
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
KT = os.path.join(REPO, "app/src/main/java/com/silverbp/android/nutrition/NutritionDatabase.kt")
ASSET = os.path.join(REPO, "app/src/main/assets/nutrition/foods.v1.json.gz")
SCHEMA_VERSION = 1
# Fixed provenance string (keeps output deterministic — no wall-clock).
GENERATED_AT = "TFDA-2025-07 + curated-exclusions"
SOURCES = ["TFDA-8543 (政府資料開放授權條款 v1)"]

# Bare category words that must NOT become standalone English aliases — they are
# substrings of countless dish names and caused the 滷味->boiled-greens bug.
BARE_NOUN_BLOCKLIST = {
    "vegetable", "vegetables", "greens", "green", "egg", "eggs", "rice", "tofu",
    "meat", "meats", "soup", "soups", "fish", "fruit", "fruits", "juice",
    "coffee", "tea", "bread", "soda", "milk", "water", "sauce", "candy",
    "snack", "dessert", "noodle", "noodles", "bean", "beans", "nut", "nuts",
    "seed", "seeds", "oil", "sugar", "salt", "drink", "powder",
}

# Per-100g asset is portion-agnostic; defaultPortionGrams is a rough serving
# hint per TFDA 食品分類, seeded from the curated records' hand-tuned values.
CATEGORY_PORTION = {
    "穀物類": 200.0, "澱粉類": 200.0, "蔬菜類": 120.0, "菇類": 100.0,
    "藻類": 50.0, "水果類": 150.0, "肉類": 120.0, "魚貝類": 120.0,
    "蛋類": 55.0, "豆類": 100.0, "乳品類": 240.0, "飲料類": 350.0,
    "油脂類": 15.0, "糖類": 15.0, "調味料及香辛料類": 15.0,
    "堅果及種子類": 30.0, "糕餅點心類": 60.0, "加工調理食品及其他類": 150.0,
}
DEFAULT_PORTION = 100.0

# Categories whose sodium is especially unreliable from a photo.
UNCERTAIN_CATEGORIES = {"調味料及香辛料類", "加工調理食品及其他類"}
# Name keywords (CJK + latin) flagging high-sodium / hard-to-judge preparation.
UNCERTAIN_KEYWORDS = [
    "湯", "滷", "鹵", "醃", "漬", "醬", "泡菜", "鹹", "燴", "羹", "火鍋",
    "罐頭", "香腸", "培根", "醬油", "鹽",
    "soup", "broth", "braised", "pickled", "brined", "cured", "sauced",
    "stewed", "marinated", "canned", "instant", "sausage", "bacon", "sauce",
]


def normalize(s):
    """Match NutritionDatabase.normalize: lowercase, strip space/dash/underscore."""
    return (s or "").lower().replace(" ", "").replace("-", "").replace("_", "").strip()


def parse_curated_keys(kt_path):
    """Extract every curated canonical + alias (normalised) from the .kt source."""
    text = open(kt_path, encoding="utf-8").read()
    keys = set()
    # NutritionRecord("CANON", listOf("a", "b", ...), ...)
    for m in re.finditer(r'NutritionRecord\(\s*"([^"]+)"\s*,\s*listOf\(([^)]*)\)', text):
        keys.add(normalize(m.group(1)))
        for alias in re.findall(r'"([^"]+)"', m.group(2)):
            keys.add(normalize(alias))
    return keys


def num(x):
    x = (x or "").strip().replace(",", "")
    if x == "":
        return None
    try:
        return float(x)
    except ValueError:
        return None


def is_uncertain(name, alias, name_en, category):
    if category in UNCERTAIN_CATEGORIES:
        return True
    hay = f"{name} {alias} {name_en}".lower()
    return any(k in hay for k in UNCERTAIN_KEYWORDS)


def build_aliases(alias_zh, name_en, canon, curated_keys):
    out = []
    seen = set()
    for cand in (alias_zh, name_en):
        cand = (cand or "").strip()
        if not cand:
            continue
        n = normalize(cand)
        if not n or n == normalize(canon) or n in seen:
            continue
        # Drop bare-noun English category words (substring-collision risk).
        if n in BARE_NOUN_BLOCKLIST:
            continue
        # Drop anything that collides with a curated key (curated owns it).
        if n in curated_keys:
            continue
        seen.add(n)
        out.append(cand)
    return out


def load_overrides():
    """Optional manual blocklist (ids/names to drop) + alias additions."""
    block = set()
    bpath = os.path.join(HERE, "data/overrides/blocklist.csv")
    if os.path.exists(bpath):
        with open(bpath, encoding="utf-8") as f:
            for row in csv.reader(f):
                if row and not row[0].startswith("#"):
                    block.add(normalize(row[0]))
    return block


def read_wide(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def build_records():
    curated_keys = parse_curated_keys(KT)
    blocklist = load_overrides()
    rows = []
    rows += read_wide(os.path.join(HERE, "data/tfda_wide.csv"))
    rows += read_wide(os.path.join(HERE, "data/usda_wide.csv"))  # optional

    report = {"in": len(rows), "no_sodium": 0, "curated_collision": 0,
              "blocked": 0, "dup": 0, "out": 0}
    out = {}  # normalized canonical -> record (dedup, first wins)
    for r in rows:
        name = (r.get("name") or "").strip()
        # TFDA appends "平均值" (= averaged value) to some sample names; it is a
        # data-collection artifact, not part of the food name. Strip it so the
        # displayed label reads cleanly (and so averaged duplicates of curated
        # foods, e.g. 滷蛋平均值 -> 滷蛋, get dropped by the curated-collision rule).
        if name.endswith("平均值"):
            name = name[:-3].strip()
        if not name:
            continue
        sodium = num(r.get("sodium"))
        if sodium is None:
            report["no_sodium"] += 1
            continue
        ncanon = normalize(name)
        if ncanon in curated_keys:
            report["curated_collision"] += 1
            continue
        if ncanon in blocklist:
            report["blocked"] += 1
            continue
        if ncanon in out:
            report["dup"] += 1
            continue
        category = (r.get("category") or "").strip()
        aliases = build_aliases(r.get("alias"), r.get("name_en"), name, curated_keys)
        rec = {
            "c": name,
            "a": aliases,
            "na": round(sodium, 2),
            "kc": round(num(r.get("kcal")) or 0.0, 1),
            "p": round(num(r.get("protein")) or 0.0, 2),
            "f": round(num(r.get("fat")) or 0.0, 2),
            "cb": round(num(r.get("carb")) or 0.0, 2),
            "g": CATEGORY_PORTION.get(category, DEFAULT_PORTION),
            "u": is_uncertain(name, r.get("alias"), r.get("name_en"), category),
        }
        out[ncanon] = rec
    records = [out[k] for k in sorted(out.keys())]
    report["out"] = len(records)
    return records, report


def serialize(records):
    doc = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": GENERATED_AT,
        "sources": SOURCES,
        "records": records,
    }
    payload = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:  # mtime=0 -> deterministic
        gz.write(payload)
    return buf.getvalue(), payload


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="rebuild and diff against the committed asset; exit 1 if different")
    args = ap.parse_args()

    records, report = build_records()
    gz_bytes, raw = serialize(records)

    print("== nutrition asset build ==")
    for k, v in report.items():
        print(f"  {k}: {v}")
    print(f"  raw json: {len(raw)} bytes  gz: {len(gz_bytes)} bytes")
    # Invariant: every record has a finite, >= 0 sodium.
    bad = [r["c"] for r in records if not isinstance(r["na"], (int, float)) or r["na"] < 0]
    assert not bad, f"records with bad sodium: {bad[:5]}"

    if args.check:
        if not os.path.exists(ASSET):
            print("CHECK FAIL: asset missing", file=sys.stderr)
            sys.exit(1)
        existing = open(ASSET, "rb").read()
        if existing != gz_bytes:
            print("CHECK FAIL: committed asset differs from regenerated output", file=sys.stderr)
            sys.exit(1)
        print("CHECK OK: committed asset matches regenerated output")
        return

    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    with open(ASSET, "wb") as f:
        f.write(gz_bytes)
    print(f"  wrote {ASSET}")


if __name__ == "__main__":
    main()
