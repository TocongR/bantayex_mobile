# Task: exact monitoring rules + a "Setup tips" screen before calibration

Before doing anything, read `AGENTS.md` in the project root and follow it strictly (architecture,
folder layout, XML style, colors, buttons, manifest, code style). Then open these existing files as
your reference, because the new work must look and behave like them:

- `features/student/examrules/ExamRulesActivity.kt`, `ExamRulesViewModel.kt`
- `res/layout/activity_exam_rules.xml`
- `features/student/calibration/CalibrationActivity.kt`
- `AndroidManifest.xml`, `res/values/styles.xml`, `res/values/colors.xml`

## Goal

Students should know exactly what counts as a violation, and how to set up their phone, light and
position so monitoring is accurate and does not flag them unfairly. Two changes:

1. **Rewrite the content of the existing Exam Rules screen** with the exact monitoring rules.
2. **Add one new screen, `SetupTipsActivity`**, between Exam Rules and Calibration, with visuals + tips.

New student flow:

```
EnterCode → ExamDetails → ExamRules (schedule gate, unchanged) → SetupTips (NEW) → Calibration → TakeExam → Result
```

## Part 1 — Exam Rules screen (edit existing files only)

Edit `res/layout/activity_exam_rules.xml`. Keep the same structure, ids (`btnBack`, `btnContinue`),
card style and button style. Keep the "Your privacy" card exactly as it is.
Replace the first two cards with the content below (keep the title "Before you begin", and keep the
subtitle sentence). Keep the button text as "Continue to exam" → change it to **"Continue"** because the next screen is now tips, not the exam.

**Card 1 — heading: "Rules to follow"** (body as bullets, `\n`-separated like the current layout):

- Keep your face visible. If your face isn't detected, you get a warning and a beep right away. After 3 seconds it is recorded as a violation.
- Keep your eyes on the screen. If you look away (with your eyes or by turning your head) for 2 seconds, a warning and a beep appear. If you keep looking away for 3 more seconds, it is recorded as a violation.
- Don't glance away again and again. Looking away briefly 3 times within 10 seconds is recorded as a repeated look-away violation.
- Stay on the exam screen. Leaving the app for more than 1.5 seconds is recorded as a violation.
- Take the exam alone, without assistance.

**Card 2 — heading: "Good to know"**:

- Looking at anything off the screen counts as looking away, including down at your desk, lap or notes.
- Normal blinking is fine.
- You get a warning before a look-away or missing-face violation is recorded, so you can correct yourself.
- After one camera violation, the next one can only be recorded 5 seconds later.

These numbers come from the real monitoring code (see the table at the bottom of `AGENTS.md`).
**Do not change any thresholds or anything under `monitoring/`.** If you notice the code and this text
disagree, stop and tell me instead of editing either one.

In `ExamRulesActivity.kt` change only the navigation target: after the schedule check succeeds, start
`SetupTipsActivity` instead of `CalibrationActivity`, forwarding `examId`, `examCode`, `studentName`.
`ExamRulesViewModel` and the schedule gate stay exactly as they are.

## Part 2 — New screen: `SetupTipsActivity`

**Files to create** (follow `AGENTS.md` naming and folders):

- `features/student/setuptips/SetupTipsActivity.kt`
- `res/layout/activity_setup_tips.xml`
- Register it in `AndroidManifest.xml` under the Student group, `exported="false"`.

**No ViewModel.** The screen is static content plus navigation, so write the same kind of KDoc
explanation used in `WelcomeActivity` / `CalibrationActivity`. The Activity only:

- reads `examId`, `examCode`, `studentName` from the intent,
- `btnBack` → `finish()`,
- `btnContinue` → starts `CalibrationActivity` with the same three extras, then `finish()`.

**Visuals are already made.** I'm providing four vector drawables. Copy them unchanged into
`app/src/main/res/drawable/`:

| File | Shows |
|---|---|
| `tips_placement_stand.xml` | face looking straight at the phone, held by a phone stand |
| `tips_placement_tumbler.xml` | same, phone leaning on a tumbler |
| `tips_placement_wall.xml` | same, phone leaning on a wall |
| `tips_face_centered.xml` | phone seen from the front with the face centered on the screen |

They contain **no text on purpose**. All text goes in the layout. They only use `@color/primary` and
`@color/surface`. Don't redraw, recolor or restyle them.

**Layout** — same shell as `activity_exam_rules.xml` (ScrollView, 28dp padding, `btnBack`, title,
subtitle, cards using `@drawable/input_box`, primary button at the bottom). One scrolling screen:

- Title: **"Set up for accurate monitoring"**
- Subtitle: **"A few quick tips help the camera see you clearly, so you don't get flagged by mistake."**

Card 1 — heading **"Place your phone on something stable"**
- Show the three placement drawables in a horizontal row, each in its own equal-width `ImageView`
  (`layout_weight="1"`, `adjustViewBounds`, `contentDescription` set: "Phone on a stand", "Phone on a tumbler", "Phone against a wall").
- Body: "Use a phone stand, a tumbler, a wall, or anything similar that keeps the phone steady. If your desk is low, tilt the phone back a little more. If your desk is high, keep it more upright. The screen should face you straight on."

Card 2 — heading **"Keep your face centered"**
- Show `tips_face_centered` centered, about 160dp tall (`adjustViewBounds`, `contentDescription` "Face centered on the phone screen").
- Body: "Sit about an arm's length from the screen with your whole face in the middle of the view. Keep your head up and facing the screen."

Card 3 — heading **"Lighting"** (text only, no image)
Bullets:
- Face a light source, like a window or lamp in front of or beside you.
- Avoid bright windows or lamps behind you. They turn your face into a dark shape.
- Avoid glare and reflections on your face or glasses.
- Avoid very bright or very dark rooms. Soft, even light works best.
- Keep hair, caps and masks away from your eyes.

Card 4 — heading **"Before you continue"**
Bullets:
- Wipe your front camera lens and make sure the case doesn't cover it.
- Once calibration starts, don't move your phone or change your seat. If you do, calibration needs to be redone.
- Screen brightness is set to maximum automatically.

Button: `btnContinue`, text **"Continue to calibration"**, `style="@style/Widget.App.Button.Primary"`.

Do not add new libraries, a ViewPager, Fragments, or new colors/styles. If you need spacing between the
three placement images, use plain `layout_margin`.

## Out of scope

- Anything in `monitoring/`, `TakeExamActivity`, Firestore, models, or repositories.
- Changing the existing "Position your head in the center of the frame" prompt inside `CalibrationActivity`.
- Translations or moving text to `strings.xml`.

## Done when

1. `./gradlew assembleDebug` passes.
2. Flow works: Exam Rules → Continue (schedule gate still blocks upcoming/expired exams) → Setup Tips → Continue → Calibration, and Back works on both screens.
3. All three extras reach `CalibrationActivity`.
4. Both screens use only existing colors and styles, and `SetupTipsActivity` is in the manifest.
5. Update the "Current student flow" section in `AGENTS.md` (remove the square brackets around `SetupTips`).
6. Reply with a short list of the files you created and changed.