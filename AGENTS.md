# AGENTS.md — BantayEx

Read this fully before writing any code. It describes how this project is already built.
**Match the existing patterns exactly.** Do not introduce new architecture, libraries, or styles.
When in doubt, open a similar existing screen and copy its structure.

## What the app is

BantayEx is a native Android app for taking online multiple-choice exams with camera-based
proctoring. A front-camera face/gaze pipeline runs while a student takes the exam and logs
violations that the professor reviews afterwards.

- **Professor** (Firebase Auth, email/password): dashboard, create/edit exam, view results + violation photos.
- **Student** (no account, just exam code + name): enter code → exam details → rules → calibration → take exam → result.

## Tech stack (do not add to it)

- Kotlin, **XML layouts + ViewBinding** (no Compose, no Fragments, no Navigation component — screens are plain Activities)
- **MVVM** with **Hilt** (`@HiltViewModel`, `@AndroidEntryPoint`)
- Coroutines + `StateFlow`
- Firebase Auth + Firestore (Spark plan — keep Firestore usage small)
- CameraX + MediaPipe Face Landmarker for monitoring
- minSdk 24, compile/target SDK 36, Java/Kotlin target 11

## Package layout

Base package: `com.example.bantayex`

```
core/
  constants/   FirestoreConstants.kt
  di/          FirebaseModule.kt            (Hilt module)
  session/     SessionManager.kt
  util/        Result.kt, UiState.kt, CodeGenerator.kt
data/
  model/       Exam, ExamResult, Question, Violation, ViolationProof   (plain data classes)
  repository/  ExamRepository, ResultRepository, ProofRepository
  ExamScheduleHelper.kt, ViolationLabels.kt, ProofImage*.kt
features/
  auth/login/
  professor/{dashboard,createexam,results}/
  student/{entercode,examdetails,examrules,calibration,takeexam,result}/
  welcome/
monitoring/    Gaze/face pipeline (GazeCalculator, CalibrationManager, BlinkDetector, ...)
ui/theme/      Color.kt, Theme.kt, Type.kt
```

**Rules for placing code**

- One folder per screen under `features/<role>/<screen>/`.
- Screen files are named `<Screen>Activity.kt` and `<Screen>ViewModel.kt` (plus `<Screen>Adapter.kt` for lists).
- The layout for `FooBarActivity` is `res/layout/activity_foo_bar.xml`; its binding is `ActivityFooBarBinding`.
- Shared data classes → `data/model/`. Firestore access → `data/repository/`. Never call Firestore from an Activity.
- Do not touch `monitoring/` unless the task explicitly says so. **Never change detection thresholds** unless asked.

## Screen pattern

**Screen with business logic** (network, Firestore, scheduling decisions):
`@AndroidEntryPoint` Activity + `@HiltViewModel` ViewModel. The ViewModel exposes
`StateFlow<UiState<T>?>` (null = idle); the Activity collects it inside
`lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }` and `when`s over
`Loading / Success / Error`. Repositories return `Result.Success / Result.Error`.
Reference implementation: `features/student/examrules/ExamRulesActivity.kt` + `ExamRulesViewModel.kt`.

**Screen with NO business logic** (static content, pure navigation, camera-bound):
**No ViewModel.** Do not create an empty pass-through ViewModel. Say so in a class-level KDoc, the way
`WelcomeActivity`, `ResultActivity` and `CalibrationActivity` do.

**Navigation / data passing:** plain `Intent` with string extras, using the existing literal keys
`"examId"`, `"examCode"`, `"studentName"` (camelCase literals — that is the current convention, keep it).
Every student screen that follows another forwards all three extras. Read them with
`intent.getStringExtra("examId") ?: ""`.

## UI conventions (XML)

The look is **minimal, black and white**. Use only colors from `res/values/colors.xml`:
`primary #2C2C2A`, `text_primary`, `text_secondary #888780`, `text_hint`, `surface #FFFFFF`,
`surface_secondary`, `background`, `divider`, `input_border`, `white`. **Do not hardcode hex colors in layouts or drawables**
(the one existing exception is the camera overlay in the calibration screen — don't copy it).

Standard content screen layout (copy `activity_exam_rules.xml`):

- Root `ScrollView`, `android:background="@color/surface"`, `fillViewport="true"`
- Inner vertical `LinearLayout`, `padding="28dp"`
- Back link: `TextView` with id `btnBack`, text `← Back`, 14sp, `text_secondary`, `layout_marginBottom="24dp"`, `padding="4dp"`
- Title: 22sp, bold, `text_primary`, `marginBottom="8dp"`
- Subtitle: 14sp, `text_secondary`, `lineSpacingMultiplier="1.3"`, `marginBottom="28dp"`
- Content cards: vertical `LinearLayout`, `background="@drawable/input_box"`, `padding="18dp"`, `marginBottom="16dp"`
    - Card heading: 13sp bold `text_primary`, `marginBottom="10dp"`
    - Card body: 13sp `text_secondary`, `lineSpacingMultiplier="1.4"`
- Primary action: `Button` with `style="@style/Widget.App.Button.Primary"` (secondary: `Widget.App.Button.Outlined`).
  **Never repeat button attributes inline** — use the styles in `res/values/styles.xml`.

Other rules:

- User-facing text is written directly in the layout XML (that is how existing screens do it). Follow that;
  do not start a `strings.xml` migration.
- Drawables go in `res/drawable/`, named `snake_case` with a purpose prefix (`status_*`, `code_box_*`, `tips_*`).
  Prefer **vector drawables**; reuse `@drawable/input_box` for bordered cards instead of making new card shapes.
- Camera screens (`CalibrationActivity`, `TakeExamActivity`) are `screenOrientation="portrait"`. Static screens
  don't need that.

## Manifest conventions

Every Activity is registered in `AndroidManifest.xml` with `android:exported="false"` (only `MainActivity`
is exported). Keep entries grouped under the existing comments (Auth / Professor / Student) and add a
short comment for the step if the group uses them.

## Code style

- Short, explicit Kotlin; follow the existing import ordering and 4-space indentation.
- Comments explain **WHY** (the decision), not what the line does. When a screen intentionally has no
  ViewModel, or a check intentionally lives in one place, write that reasoning in a KDoc block.
- No `!!`; use `?:` fallbacks like the existing code. No TODO comments left behind.
- Do not rename or reformat files you aren't changing. Keep diffs small and focused.

## Current student flow (update this list if you change it)

```
EnterCode → ExamDetails → ExamRules (schedule gate) → SetupTips → Calibration → TakeExam → Result
```

The schedule validation lives **only** in `ExamRulesViewModel.checkScheduleAndProceed()` and is
intentionally not re-checked later. Do not add schedule checks to later screens.

## Monitoring behaviour (source of truth for any user-facing rule text)

If you write rules or hints that describe monitoring, they must match the code. Current behaviour
(`TakeExamActivity` + `CalibrationManager`):

| Situation | What the student sees | When it becomes a violation |
|---|---|---|
| Looks away (eyes or head turned off-screen) | Silent for 2 s, then warning banner + 3-beep tone | After 3 more seconds (5 s total) → `LOOK_AWAY` |
| Short glances away (≥ 0.25 s, then back on screen) | nothing | 3 within 10 s → `REPEATED_LOOK_AWAY` |
| Face not detected | Warning + beep immediately | After 3 s → `NO_FACE` |
| Leaves the app / exam screen | notice on return | Away longer than 1.5 s → `APP_EXIT` |

Other facts: normal blinking is ignored; looking down off-screen counts as looking away; camera violations
are spaced at least 5 s apart; up to 10 evidence photos are saved per exam; leaving the app takes no photo;
screen brightness is forced to maximum during calibration and the exam.

## Before you finish any task

1. `./gradlew assembleDebug` must pass.
2. Every new Activity is in the manifest and every new layout has its binding used.
3. No hardcoded hex colors, no inline button attributes, no unused files.
4. Re-read the diff: does each new file sit in the folder this document says it should?