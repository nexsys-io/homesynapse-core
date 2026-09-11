# hero-v1 — the explainability hero, design of record (v1, 2026-09-11)
This folder is the design specification and static mockups for the explainability hero (why did it fire · why didn't it · did it actually confirm): `SPEC.md` (the headline grammar, empty states, confirmation modes, copy table, accessibility, tokens, build rows, open questions) and `states.html` (every state, dark and light, self-contained, no scripts).
It is not shipped and not bundled: nothing here is imported by `src/`, the Vite build or the CI gate; the product name appears only as the token `{{NAME}}`.
Nick rules on SPEC §12 in one batch (`HERO1: <row> <word>`) after the hub's audit; the implementing lane (FE-114 / HERO-1b) works SPEC §11 in order.
Spec version v1, dated 2026-09-11, designed against core HEAD `eabdbb1` and the v1.1.3 read-API mirror (`CONTRACT_VERSION v1.1.3-2026-09-06`).
