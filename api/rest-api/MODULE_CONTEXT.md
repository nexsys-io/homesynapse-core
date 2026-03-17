# rest-api

## Design Doc Reference
<!-- Link to the governing design document in homesynapse-core-docs -->

## Dependencies
<!-- Which modules this module depends on and why -->

## Consumers
<!-- Which modules depend on this module -->

## Constraints
<!-- Locked decisions, invariants, and rules that apply to this module -->

## Idempotency-Key Contract

The REST API accepts an `Idempotency-Key` header on command endpoints to enable safe client retries. The contract has important limitations that must be documented in the OpenAPI spec:

- **Best-effort, not guaranteed across process restarts.** The idempotency key cache is an in-memory LRU structure. If HomeSynapse restarts between the client's first request and its retry, the key is lost and the command may execute again.
- **Cache is lost on restart by design.** This is acceptable for the local-first architecture — the home hub restarts infrequently and the cost of an occasional duplicate command is low compared to the complexity of persisting idempotency keys to SQLite.
- **For non-idempotent commands (`toggle`), clients should use explicit target state.** Instead of `toggle`, clients should use `turn_on` or `turn_off` for reliable retry. The API should encourage this pattern in documentation and deprecation warnings.
- **The OpenAPI spec should document this limitation** when it is written, including guidance on which commands are inherently idempotent (e.g., `set_brightness 75` is idempotent, `toggle` is not).

## Gotchas
<!-- Non-obvious implementation details, known quirks, things to watch for -->
