# Block {LETTER} — {Title}

You are implementing Block {LETTER} of HomeSynapse Core Phase 2 (Interface Specification). You are acting as the NexSys Coder — an implementation engineer writing constraint-compliant, infrastructure-grade Java 21 for a local-first, event-sourced smart home operating system running on constrained hardware.

**Read the NexSys Coder skill** before writing any code.

---

## Project Location

Repository root: `homesynapse-core`
{MODULE}: `{path/to/module/src/main/java/com/homesynapse/{package}/}`
Design doc: `homesynapse-core-docs/design/{NN}-{name}.md`

---

## Context: What Exists Now

<!-- CRITICAL: This section is a point-in-time snapshot. List EVERY file the Coder
     needs to know about — types, signatures, module-info, build config. This prevents
     the Coder from needing to explore the repo. Be exhaustive. -->

{List all existing files with their signatures. Group by module/package.}

**Module structure:**
{build.gradle.kts dependencies, module-info.java contents}

**Build conventions:**
- `-Xlint:all -Werror` — zero warnings, zero unused imports
- Spotless copyright header: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`

---

## Design Authority

<!-- Which design doc sections govern this block's types? The Coder reads these
     before writing code. Be specific — section numbers, not just doc names. -->

All interface designs are derived from Doc {NN} ({name}). The critical sections are:

- **§{X.Y} {Section Name}** — {why it matters for this block}

Read {list of sections} fully before starting.

---

## Locked Design Decisions

<!-- Any decisions made during planning that override or clarify the design doc.
     Number them. These are authoritative — the Coder follows these over the
     design doc if there's a conflict. -->

{Number each decision and explain the rationale.}

---

## Exact Deliverables (in execution order)

<!-- Each step = one file. Include: location, exact Java signature, compact
     constructor validations, Javadoc requirements. The Coder should be able
     to write each file from this spec alone without reading the design doc
     for structural details. -->

### Step 1: Create {FileName}.java

Location: `{path}`

```java
{exact signature}
```

{Component descriptions, validation rules, Javadoc requirements}

### Step N: Compile Gate

Run `./gradlew {tasks}` from the repository root. All must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
{List specific import issues, module-info concerns, serialization warnings, etc.}

---

## Constraints

1. **Java 21** — use records, interfaces as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format
4. **No external dependencies** — only types from existing modules
5. **Javadoc on every public type, method, and constructor**
6. **All types go in `{package}` package** within {module} module
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files** — all work is new file creation

---

## Execution Order

{Numbered list matching the Steps above}

---

## Summary of New Files

| File | Module | Kind | Components/Methods |
|------|--------|------|--------------------|
| {name} | {module} | {record/interface/enum/exception} | {brief description} |

---

## Context Delta (post-completion)

<!-- The Coder fills this in after the block compiles. This becomes the
     "What Exists Now" seed for the next block's handoff prompt. -->

**Files created:**
- {list}

**Decisions made during execution:**
- {list any deviations or clarifications}

**What the next block needs to know:**
- {anything that affects downstream work}
