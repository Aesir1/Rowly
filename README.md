# Rowly

An Android rowing tracker. It records a session's route, distance and speed from GPS, and derives
**stroke rate (SPM) from the phone's accelerometer alone** — no boat-mounted sensor, no watch, no
pairing. Put the phone where you normally carry it and row.

---

## How it works

### Stroke rate from a bare accelerometer

`sensors/StrokeRateDetector.kt` is the heart of the app. Raw accelerometer samples arrive at ~50 Hz
and become one SPM estimate per second:

```
onSample(x,y,z,t)
  -> per-axis band-pass 0.08-2.0 Hz (dt-aware IIR, three axes kept separate)
  -> resample onto a fixed 25 Hz grid -> 20 s ring buffer
  -> every second: rotation-invariant normalised autocorrelation over lags 10..250
  -> pick the period, refine parabolically -> spm = 1500 / lag
  -> confidence gate + hysteresis -> Reading.Valid | Reading.NoData(reason)
```

Three decisions carry the whole design:

- **All three axes, never a scalar magnitude.** `sqrt(x²+y²+z²)` rectifies any acceleration
  perpendicular to gravity, so a real 24 SPM stroke reads as 48 and falls out of the valid band.
  Summing the per-axis correlations is invariant under rotation, so phone orientation needs no
  handling at all.
- **Band-pass before resampling.** Hull slap and vibration sit well above the grid's Nyquist limit;
  resampling first would alias a 24.8 Hz buzz down to 0.2 Hz — dead centre of the stroke band.
- **Autocorrelation, not peak counting.** One stroke produces several acceleration extrema (catch,
  drive, finish, seat arrival), so "one peak per stroke" is false at the physics level. Correlation
  is structurally immune: a lone wake hit isn't periodic, so it *lowers* confidence instead of
  injecting a bogus interval.

The detector is pure Kotlin — no Android imports, no clock reads, no threads. All time comes from
the caller, so the test suite replays hours of synthetic signal in milliseconds and gets identical
results every run.

**It never guesses.** `Reading.NoData` carries a reason (`WARMING_UP`, `NO_MOTION`, `NOT_PERIODIC`,
`OUT_OF_RANGE`, `SENSOR_GAP`) and has no SPM field, so an unreliable estimate physically cannot
reach the display or the saved statistics.

### Session state

`recording/RecordingController.kt` is a process-scoped singleton — not owned by a ViewModel (dies
with the screen) nor by the service (only there to keep the process alive and show the
notification). Rotation, recomposition and the screen going off change nothing.

`RecordingForegroundService` wires the accelerometer and the fused location provider into the
controller and mirrors its phase back into sensor registration, so a paused session stops draining
the battery. Samples are buffered and flushed in batches every 10 s — a per-fix insert would be
~5,000 transactions over a 90-minute outing.

### Persistence

Room, schema exported to `app/schemas/` with hand-written migrations. Sessions are irreplaceable,
so nothing is ever destructively migrated.

| Table | Holds |
|---|---|
| `activities` | One finished session; aggregates denormalised so the list is one query |
| `location_points` | Every GPS fix, including ones rejected for distance |
| `stroke_rate_samples` | Accepted SPM readings only |
| `user_settings` | Single row: calibrated sensitivity + the phone/sensor that produced it |
| `calibration_samples` | Phone-vs-ergometer calibration history |

Rejected fixes are still stored: the drawn route is *what actually happened*, while the distance and
speed figures are *what can be trusted*. Those are different questions.

---

## Screens

**Record** — the centre of the navigation bar, a raised circle overlapping the bar. Stroke rate
dominates the layout because it's the reason the app exists. One control: **hold three seconds to
pause**, with a ring that fills as you hold. The friction is deliberate — a brief mis-tap while
rowing must never end a session. While recording, the rest of the app is locked out (bar items
disabled, back swallowed) and the display is held awake, both until that hold completes. Waking is
`FLAG_KEEP_SCREEN_ON`, not a wake lock — it only defers the idle timeout, so pressing power still
locks the phone normally.

**Activities** — every session, newest first. Tapping one opens the route on an OpenStreetMap layer
(osmdroid: no API key, no billing account), speed over distance, and the summary figures.

**Training** — placeholder. Structured sessions are explicitly out of scope for this version.

**Settings → Calibration** — the hardware honesty knob. Real sensors read off and real mounting
positions differ, so the detector's motion floor is tunable rather than a constant. Run one minute
on an ergometer, type in the rate the machine's own display showed, and the app records both numbers
side by side with the sensitivity that produced them. The slider applies to the next recorded
session; the history makes a drift of a few SPM visible instead of assumed away.

**Settings → User** — link only, not built yet.

---

## Architecture

No architecture framework, no Hilt, no Koin, no MVI library. The shape is plain
**Compose + ViewModel + StateFlow**, and every deviation from that is deliberate.

```
   Compose screen        collectAsState()          immutable UI state
        |                      ^
        | intents              |
        v                      |
     ViewModel  ---------------+          thin: forwards intents, answers questions
        |
        v
  RecordingController (process-scoped singleton)   <-- the session's single source of truth
        |                          ^
        | writes                   | sensor + location callbacks
        v                          |
   ActivityRepository        RecordingForegroundService
        |
        v
     Room DAOs
```

### The one rule: state lives below the UI

`RecordingController` is a **process-scoped singleton**, not a ViewModel field and not service
state. A ViewModel dies with its screen; a service is only there to keep the process alive and show
the notification. Because the session lives below both, a rotation, a recomposition, a backgrounded
app or the screen going off changes nothing about what is being recorded.

Each screen exposes exactly one immutable state class (`RecordingUiState`, `CalibrationUiState`, …)
as a `StateFlow`. The UI renders it and sends intents back. There is no two-way binding and no
mutable state shared between layers.

### Pure core, Android shell

The parts worth testing have no Android in them at all:

| Pure (JVM-testable) | Android-aware shell |
|---|---|
| `StrokeRateDetector` | `AccelerometerCollector` (unpacks `SensorEvent`) |
| `TrackAccumulator`, `GeoMath` | `LocationTracker` (fused provider) |
| `SpeedSeries` | `RecordingForegroundService`, Compose screens |

The detector takes no clock and starts no threads — all time arrives as the caller's
`timestampNanos`. That is what lets `app/src/test/` replay hours of synthetic signal in milliseconds
and get bit-for-bit identical results every run. Keep that boundary: anything that reads
`System.currentTimeMillis()` or touches a `Context` belongs in the shell column.

`StrokeRateSource` is a one-method `fun interface` and the **swap seam** — the stroke algorithm can
be replaced wholesale without touching a single caller. The contract a replacement must satisfy is
`StrokeRateDetectorTest`.

### Threading

`RecordingController` is **main-thread confined**. Sensor and location callbacks are delivered on
the main looper and its internal scope is `Dispatchers.Main.immediate`, so its mutable fields need
no locking — the only hop to `Dispatchers.IO` is the database write. Preserve this: adding a
background caller means adding synchronisation that currently doesn't exist.

### Dependency injection

`AppContainer` in `RowlyApplication.kt` — three lazy singletons and a `SettingsDao`. A DI framework
would cost build time and a code-generation step to solve a problem this app doesn't have. The
comment in that file says to revisit at fifteen entries; that is a real threshold, not a joke.

Note there is no repository over `SettingsDao`. A repository that only forwards is a file to open
on the way to the answer. `ActivityRepository` exists because it genuinely composes three DAOs.

### Navigation

One `NavHost` in `ui/navigation/RowlyNavHost.kt`. Tab switches use
`popUpTo(startDestination) { saveState = true }` + `restoreState`, which is what makes returning to
a live Record screen instant. While `RecordingUiState.isLive`, every bar item is disabled, the back
handler swallows back, and `FLAG_KEEP_SCREEN_ON` is held — a live session owns the screen until the
three-second pause hold completes.

---

## Database and migrations

Room, `version = 2`, `exportSchema = true`. The exported JSON in `app/schemas/` **is** the migration
history and is committed to git — never delete or hand-edit it.

`fallbackToDestructiveMigration()` is not used and must not be. A recorded session cannot be
reproduced; wiping a user's training history to avoid writing six lines of SQL is not a trade this
project makes.

### Adding a migration

Worked example already in the tree: `MIGRATION_1_2` in `data/database/RowlyDatabase.kt`, which added
`user_settings` and `calibration_samples` without touching v1 data.

1. **Change the entity** (add a column, add an `@Entity`, add it to the `entities = [...]` list).
2. **Bump `version`** in the `@Database` annotation.
3. **Build once.** KSP writes the new schema to
   `app/schemas/dev.aesir1.rowly.data.database.RowlyDatabase/<N>.json`.
4. **Read the exact SQL Room expects** out of that file:

   ```bash
   python3 -c "import json,sys; [print(e['createSql'].replace('\${TABLE_NAME}', e['tableName']),'\n') for e in json.load(open(sys.argv[1]))['database']['entities']]" \
     app/schemas/dev.aesir1.rowly.data.database.RowlyDatabase/2.json
   ```

5. **Write the `Migration`** using that string verbatim and register it in `addMigrations(...)`.
   Room validates the resulting schema against the JSON at open time and throws if a single column
   type or constraint differs — so copy, do not retype.
6. **Test the upgrade path, not just a fresh install.** Install the *old* build, record something,
   then install the new one over it:

   ```bash
   git stash && ./gradlew :app:installDebug && git stash pop   # old build, make some data
   ./gradlew :app:installDebug                                  # new build, must not crash
   ```

   A fresh install runs `CREATE`, never `migrate()` — it proves nothing about the migration.

### Inspecting the database on a device

```bash
adb shell "run-as dev.aesir1.rowly cat databases/rowly.db" > rowly.db   # debug builds only
sqlite3 rowly.db ".tables"
sqlite3 rowly.db "SELECT id, createdAt, totalDistanceKm, averageSpm FROM activities ORDER BY createdAt DESC LIMIT 5;"
sqlite3 rowly.db "PRAGMA user_version;"                                 # current schema version
```

---

## Commands

`compileSdk` 37, `targetSdk` 34 (validated against Android 14), `minSdk` 29. Requires JDK 17+ and a
device on API 29+.

`gradlew` fails with *"JAVA_HOME is not set and no 'java' command could be found"* when the JDK is
only inside Android Studio, which is the normal case — Studio does not put it on your `PATH`. Point
your shell at the bundled JDK and the SDK tools once (append to `~/.bashrc`, adjust the paths):

```bash
export JAVA_HOME=/opt/android-studio/jbr          # macOS: /Applications/Android\ Studio.app/Contents/jbr/Contents/Home
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"
```

Then `source ~/.bashrc` (or open a new terminal). That covers `gradlew`, `adb`, `keytool`,
`apksigner` and `aapt2` — every command in this README.

### Build and test

```bash
./gradlew :app:assembleDebug            # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest        # JVM unit tests (detector, geo, track, speed series)
./gradlew :app:installDebug             # build + install onto the connected device
./gradlew :app:lintDebug                # Android lint
./gradlew clean                         # when a stale build is suspected, and only then
./gradlew :app:dependencies             # full dependency tree
./gradlew tasks                         # every available task
```

Test report after a failure: `app/build/reports/tests/testDebugUnitTest/index.html`.

### Device

```bash
adb devices                                              # confirm the phone is visible
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb uninstall dev.aesir1.rowly                           # full wipe, including the database
adb shell pm clear dev.aesir1.rowly                      # wipe data, keep the app installed
adb shell am force-stop dev.aesir1.rowly
adb logcat --pid=$(adb shell pidof -s dev.aesir1.rowly)  # only this app's logs
adb logcat -c                                            # clear the log buffer first
```

Useful while working on the recorder:

```bash
adb shell dumpsys activity services dev.aesir1.rowly     # is the foreground service alive?
adb shell dumpsys battery unplug                         # force battery-saver conditions
adb shell settings put secure location_mode 0            # switch location off, exercise the gate
```

### Inspecting an APK

```bash
$ANDROID_HOME/build-tools/*/aapt2 dump badging app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/*/apksigner verify -v app/build/outputs/apk/release/app-release.apk
unzip -l app/build/outputs/apk/release/app-release.apk | sort -k1 -n | tail   # biggest entries
```

---

## Releasing

### Current state — read this first

`app/build.gradle.kts` signs the release build with the **debug key**:

```kotlin
release {
    signingConfig = signingConfigs.getByName("debug")
    isMinifyEnabled = true
    isShrinkResources = true
}
```

That exists so `assembleRelease` always produces an APK that actually installs — Android refuses
unsigned APKs outright, and an `app-release-unsigned.apk` sitting in `build/outputs` is a trap that
costs an afternoon to diagnose. **The debug key is public and identical on every machine. It cannot
be published.** Replace it before any real distribution.

### Setting up a real upload key

1. Generate a keystore. Keep it somewhere outside the repo and back it up — losing it means you can
   never update the app under the same identity again.

   ```bash
   keytool -genkeypair -v -keystore ~/keys/rowly-upload.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias rowly
   ```

2. Put the secrets in `keystore.properties` at the repo root (gitignored — never commit it):

   ```properties
   storeFile=/home/you/keys/rowly-upload.jks
   storePassword=…
   keyAlias=rowly
   keyPassword=…
   ```

3. Wire it into `app/build.gradle.kts` (needs `import java.util.Properties` at the top of the
   file), falling back to the debug key when the file is absent so a fresh clone still builds:

   ```kotlin
   val keystoreProperties = Properties().apply {
       val f = rootProject.file("keystore.properties")
       if (f.exists()) f.inputStream().use { load(it) }
   }

   android {
       signingConfigs {
           if (keystoreProperties.isNotEmpty()) {
               create("upload") {
                   storeFile = file(keystoreProperties.getProperty("storeFile"))
                   storePassword = keystoreProperties.getProperty("storePassword")
                   keyAlias = keystoreProperties.getProperty("keyAlias")
                   keyPassword = keystoreProperties.getProperty("keyPassword")
               }
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.findByName("upload")
                   ?: signingConfigs.getByName("debug")
           }
       }
   }
   ```

   In CI, read the same four values from environment variables instead of the file.

### Cutting a build

1. **Bump the version** in `app/build.gradle.kts` — `versionCode` must strictly increase (Play
   rejects a reused one); `versionName` is the human string.
2. Build the artifact:

   ```bash
   ./gradlew :app:assembleRelease   # APK  -> sideloading, direct download
   ./gradlew :app:bundleRelease     # AAB  -> what Google Play requires
   ```

3. **Verify what came out:**

   ```bash
   apksigner verify -v app/build/outputs/apk/release/app-release.apk   # expect v2/v3 signed
   aapt2 dump badging app/build/outputs/apk/release/app-release.apk    # versionCode, targetSdk
   ```

4. **Install and exercise the release build on a device before shipping it.** Release is not debug:
   R8 shrinks and rewrites the code, so this is the first build where a missing keep rule can
   surface. Record a real session, open an activity, check the map draws.
5. Tag the commit so a crash report can be traced back to a build.

### R8

`isMinifyEnabled` and `isShrinkResources` are both on. `app/proguard-rules.pro` is **empty and
currently correct** — Room, Compose and the AndroidX libraries all ship their own consumer rules.

Add a keep rule only when you add something reflective (a JSON parser over data classes, a
`Class.forName`, a library without consumer rules), and only after seeing the release build actually
fail. A speculative `-keep class dev.aesir1.rowly.**` disables the shrinker and silently undoes the
reason it was turned on.

If a release-only crash appears, deobfuscate the trace with the mapping file — keep it, it is
generated per build and is the only way to read that stack:

```
app/build/outputs/mapping/release/mapping.txt
```

---

## Layout

```
app/src/main/java/dev/aesir1/rowly/
  sensors/      StrokeRateDetector (pure), AccelerometerCollector (the only Android-aware part)
  location/     LocationTracker, TrackAccumulator, GeoMath
  recording/    RecordingController (session truth), RecordingForegroundService
  data/         Room entities, DAOs, ActivityRepository
  ui/           record/ activities/ activity/ settings/ training/ navigation/ splash/ theme/
app/src/test/   JVM tests: detector, geo math, track accumulation, speed series
```

## Permissions

`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (route, distance, speed),
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` (record with the screen off),
`POST_NOTIFICATIONS` (the unmissable recording notification),
`INTERNET` + `ACCESS_NETWORK_STATE` (map tiles).

Nothing leaves the device except OpenStreetMap tile requests. There is no account, no backend and
no analytics.
