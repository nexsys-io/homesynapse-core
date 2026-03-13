# ADR-0001: Adopt MADR Format for Architecture Decision Records

## Status

Accepted

## Context

As HomeSynapse Core evolves, architectural decisions need to be recorded so that
current and future contributors understand why things are the way they are. Without
a structured format, decisions get lost in commit messages, chat threads, or
tribal knowledge.

## Decision

We adopt the Markdown Any Decision Record (MADR) format for all architecture
decision records in this repository. ADRs are stored in `docs/decisions/` and
numbered sequentially.

Major architectural decisions that affect multiple subsystems are tracked in the
locked decisions register (`homesynapse-core-docs/governance/HomeSynapse_Core_Locked_Decisions.md`).
ADRs in this repository capture implementation-level decisions scoped to the
codebase.

## Consequences

- Every significant implementation decision gets a lightweight written record.
- New contributors can read the decision log to understand the rationale behind
  the current architecture.
- The format is simple enough that writing an ADR takes minutes, not hours.
