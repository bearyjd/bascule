# Changelog

Notable changes, newest first. Generated from conventional commits per the
Phase 5 release gate (`docs/prp/bascule-agent-prompt.md`).

GitHub Releases carry auto-generated notes linking the merged PRs; this file
is the human-readable view and leads with what changed for the person using
the app, not with internals.

## v0.2.0

The release that made capture reliable. v0.1.0 could take a weigh-in only if
the phone happened to be listening at the moment you stepped on; several
failure modes could lose a reading outright. Both are fixed, and the fixes are
proven against the real scale and server rather than only in tests.

### A weigh-in taken while the phone is asleep is no longer lost

The BF720 stores an unattended weigh-in and hands it over **once, on the next
consent**, as ordinary SIG indications — about a second after the consent
write and before the consent response. Bascule enabled those indications
*after* consent, so every such delivery went into the void. They are now
enabled before the handshake, and a measurement frame that arrives mid-handshake
is held and decoded once consent completes. Proven on hardware: a weigh-in at
10:34:51 with the app force-stopped was delivered at 10:36:28 and stored.

Known limit, outside the app's control: the scale files an unattended weigh-in
under the user whose last reading is closest. After a long gap or a large
change it may not recognise you, and the weigh-in is not stored for anyone.

### A reading carries the time you stood on the scale

`captured_at` was the moment the phone collected the reading. For a stored
weigh-in that could be hours late, and it is the timestamp the server and
Garmin use. It is now the scale's own clock when that is plausible, with the
delivery-retry and sync-backlog clocks still anchored to when the phone
received it.

### Three ways a reading could be lost or stalled, closed

- **A wrong Base URL no longer destroys readings.** A 404 was treated as a
  permanent rejection, so a missing person path (`/p/your-slug`) marked every
  reading permanently failed on its first attempt. It is now transient, and
  correcting the URL drains the backlog within seconds instead of up to
  fifteen minutes.
- **An outage no longer stalls delivery for hours.** A failed drain entered
  WorkManager's own retry backoff — up to five hours — and, while it sat
  there, silently swallowed every other delivery trigger, including new
  weigh-ins. Retries are now paced by the readings themselves.
- **The duplicate check against the server actually runs.** It had never
  matched a real response, because it read a field the server does not send.

### Also

- Capture failures record a specific reason that survives the process, so the
  Scale screen can say what happened rather than "no reading".
- A new launcher icon, including a themed (monochrome) variant.
- Internals: the bridge service gained an owner-aware lifecycle, and
  `ConfigViewModel` was split into focused collaborators. No behaviour change.

## v0.1.0

First release. A hands-off bridge from a Beurer BF720 Bluetooth scale to a
VitalForge server: step on the scale, and the weigh-in arrives without
touching the phone.

### Features

- Automatic capture — the app connects to the scale, completes the SIG User
  Data handshake, and listens for a weigh-in, with a foreground fallback for
  when background scanning is not enough.
- Full body composition, not just weight: body fat, body water, muscle,
  BMI, BMR, AMR, impedance and soft lean mass are decoded and delivered.
- Scale registration and profile management, including linking an existing
  profile on a scale that is already registered.
- "Weigh now" — a bounded scan on both the Scale and History screens, for
  when you would rather not wait for the next window.
- VitalForge connection test and username/password login, with encrypted
  credential storage.
- Manual entry for weigh-ins the scale did not capture.
- History with delivery status, capture-state banners that link to the
  setting that fixes them, and a Material 3 interface with an adaptive
  launcher icon.

### Fixes worth knowing about

- **Capture was a lottery, not a bug in the radio.** Sessions listened for
  45 seconds on the assumption that a session began when you stepped on;
  they actually begin when the phone reconnects. Sessions now listen for
  minutes, which took capture from roughly one chance in seven to ~95%
  coverage.
- **A scan registration does not survive the Bluetooth adapter cycling.**
  Toggling Bluetooth or airplane mode silently disabled capture until
  something restarted the app — for eleven hours in the case that found it.
- **A new phone could never pair.** The scale demands an encrypted link and
  starts pairing on the app's first write; nothing waited for the human to
  accept, so registration failed silently two seconds in.
- Delivery is idempotent on `client_id` and anchors deduplication on capture
  time, so a delayed or replayed reading does not duplicate.
- A rejected reading is no longer lost when the cause was configuration:
  switching contract version requeues rows the other contract rejected.

### Known limitations

- One active scale profile at a time.
- AMR is derived, not measured. The scale does not transmit it — no such
  field exists in the SIG Body Composition profile — so it is computed from
  basal metabolism using a coefficient measured from this device. It encodes
  one activity level and reads a few kcal above the scale's own display.
- No instrumented or Compose test lane; UI is verified on hardware, not by
  CI.

### Licence

AGPL-3.0. No openScale source is used — the decoders were reimplemented from
the Bluetooth SIG specifications (see `README.md`).
