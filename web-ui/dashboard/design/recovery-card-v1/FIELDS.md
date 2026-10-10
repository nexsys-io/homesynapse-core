# FIELDS — the recovery card's reads (v1 · 2026-10-09 · `da9ca3d` · mirror v1.1.5)
`C` contract.ts · `L` ListEntitiesEndpoint.java · `T` StandardAvailabilityTracker.java · `A` ZigbeeIntegrationAdapter.java. A1 row keys unless noted. AVAIL-API-1 is cut from §3.

## §1 IN-MIRROR (v1.1.5)
| key | mirror · wire | the sentence it serves |
|---|---|---|
| `availability` | `C:245` (`C:183`) · `L:195` | the row: AVAILABLE → Reporting · UNAVAILABLE → Not responding · UNKNOWN → the fifth |
| `lastReported` | `C:251` · `L:206` | "Last report {t}" on every row; the "since" of Reporting/Quiet; the fallback when `lastSeenAt` is null |
| `deviceId` | `C:250` · `L:200` | the row→device join (the act's target); null → no act |
| `stale` | `C:246` · `L:196` | NOT a card word (SPEC §6 B) |
| `name` · `triggerRef` | `C:244` · `C:522` | the name slot · the third surface's join |

## §2 ON-WIRE-NOT-IN-MIRROR (the mirror bump, SPEC §11 row 1; tri-state)
| key | wire | type · null | serves |
|---|---|---|---|
| `availabilityReason` | `L:213`; the name LOWER-CASED `A:2080–:2081`; `AvailabilityReason.java:27–:49` | `first_contact · ping_success · frame_received · ping_timeout · silence_timeout · leave` \| null (until the first transition, IR-133; or a v1 event) | `ping_timeout` → "(asked twice, no answer)" · `silence_timeout` → passive dark · null ∧ UNAVAILABLE → the fifth state |
| `lastSeenAt` | `L:214–:215` | ISO \| null | "since {t}" of Not responding (last heard); the reply instant on a `ping_success` edge |
| `link` | `L:216` | `{lqi,rssiDbm,at}` \| null | L2 only; never a card word |

## §3 NOT-ON-WIRE (camelCase keys beside `availability`, never under it)
| proposedKey | serves | type · null | lives today | state |
|---|---|---|---|---|
| `reportIntervalSeconds` | "reports at least every 10 minutes" | int \| null = no contract / none declared | `A:897–:903` → `ReportingConfigurator.contractMaxIntervalFor`; passive `A:872–:882`; per sweep, never stored | gap 1 CONFIRMED |
| `silenceLimitSeconds` | "after 11 minutes of silence it is asked" (60/660; passive: declared, else 25 h) | int \| null = uninterviewed | `T:515–:518` (+`T:95`); `T:504–:507`; never stored | gap 1 CONFIRMED |
| `lastProbeOutcome` + `lastProbeAt` | "asked at {t}; it answered" · "asked twice, no answer" · "not asked" | `ok\|timeout\|error` + ISO \| null = not probed this process | `A:816–:821` INFO only; a reply while AVAILABLE publishes nothing (`T:599–:600`) | gap 2 CONFIRMED — the only source of Quiet |
| `probeMisses` | "asked once" vs "asked twice" | int \| null = non-mains | `T:222`; `PROBE_MISSES_TO_DARK` `T:110` | gap 3 CONFIRMED |
| `availabilityClass` | which contract sentence | `mains-metered\|mains-floor\|passive` \| null = uninterviewed (powerSource 0 → passive arm) | `T:550` via `A:470–:471`; `mainsContractFor` present/empty | gap 4 CONFIRMED |
| (no key) seeded-never-asked | the fifth state | = `lastProbeAt` null ∧ UNAVAILABLE | `T:284–:291` seeds UNAVAILABLE; `T:465–:467` skips it every sweep; `A:481` `unknown=` counts `available==null` seeds, which ARE probed (`T:477–:487`) | gap 5 CONFIRMED; a key REFUTED; the seed's `unknown` is not the source |
| `integrationId` | the act's path `POST /api/v1/integrations/{id}/permit-join` | string \| null | `Device.java:56`; no read | CONFIRMED (new) |
| `ieeeAddress` | the act's `scope` (`0x`+16 hex) | string \| null | `Device.hardwareIdentifiers` `("zigbee",hex)` `ZigbeeAdoptionSlice.java:80,:384` | CONFIRMED (new; alt SPEC §12 Q6) |
| the open window for THIS device | the countdown after a reload | a read `{opensAt,closesAt,scope}` \| null | `PairingWindowPort.java:45` has `open` only; `PermitJoinEndpoint.java:247–:253` answers at open; no GET | gap 6 CONFIRMED — a separate unit |
| the join moments | "turned away" · "it is back" | B1 `/api/v1/events` (`C:313`) FROZEN-UNBUILT | `join_rejected` in the store only; "it is back" = the row flips AVAILABLE | B1, not a key |
