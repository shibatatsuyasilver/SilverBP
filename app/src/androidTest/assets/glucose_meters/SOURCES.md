# Glucose-meter test images — sources, licenses & ground truth

⚠️ **TEST-ONLY FIXTURES.** These images live under `app/src/androidTest/assets/`,
so they ship **only inside the instrumentation (test) APK** and are **never bundled
into the production app** that end users install. They exist purely to validate the
blood-glucose photo-OCR pipeline (`GlucoseRecognitionImageTest` harness).

⚠️ **ATTRIBUTION REQUIRED.** Every image below is under an open license
(CC BY / CC BY-SA / CC0 / Public Domain). The CC BY and CC BY-SA images **require
the listed author attribution** if these fixtures are ever redistributed; this file
is that attribution record. CC BY-SA additionally requires share-alike. Only images
with a **confirmed** open license were committed — no "all rights reserved" or
NonCommercial (CC BY-NC*) images were kept.

**Ground truth was read visually from each image (vision), not copied from the
caption.** Where the caption and the on-screen value disagreed, the on-screen value
wins. Rotated displays were mentally rotated upright before reading.

## Image table

| File | Source (landing page) | License | Author (attribution) | Meter brand / model | Ground-truth reading (as shown) |
|------|----------------------|---------|----------------------|---------------------|---------------------------------|
| `glucose_glucocheckxl_5p3mmol_rotated.jpg` | https://commons.wikimedia.org/wiki/File:(20250417)_Blood_glucose_meter_09.jpg | CC BY-SA 4.0 | Roy Zuo (RoyZuoMedia) | GlucoCheck XL (Aktivmed TD-4277) | **5.3 mmol/L** — digits are rotated ~90° on screen (rotation test case) |
| `glucose_onetouch_ultra_5p9mmol.jpg` | https://commons.wikimedia.org/wiki/File:Glukometr_OT.jpg | Public domain (author release) | Mr Hyde (cs.wikipedia) | OneTouch Ultra (LifeScan) | **5.9 mmol/L** — "mem" recall view; time 11:48, date 31-5 |
| `glucose_gluneo_5p1mmol.jpg` | https://commons.wikimedia.org/wiki/File:Glukometr_Novatin_GluNEO.png | CC BY-SA 4.0 | Lolopusa | Novatin GluNEO | **5.1 mmol/L** — DATE 10.10, PM 12:30 |
| `glucose_contour_ts_8p4mmol.jpg` | https://commons.wikimedia.org/wiki/File:Pmk_4.jpg | CC BY-SA 4.0 | Pmkscan | Bayer Contour TS | **8.4 mmol/L** — "mmol/L" label, meal/drop icons shown |
| `glucose_relion_prime_544mgdl.jpg` | https://www.flickr.com/photos/jeepersmedia/16252791002 | CC BY 2.0 | Mike Mozart (JeepersMedia) | ReliOn Prime (Arkray) | **544 mg/dL** — 5:15PM, 01/06, "mg/dL" label |
| `glucose_relion_prime_425mgdl.jpg` | https://www.flickr.com/photos/jeepersmedia/15633786823 | CC BY 2.0 | Mike Mozart (JeepersMedia) | ReliOn Prime (Arkray) | **425 mg/dL** — 5:25PM, 01/06, "mg/dL" label |
| `glucose_relion_prime_263mgdl.jpg` | https://www.flickr.com/photos/39160147@N03/15631353244 | CC BY 2.0 | Mike Mozart (JeepersMedia) | ReliOn Prime (Arkray) | **263 mg/dL** — 8:14PM, 01/06, "mg/dL" label |
| `glucose_relion_prime_395mgdl.jpg` | https://www.flickr.com/photos/jeepersmedia/15581184674 | CC BY 2.0 | Mike Mozart (JeepersMedia) | ReliOn Prime (Arkray) | **395 mg/dL** — 10:15PM, 01/04, "mg/dL" label |
| `glucose_roundmeter_254mgdl_rotated.jpg` | https://www.flickr.com/photos/jeepersmedia/16201714831 | CC BY 2.0 | Mike Mozart (JeepersMedia) | round generic meter | **254 mg/dL** — display rotated ~90° on screen (rotation test case); "mg/dL" label |
| `glucose_lo_warning_nonnumeric.jpg` | https://www.flickr.com/photos/189590028@N07/50191694906/ (via https://commons.wikimedia.org/wiki/File:Low_Blood_Sugar_-_Glucose_Monitor_-_50191694906.jpg) | CC BY 2.0 | formulatehealth | white round meter | **"Lo"** — out-of-range LOW warning word, NOT a number (edge case: parser must return value=null) |

## Coverage summary

- **Brands:** GlucoCheck XL, OneTouch Ultra (LifeScan), Novatin GluNEO, Bayer Contour TS,
  ReliOn Prime (Arkray ×4), one generic round meter, plus a "Lo" edge-case meter.
- **Units:** 4× mmol/L (5.1, 5.3, 5.9, 8.4) and 5× mg/dL integers (254, 263, 395, 425, 544);
  1× non-numeric "Lo".
- **Rotation cases:** 2 (`glucocheckxl_5p3mmol_rotated`, `roundmeter_254mgdl_rotated`).
- **Edge case:** 1 (`lo_warning_nonnumeric` — verifies "Lo"/"HI" → value null handling).

## Notes & limitations

- A confirmed open-licensed real photo of a **Roche Accu-Chek with an ACTIVE display
  showing a reading** could not be found on Commons/Flickr — every Accu-Chek photo
  located (Aviva, Comfort, Go) showed the meter powered OFF / blank, so none were kept.
  The prompt still prioritises Accu-Chek; this is a fixture-availability gap only.
- The ReliOn Prime values (544/425/395/263) come from one diabetic photographer's set
  (Mike Mozart), so they skew high (hyperglycaemic). They are all distinct, legible,
  and provide the mg/dL integer coverage; a low/normal mg/dL fixture would round out
  the range if a licensed one surfaces.
- Licenses verified from each file's Wikimedia "File:" page or Flickr photo page on
  2026-06-14. Re-verify before any redistribution.
