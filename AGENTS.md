Working rules for this repository (read before every task)

The owner tests every change on his real phone, TV and car screen. Speed matters more than automated proof.

Small changes (the default)

- Make the smallest code change that does what was asked. Do not refactor unrelated code.
- Do NOT write new tests or change existing tests.
- Do NOT add or change CI steps, verification scripts, or workflow files.
- Do NOT run the Android emulator, instrumented tests or connectedAndroidTest.
- At most one local ./gradlew assembleDebug, only to check a compile error.
- Commit and push once. If the GitHub build fails, fix that error and push once more. Do not loop.
- Then stop and report briefly in Arabic.

Builds (.github/workflows/android.yml)

- The emulator smoke test runs ONLY when device_tests is true or distribution is validation. Keep it that way, and do not use either unless the owner asks.
- Keep cache-read-only: false on setup-gradle. A normal build should take 4–7 minutes.

Before starting any task

- git pull the branch first; another assistant may have pushed changes.
- Experiment on a separate branch created from ccr-3b14310b-u6y40j; do not break the release branch.

Big features

- Only when the owner calls it a big/new feature may you add tests, and still never make the emulator test mandatory.
