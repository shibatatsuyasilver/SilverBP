# Workout + Blood-Pressure Evolution — Memo

_Date: 2026-06-03 · Landed in commit `Evolve SilverBP into a workout + blood-pressure training app`._

## What this was

SilverBP started as a blood-pressure tracking app. It has been evolved into a
**workout + blood-pressure training app**: the BP features stay, and strength
training plus more cardio were added, with **deep BP↔workout linkage** (the
differentiator). Built in 6 phases, parallel-developed; because the phases share
core files (`ServiceLocator`, `SilverBpDatabase`, `AppNavHost`, `CoachEngine`,
`strings.xml`) they landed as one coherent commit.

## Locked product decisions

- **Cardio**: added 健走 (BriskWalking) + 騎車 (Cycling) on top of Walking/Running.
- **Strength**: exercise library (search / body-part filter / favorites), exercise
  detail (muscle groups), structured sessions (sets/reps/weight, notes, skip),
  end-of-workout difficulty feedback.
- **First-launch Google sign-in is a HARD gate**; data is backed up to Google
  Drive (reuses existing Drive infra, not Supabase). ⚠️ This removes the previous
  "no account / offline-first" stance — a user without a Google account cannot
  pass onboarding. Revisit if offline use is needed (could become skippable with
  backup disabled until linked).
- **Deep BP↔workout**: measure BP before/after a workout (associated to the
  session), auto-adjust intensity from recent BP, Coach warns + reduces/gates
  intensity when BP is abnormal.
- **IA**: the Exercise tab is a training hub (課表 / 動作庫 / 歷史 + start picker);
  the 6 bottom tabs are unchanged.
- **Onboarding**: 4 goal-profile steps (goal / experience / weekly availability /
  training style) feed `CoachEngine` plan generation.
- **Theme**: FORGE dark brand (purple primary, lime CTA); `dynamicColor` off by
  default; BP-category semantic colors preserved.

## DB versions

- v12 → **v13**: `exercise_catalog_item`, `strength_workout_session`, `set_log`.
- v13 → **v14**: `bp_workout_association` + `coach_task.skipped` / `.movedDayOffset`.
- Schema JSON `13.json` / `14.json` are checked in.

## Key safety logic

`CoachEngine.shouldAllowWorkout(recentBp)` → `WorkoutBpGate` (Allow / Caution /
Block) and `computeWorkoutIntensity(...)` are the single source of truth and are
conservative: ≥180/110 in 24h → Block; sustained ≥160/100 or latest ≥140/90 or
7-day mean ≥160 → Caution; no readings → Caution (encourage measuring), never a
silent Allow. Covered by boundary tests (`CoachEngineWorkoutGateTest`).

## Testing status

- ~50 new unit tests (BP gating boundaries, sync round-trips, goal profile,
  mappers). All unit tests green; `assembleDebug` passes.
- Full flow verified on an **API 34 Google Play emulator** (theme, 6-step
  onboarding, training hub, library/detail, BP gate dialog, set-logging).
- During emulator testing a temporary debug-only login bypass was added to
  `AppNavHost` and **removed afterwards** (verified clean — no `BuildConfig.DEBUG`
  / `TEMP-TEST-BYPASS` remains).

## Open follow-ups

1. **iOS sync parity** — to sync the new data cross-platform, the iOS client must
   mirror: `SyncEntityType` tags 18–21 (`exercise_catalog_item`,
   `strength_workout_session`, `set_log`, `bp_workout_association`) and the
   `coach_task` payload tags **10 = skipped**, **11 = movedDayOffset**. Adding
   these is backward-compatible (unknown tags are skipped), so iOS not knowing
   them won't crash — it just won't carry the new fields.
2. **v13→v14 migration test** lives in `app/src/androidTest/.../Migration13To14Test`
   (drives the `Migration` object); it needs a connected device/emulator to run —
   wire it into a device/CI run. Unit-level engine + mapper tests already pass.
3. **Multi-exercise routine builder** — the 動作庫 path currently starts a
   single-exercise workout (`liveStore.start(listOf(item))`). Building a multi-move
   routine before starting is not yet implemented.
4. **Strength → today's plan** — `CoachEngine` plan generation still emits
   cardio-oriented exercise tasks; `trainingStyle` only scales aerobic volume.
   Wiring strength tasks into the weekly plan is a later step.
5. **Health Connect gaps** — strength sessions are not written to HC (no first-class
   type); cycling has no plain HC type and no step count (steps UI is guarded).
6. **Strength history detail** — strength history rows show date + duration only
   (shallow sessions); no strength-session detail screen yet.
