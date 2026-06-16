#!/usr/bin/env python3
"""Fetch + normalise the Taiwan TFDA food-composition open dataset (#8543).

Downloads the official CSV bundle, pivots the long format (one row per
食物×分析項) into one row per food with the 5 fields the app needs, and writes
the committed intermediate `data/tfda_wide.csv` consumed by
build_nutrition_asset.py.

Source : 政府資料開放平臺 dataset 8543 / 衛福部食藥署
URL    : https://data.fda.gov.tw/opendata/exportDataList.do?method=ExportData&InfoId=20&logType=2
License: 政府資料開放授權條款-第1版 (free reuse incl. commercial + redistribution, attribution required)

Requires: pandas (reads the quoted CSV with embedded newlines correctly).

Usage: python3 fetch_tfda.py
"""
import io
import os
import zipfile

import pandas as pd
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
URL = ("https://data.fda.gov.tw/opendata/exportDataList.do"
       "?method=ExportData&InfoId=20&logType=2")
OUT = os.path.join(HERE, "data/tfda_wide.csv")

# 分析項 (analysis-item) name -> our column. Values are read from 每100克含量.
NUTRIENTS = {"熱量": "kcal", "粗蛋白": "protein", "粗脂肪": "fat",
             "總碳水化合物": "carb", "鈉": "sodium"}


def download_csv():
    req = urllib.request.Request(URL, headers={"User-Agent": "SilverBP-nutrition-import"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        blob = resp.read()
    # The endpoint returns a ZIP containing a single long-format CSV.
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        name = z.namelist()[0]
        with z.open(name) as f:
            return f.read()


def num(x):
    x = (x or "").strip().replace(",", "")
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def main():
    raw = download_csv()
    df = pd.read_csv(io.BytesIO(raw), encoding="utf-8-sig", dtype=str, keep_default_na=False)
    rows = []
    for gid, g in df.groupby("整合編號"):
        def first(col):
            for v in g[col]:
                if v and v.strip():
                    return v.strip()
            return ""
        rec = {"id": gid, "name": first("樣品名稱"), "alias": first("俗名"),
               "name_en": first("樣品英文名稱"), "category": first("食品分類")}
        for zh, key in NUTRIENTS.items():
            val = None
            for v in g.loc[g["分析項"] == zh, "每100克含量"]:
                val = num(v)
                if val is not None:
                    break
            rec[key] = "" if val is None else val
        rows.append(rec)
    out = pd.DataFrame(rows, columns=["id", "name", "alias", "name_en", "category",
                                      "kcal", "protein", "fat", "carb", "sodium"])
    out = out.sort_values("id")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out.to_csv(OUT, index=False, encoding="utf-8")
    print(f"wrote {OUT}: {len(out)} foods "
          f"({out['sodium'].astype(str).str.len().gt(0).sum()} with sodium)")


if __name__ == "__main__":
    main()
