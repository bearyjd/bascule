# Prompt for the VitalForge repo — root-compat routes

Paste the block below into a session in the `vitalforge` repo. It is written to
be self-contained: that session has no Bascule context.

Tracked in `HANDOFF.md` as open item 2. Blocks ever wiring
`ReplayMigrationWorker`.

---

VitalForge recently moved its weight endpoints behind a per-person prefix:
clients now POST to `/p/<slug>/api/weight` rather than `/api/weight`. The
Bascule Android app is the only writer. I need root-compat routes added, and
the reason is a specific data-loss failure mode rather than tidiness.

**The failure mode, concretely:**

- Bascule builds its request URL as `{configured base URL} + /api/weight`. If a
  user's base URL is still the old root form (`https://weight.grepon.cc`),
  every POST resolves to `/api/weight` and gets a 404.
- Bascule classifies 404 as `PermanentRejection`, not a transient failure. A
  permanently-rejected row is never retried.
- Bascule has a one-shot backfill worker, `ReplayMigrationWorker`, that
  re-queues already-delivered rows as pending after a contract upgrade so
  fields they hold but never sent (BMI, BMR, AMR, body composition) can reach
  the server. It is written and tested but deliberately not wired into any
  scheduling path yet.
- If that worker ever runs while the client's base URL lacks the person
  prefix, the whole backlog posts to root routes, every row 404s, and every row
  is marked permanently failed on its first attempt — unrecoverable without
  manual surgery on the phone's database.

So root routes must stop returning 404 before that worker is wired.

**What to implement:**

1. Keep `/api/weight` and `/api/weight/recent` (plus any other weight endpoint
   that moved) answering at the root path.
2. Resolve them to the primary person — `persons.is_primary = 1`, currently id
   1, slug `bash6632`. This is a single-user deployment; do not invent
   multi-tenant semantics at the root path.
3. Either a real handler that shares the person-scoped implementation, or a
   307/308 redirect that preserves method and body. If you choose a redirect,
   verify the client actually follows it with the POST body intact — a
   redirect that downgrades to GET drops the payload silently, which is a
   worse failure than the 404 because it looks like success.
4. Leave the `extra="forbid"` model behaviour alone. Do not loosen validation
   as part of this change.
5. `/auth/login` already lives at the origin and must stay there. Bascule
   resolves login at the origin deliberately, separately from the
   person-prefixed data routes.

**Acceptance criteria:**

- `POST /api/weight` with a valid payload creates the same row as
  `POST /p/bash6632/api/weight`, attributed to person 1.
- `GET /api/weight/recent` returns the primary person's readings.
- Idempotency holds *across* the two paths: the `client_id` + `captured_at`
  dedup added in PR #39 must treat a root POST and a person-prefixed POST of
  the same reading as one row, not two. This is the criterion most likely to
  be missed.
- A root-route request carrying an unknown field still returns 422, not 500.
- Existing person-prefixed routes are unchanged, with tests covering both
  paths.

Check field names against the actual Pydantic models rather than trusting any
list you are given, including this one.

---

## Not in scope for the server

Whether Bascule should classify 404 as permanent at all is a **client-side**
question, tracked separately as open item 3 in `HANDOFF.md`. Fixing the server
removes the trigger; it does not remove the sharp edge. `submitReading`'s own
KDoc already argues against permanent classification for a bad base URL.
