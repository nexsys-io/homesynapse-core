/*
 * WhyNotView — the co-equal hero half: "why didn't it fire?" (B3 mock).
 * The single most differentiated read in the field: no competitor distinguishes
 * condition-false vs trigger-never-matched vs device-didn't-act. We present the
 * verdict as ONE plain sentence + the one gating fact + a next step (research §4).
 * Pick-an-automation mode when no id is supplied.
 */
import { api } from '../lib/api';
import type { AutomationSummary, NonFiringExplanation, SubjectRef } from '../lib/api/contract';
import { useApi } from '../lib/poll';
import { href } from '../lib/router';
import {
  verdictMeta,
  clockTimeWithDate,
  heroCopy,
  parseInstant,
  refLabel,
  type Tone,
  UNRESOLVED_REF_HELP,
  UNRESOLVED_REF_PHRASE,
  UNRESOLVED_REF_PILL,
} from '../lib/format';
import { useRefResolver, type RefResolver } from '../lib/registry';
import { MODE_GLYPHS } from '../lib/verdicts';
import { t, type MessageKey } from '../lib/i18n';
import { Page, Card } from '../components/layout';
import { Resource } from '../components/Resource';
import { StatusPill } from '../components/StatusPill';
import styles from './WhyNotView.module.css';

/** v1.1.3 (FE-113 / CG-1): the trigger's entity, rendered THROUGH the registry
 *  census (FE-HONEST-1 §10-J). Resolved → the display name, linked to the device
 *  list; dangling on a COMPLETE census → LOUD: the ULID verbatim, "not in this
 *  hub's registry", the failing pill (exactly the causal chain's render); an
 *  incomplete/unloaded census → the neutral label, no accusation. Callers pass
 *  only a PRESENT-object ref — null and absent render the sentence alone. */
function TriggerEntity({ subjectRef, resolveRef }: { subjectRef: SubjectRef; resolveRef: RefResolver }) {
  const res = resolveRef(subjectRef.id);
  if (res.kind === 'dangling') {
    return (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--hs-space-2)', flexWrap: 'wrap' }}>
        <span>
          <span style={{ fontFamily: 'var(--hs-font-mono, monospace)' }}>entity {subjectRef.id}</span> — {UNRESOLVED_REF_PHRASE}
        </span>
        <StatusPill tone="error" label={UNRESOLVED_REF_PILL} title={UNRESOLVED_REF_HELP} size="sm" />
      </span>
    );
  }
  return <a href={href(`/devices/${encodeURIComponent(subjectRef.id)}`)}>{refLabel(subjectRef.id, res)}</a>;
}

export function WhyNotView({ automationId }: { automationId?: string }) {
  if (!automationId) return <WhyNotPicker />;
  return <WhyNotDetail automationId={automationId} />;
}

function WhyNotPicker() {
  const autos = useApi(() => api.listAutomations());
  return (
    <Page title="Why didn't it happen?" lede="Choose the automation you expected to run.">
      <Resource state={autos}>
        {(rows: AutomationSummary[]) => (
          <ul class={styles.pick}>
            {rows.map((a) => (
              <li key={a.automationId}>
                <a class={styles.pickRow} href={href(`/explain/why-not/${a.automationId}`)}>
                  <span class={styles.pickName}>{a.name}</span>
                  <span class={styles.pickSummary}>{a.components.map((c) => c.summary).join(' · ')}</span>
                </a>
              </li>
            ))}
          </ul>
        )}
      </Resource>
    </Page>
  );
}

/* ---- HERO-1b B3 (2026-09-12): the why-not card's L1 sentence, body and link per verdict
 * (SPEC §3 N1–N7; the copy is §7 `whyNot.*` behind t()). `{time}` is `lastEvaluation.at`
 * (object-or-null on the observed wire — DX-16), so every row has a no-time arm: N1 and N3
 * carry an explicit `.noTime` key; N4/N6 drop " at {time}" as the §7 slot note says. N3 is
 * an INFERENCE from a non-null run id (a run that lawfully skipped every target also carries
 * one), so it renders in the info register, never ok (Q1 ruled (a)) — until FIRED_CONFIRMED
 * lands (EXPLAIN-6, gated). N6 (noCommandsIssued true) wins over any verdict. */
interface WhyNotCard {
  pill: { label: string; tone: Tone; glyph?: string };
  headline: string;
  body: string | null;
  link: MessageKey | null;
}

function whyNotCard(nf: NonFiringExplanation): WhyNotCard {
  const at = nf.lastEvaluation?.at ?? null;
  const time = parseInstant(at) ? clockTimeWithDate(at) : null;
  /** Drop the " at {time}" clause when the wire carries no time (§7: "null → drop 'at {time}'"). */
  const timed = (key: MessageKey) => (time ? heroCopy(key, { time }) : heroCopy(key, {}).replace(' at {time}', ''));
  const ranFine = nf.verdict === 'NEVER_TRIGGERED' && nf.lastRelevantRunId !== null;
  if (nf.noCommandsIssued === true) {
    return {
      pill: { label: 'Ran, but sent nothing', tone: 'warn', glyph: MODE_GLYPHS.skipped },
      headline: timed('whyNot.headline.sentNothing'),
      body: heroCopy('whyNot.body.sentNothing'),
      link: 'whyNot.link.sentNothing',
    };
  }
  switch (nf.verdict) {
    case 'CONDITION_NOT_MET':
      return {
        pill: verdictMeta(nf.verdict),
        headline: time ? heroCopy('whyNot.headline.conditionNotMet', { time }) : heroCopy('whyNot.headline.conditionNotMet.noTime'),
        body: heroCopy('whyNot.body.conditionNotMet', { triggerSummary: nf.triggerSummary }),
        link: 'whyNot.link.conditionNotMet',
      };
    case 'NEVER_TRIGGERED':
      return ranFine
        ? {
            pill: { label: 'It did run', tone: 'info' },
            headline: time ? heroCopy('whyNot.headline.neverTriggered.ranFine', { time }) : heroCopy('whyNot.headline.neverTriggered.ranFine.noTime'),
            body: heroCopy('whyNot.body.ranFine'),
            link: 'whyNot.link.ranFine',
          }
        : {
            pill: verdictMeta(nf.verdict),
            headline: heroCopy('whyNot.headline.neverTriggered'),
            body: heroCopy('whyNot.neverTriggered.body', { triggerSummary: nf.triggerSummary }),
            link: null,
          };
    case 'ACTED_BUT_UNCONFIRMED':
      return {
        pill: { ...verdictMeta(nf.verdict), glyph: MODE_GLYPHS['timed-out'] },
        headline: timed('whyNot.headline.actedButUnconfirmed'),
        body: heroCopy('whyNot.body.actedButUnconfirmed'),
        link: 'whyNot.link.actedButUnconfirmed',
      };
    case 'DISABLED':
      return { pill: verdictMeta(nf.verdict), headline: heroCopy('whyNot.headline.disabled'), body: heroCopy('whyNot.body.disabled'), link: null };
  }
  // Open-vocabulary hardening (the closed-switch class): a verdict this build does not
  // know is shown as recorded — never a crash, never invented meaning, never success.
  return {
    pill: { ...verdictMeta(nf.verdict), glyph: MODE_GLYPHS['not-recorded'] },
    headline: heroCopy('whyNot.headline.unknown', { verdict: String(nf.verdict ?? '') }),
    body: null,
    link: null,
  };
}

function WhyNotDetail({ automationId }: { automationId: string }) {
  const state = useApi(() => api.getNonFiring(automationId));
  // v1.1.3: the registry census for the trigger ref (the same one-poll-loop read the
  // causal chain uses); 'unverified' until it is in — nothing is accused without it.
  const resolveRef = useRefResolver();
  return (
    <Page title="Why this didn't happen" meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/why-not')}>← Pick another automation</a>
      </p>
      <Resource state={state}>
        {(nf: NonFiringExplanation) => {
          const card = whyNotCard(nf);
          return (
            <Card>
              <div class={styles.detail}>
                <div class={styles.verdictRow}>
                  <StatusPill tone={card.pill.tone} label={card.pill.label} glyph={card.pill.glyph} />
                  <span class={styles.autoName}>{nf.automationName}</span>
                </div>

                <p class={styles.explanation}>{card.headline}</p>
                {card.body ? <p class={styles.body}>{card.body}</p> : null}
                {/* The wire's own `explanation` string is NOT rendered (SPEC §2 gives it no
                    slot): Core's DP-B2 sentence says "last fired and confirmed", a claim the
                    Q1 ruling refuses to make from a run id alone — the keyed L1 + body carry
                    every fact it does. Filed with the hub in the HERO-1b return. */}

                <dl class="kv">
                  <div class="kvRow">
                    <dt>{t('whyNot.kv.trigger')}</dt>
                    <dd class={styles.left}>
                      {nf.triggerSummary}
                      {/* v1.1.3 (FE-113 / CG-1): the R-4 concealment closed on THIS surface —
                          the wire can now name WHICH entity the trigger watches. Rendered
                          only for a PRESENT-object `triggerRef`, through the registry census
                          (resolved → name; dangling → LOUD). PRESENT-null ("names no single
                          entity") and ABSENT (a pre-v1.1.3 hub) both render the sentence
                          alone — two honest facts, neither claims a name. */}
                      {nf.triggerRef ? (
                        <span style={{ display: 'block', marginTop: 'var(--hs-space-1)', fontSize: 'var(--hs-text-sm)' }}>
                          {t('whyNot.kv.watching').replace('{entity}', '')}
                          <TriggerEntity subjectRef={nf.triggerRef} resolveRef={resolveRef} />
                        </span>
                      ) : null}
                    </dd>
                  </div>
                  {/* OBSERVED LIVE NULLABILITY (2026-08-16, §4.5): the wire serves
                      `lastEvaluation: null` — the null case renders ABSENCE (the
                      row simply doesn't render), never fabrication, never a
                      throw. This exact dereference, unguarded, was DX-16's crash
                      (`can't access property "at"`). Date-qualified stamp per
                      NEW-6: an evaluation can be >24 h old. A null conditionsResult
                      beside a time is the FAILED/ABORTED/INTERRUPTED class
                      (StandardExplanationService:281–:288) — said in words. */}
                  {nf.lastEvaluation?.at ? (
                    <div class="kvRow">
                      <dt>{t('whyNot.kv.lastChecked')}</dt>
                      <dd>
                        {clockTimeWithDate(nf.lastEvaluation.at)}
                        {' · '}
                        {nf.lastEvaluation.conditionsResult ?? t('whyNot.kv.lastChecked.unclean')}
                      </dd>
                    </div>
                  ) : null}
                </dl>

                {card.link && nf.lastRelevantRunId ? (
                  <p class={styles.nextStep}>
                    <a href={href(`/explain/run/${nf.lastRelevantRunId}`)}>{t(card.link)}</a>
                  </p>
                ) : null}
              </div>
            </Card>
          );
        }}
      </Resource>
    </Page>
  );
}
