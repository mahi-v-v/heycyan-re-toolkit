# Shade the cyan_sports (Moyoung) glasses SDK

The cyan_sports glasses use the **Moyoung CRP SDK** (`com.moyoung.glasses.CRPBleClient`),
shipped as a prebuilt AAR. Its generated protobuf classes extend the **full** protobuf runtime
(`com.google.protobuf.GeneratedMessage`).

The rest of the app — **LiveKit**, the **K900** SDK, `androidx.datastore` — uses
**protobuf-javalite**, where the well-known types (e.g. `Timestamp`) extend
`GeneratedMessageLite`. Full and lite protobuf **cannot coexist** on one classpath:

- both define `com.google.protobuf.*` → duplicate-class / resource-merge failures at build, and
- if forced to one runtime, LiveKit crashes at runtime with
  `java.lang.VerifyError: … expected GeneratedMessageLite` (its bytecode was compiled against
  the lite hierarchy).

## The fix

We **shade** the Moyoung SDK: relocate its `com.google.protobuf.*` into the private package
`com.moyoung.shaded.protobuf.*` and bundle a full protobuf runtime there, all inside the AAR.
The app then stays entirely on protobuf-javalite; the Moyoung SDK carries its own protobuf.

The repackaged AAR lives at `modules/sdk/android/libs/cyan_sports_sdk.aar` and is referenced
normally from `modules/sdk/android/build.gradle`. **Do not** add a `protobuf-java` dependency to
the module — that would put full protobuf back on the global classpath and reintroduce the crash.

## Re-running (e.g. when the vendor ships a new SDK)

```bash
cd modules/sdk/tools/shade-cyan-sports-sdk
./shade.sh /path/to/new_vendor_sdk.aar
```

This extracts the vendor AAR's `classes.jar`, runs `shadowJar` (relocate + drop bundled
`.proto` resources), swaps the shaded jar back into a copy of the AAR, and writes the result to
`modules/sdk/android/libs/cyan_sports_sdk.aar`. It prints a verification line — expect
`com/google/protobuf: 0` and `com/moyoung/shaded/protobuf: >0`.

The public Moyoung API (`com.moyoung.glasses.*`) is **not** relocated, so `CyanSportsAdapter.kt`
needs no import changes after re-shading.

## Files

- `shade.sh` — the end-to-end script (uses the project's `android/gradlew`).
- `build.gradle` / `settings.gradle` — a standalone Shadow project that does the relocation.
- `moyoung-classes.jar`, `build/` — transient working artifacts (safe to delete / git-ignore).
