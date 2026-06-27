# Post-M9 ramp — the device-discovery / pairing-wizard seam (DESIGN ONLY)

**Status:** design the seam now, **build after M9**. Nothing in this file is implemented in
the skeleton. The goal is that the wizard drops into the *existing* install/first-run flow
without re-architecting anything the skeleton ships today.

## The first-run journey (where the wizard slots in)

```
 install (.deb / install.sh)
     │  creates user+dirs, starts service, prints token PATH + dashboard URL
     ▼
 service RUNNING  ── loopback :7070, authenticated, READY ──┐
     │                                                      │  ← skeleton ends HERE today:
     │  operator runs `homesynapse-token`, opens dashboard  │     "service up, here's the token"
     ▼                                                      │
 dashboard first-run detection ────────────────────────────┘
     │  (first run = initial_api_token present AND device registry empty)
     ▼
 ┌─────────────────────── PAIRING WIZARD (post-M9) ───────────────────────┐
 │  1. Pair coordinator  → 2. Reset/exclude device → 3. Permit-join +     │
 │     pair-near-the-hub → 4. Live interview progress → 5. device usable   │
 └─────────────────────────────────────────────────────────────────────────┘
     ▼
 paired; token deleted; normal operation
```

The skeleton already produces everything to the left of the wizard box. The wizard is a
**dashboard flow + a pairing API**, neither of which this lane builds. This lane owns only
the **install/first-run seam** that makes the hand-off clean.

## The seam contract — provided NOW vs. added at M9

| Concern | Skeleton provides today | M9 ramp adds |
|---|---|---|
| First-run signal | `initial_api_token` minted + its path printed | dashboard reads "first run" from token-present + empty registry; enters setup mode |
| Reaching the UI | loopback URL printed; LAN opt-in seam documented (E2) | authenticated LAN bind (or the operator's tunnel) so a headless Pi's dashboard is reachable |
| Coordinator device access | unit ships `PrivateDevices=yes` **plus the exact loosening block, commented** | flip to `PrivateDevices=no` + `DeviceAllow=/dev/ttyUSB0 rw` + `SupplementaryGroups=dialout` |
| Pairing primitives | (Core) `CoordinatorProtocol.enablePairing(seconds)`, permit-join already exist | wizard drives them; surfaces interview progress |
| Hand-off copy | installer prints token path + "pair a client, then delete it" | wizard consumes the token, runs setup, deletes it on success |

Because each row is either already present or a **commented, pre-placed change**, landing the
wizard is additive — no skeleton rework.

## Design targets (from the 2026-06-21 explainability research §5)

Every canonical first-run pain reduces to **hidden state the user can't see**. The wizard
beats the field by surfacing it — the same *"never a silent blank"* principle the hero view
uses, applied to onboarding. Four targets, each mapped to a wizard state and the primitive
underneath:

1. **Offer reset/exclusion first.** Devices often arrive already bonded to a prior hub, so the
   wizard's first action for a new device is an explicit *exclude / factory-reset* step — with
   the device-specific reset gesture shown — **before** inclusion. (Primitive: coordinator
   leave/exclude; UI: a "this device looks paired elsewhere — reset it like this" card.)

2. **Pair near the hub, then move it.** Zigbee inclusion must happen within inches of the
   coordinator. The wizard says so up front and only suggests final placement *after* a
   successful interview. (UI: a "bring it close" step with a distance cue; no silent failures
   from a device paired across the house.)

3. **Handle permit-join start-order.** The coordinator must be in permit-join *before* the
   device is triggered, with clear sequencing. The wizard opens the join window
   (`enablePairing(durationSeconds)`), shows the countdown, then prompts the device action —
   never the reverse. (Primitive: `CoordinatorProtocol.enablePairing`; UI: ordered steps with
   a live "join window open — Ns left" timer.)

4. **Surface live interview progress + actionable errors.** No opaque *"interview failed / not
   found in inclusion mode."* The wizard streams interview stages (announced → endpoints →
   clusters → bound) and, on failure, shows what stage failed and the next action, with a link
   to the logs. (UI: a progress list; errors carry a remedy, not just a code.)

## "Never a silent blank" — the onboarding invariant

Every wizard state must show **either** progress, **or** a concrete next action, **or** an
actionable error with a remedy — never an empty spinner. This is the onboarding analogue of
the hero view's no-blank rule, and it is the single design constraint that most reduces the
field's first-run abandonment.

## What this means for the skeleton's seam (so M9 is additive)

- **Keep the token hand-off stable.** The wizard will consume `initial_api_token`; the
  installer already prints its path. Don't change the artifact name or first-run semantics.
- **Pre-place the device-access change.** The unit already carries the exact `PrivateDevices`
  loosening as a commented block — M9 uncomments it; no new unit authoring.
- **Make the dashboard reachable.** The LAN opt-in (E2) is the main Core dependency the wizard
  needs on a headless Pi; until then the documented `ssh -L 7070:127.0.0.1:7070` tunnel reaches
  the loopback dashboard. Recommend prioritising E2 ahead of the M9 wizard build.
- **Curated device set.** The wizard targets the curated set from the device-acquisition brief
  (`context/planning/2026-06-21_device-acquisition-and-test-strategy_brief.md`); the on-device
  72h validation lane exercises pairing against real hardware, not CI.
