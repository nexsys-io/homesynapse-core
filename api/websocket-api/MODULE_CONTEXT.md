# websocket-api

## Design Doc Reference
<!-- Link to the governing design document in homesynapse-core-docs -->

## Dependencies
<!-- Which modules this module depends on and why -->

## Consumers
<!-- Which modules depend on this module -->

## Constraints
<!-- Locked decisions, invariants, and rules that apply to this module -->

## WebSocket Replay/Live Merge Protocol

When a WebSocket client connects and requests event replay (to catch up from a previous position), the server uses a parallel-stream architecture:

1. **During replay, live events continue streaming in parallel.** The server does not pause live event delivery while replaying historical events. Both streams are active simultaneously.
2. **Clients must buffer replay and live events separately.** The client maintains two buffers: one for replay events and one for live events arriving during the replay window.
3. **Server sends `replay_complete` with `endPosition`.** When the replay stream finishes, the server sends a `replay_complete` message containing the `endPosition` — the `globalPosition` of the last replayed event. The client then merges both buffers by `globalPosition`, deduplicating by `eventId`.
4. **Max replay events overflow handling.** If `max_replay_events` is exceeded, the `replay_complete` message includes the position where replay stopped. The client fills the gap via REST `GET /api/v1/events?after_position={pos}` to fetch any events between the replay cutoff and the live stream's starting position.
5. **Ordering guarantees.** Events are always sorted by `globalPosition` within each stream (replay and live), but cross-stream ordering requires client-side merge. The client sorts the merged buffer by `globalPosition` after deduplication.

This protocol ensures zero event loss during client reconnection while keeping the server implementation simple (no complex buffering or pause logic on the server side).

## Gotchas
<!-- Non-obvious implementation details, known quirks, things to watch for -->
