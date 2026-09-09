<!-- Generated: 2026-09-08 | Files scanned: 12 (data/) | Token estimate: ~750 -->

# Data

Three stores with three different secrecy classes. Keeping them separate is
deliberate.

## Room — `bascule.db`, schema v4

`BasculeDatabase` · one entity · `exportSchema = true`
(`app/schemas/…/4.json`).

### `readings` (ReadingEntity)

| Group | Columns |
|---|---|
| Identity | `id` (UUID), `capturedAtMillis`, `scaleTimestampMillis`, `userIndex`, `source`, `scaleProfileId` |
| Measurement | `weightKg`, `displayUnit`, `bodyFatPct`, `bodyWaterPct`, `musclePct`, `boneMassKg`, `bmi`, `bmr`, `amr`, `impedanceOhms`, `softLeanMassKg` |
| Delivery | `status`, `attemptCount`, `retryEpochMillis`, `lastAttemptMillis`, `nextAttemptMillis`, `lastError`, `lastErrorClass`, `deliveredFields`, `contractVersionAtDelivery`, `permanentRejectionHttpCode`, `remoteDuplicate` |

`ReadingStatus`: `PENDING` · `SENT` · `FAILED_PERMANENT` · `HELD_CONFIRM` ·
`DECLINED` · `BLOCKED_AUTH`

Migrations: `MIGRATION_3_4` added `permanentRejectionHttpCode`, scoping
contract recovery to 422 rather than every permanent code.

`HELD_CONFIRM` is where a reading lands when it matches a profile that is not
the active one — stored, never delivered, awaiting confirmation.

## DataStore — `bascule_config.preferences_pb`

Non-secret settings (`ConfigStore`):

```
base_url · display_unit · contract_version · always_on_bridging
automatic_capture_enabled · paired_device_address
last_replay_migration_contract_version
```

`readStoredEnum` classifies a persisted enum as `Absent` / `Parsed` /
`Unreadable` rather than collapsing a corrupt value into the default — a
silent revert to `V1_WEIGHT_ONLY` would shrink collection scope with nothing
telling the user.

## Encrypted (AndroidKeyStore, `EncryptedPreferences`)

| Store | Holds |
|---|---|
| `EncryptedScaleProfileStore` | Scale profiles: slot index, consent code, label, address |
| `EncryptedAuthTokenStore` | VitalForge bearer token |
| `EncryptedSessionCookieStore` | `vf_session` cookie |
| `EncryptedConsentStore` | Legacy BF720 consent, migrated lazily |

**The MasterKey lives in the AndroidKeyStore and dies with the install.** A
pulled copy of the consent file is permanently undecryptable, the registration
cannot be recreated without the physical scale, and re-registering burns one
of eight slots. Never uninstall to test something — always `adb install -r`.

## Ingest path

```
ScaleReading → ReadingIngestor
                 profile match → active?  PENDING : HELD_CONFIRM
                 BodyCompositionPlausibility gate
               → ReadingMapper → ReadingEntity
```

`ReadingMapper` derives what the wire does not carry: `bodyWaterPct` from
mass ÷ weight, `bmr` from kJ ÷ 4.184, `amr` from `bmr × ACTIVITY_FACTOR`.

## Backup

`SettingsBackupCodec` + `ScaleProfileCodec` — passphrase-encrypted export and
import of profiles and credentials. This is the only supported way to move an
identity between phones.
