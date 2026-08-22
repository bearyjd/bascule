# hw-probe

Throwaway BLE diagnostic tool used to reverse-verify the Beurer BF720's real
GATT protocol against openScale's documentation before implementing the
actual `BeurerDecoder` in the Bascule app. **Not part of the Bascule
codebase** — separate Gradle project, not built or shipped with `app/`.

Scans, connects, enumerates services/characteristics, enables
notifications/indications, issues reads, and can drive the Bluetooth SIG
User Data Service User Control Point (register/consent) — logging every
byte to Logcat and to an on-device file
(`/sdcard/Android/data/com.ventouxlabs.hwprobe/files/capture.txt`).

Remote-controllable without touching the phone screen, so a session can be
driven from a host machine with only the physical weigh-in requiring a
human:

```
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd scan
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd connect --es addr E7:DB:51:F1:36:91
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd synctime
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd listusers
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd register --ei consent 1234
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd consent --ei idx 2 --ei consent 1234
adb shell am broadcast -a com.ventouxlabs.hwprobe.CMD --es cmd reset
```

Findings from this tool are written up in `docs/prp/03-hardware-validation.md`
and `docs/prp/decisions.md` (ADR-007). This tool itself is not tested,
reviewed, or held to Bascule's own quality bar — it exists to produce
evidence, not to ship.
