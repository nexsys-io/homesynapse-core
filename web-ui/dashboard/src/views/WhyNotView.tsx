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
  timeAgo,
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
    <Page title={t('explain.whyNot.pick.title')} lede={t('explain.whyNot.pick.lede')}>
      <Resource state={autos} labels={{ loading: t('explain.loading'), errorTitle: t('explain.error.title'), errorBody: t('explain.error.body') }}>
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
 * (object-or-null on the observed wire — DX-16), so every row has a no-time arm: each carries
 * its explicit `.noTime` key (FE-114 D8 retired the " at {time}" string surgery N4/N6 used — the
 * HERO-1c C0 twins are consumed, byte-identical on screen). N3 is an INFERENCE from a non-null
 * run id (a run that lawfully skipped every target also carries one), so it renders in the info
 * register, never ok (Q1 ruled (a)); the v1.1.4 FIRED_CONFIRMED verdict (EXPLAIN-6, FE-114 D3)
 * is the ok arm — Core's claim, shown as recorded. N6 (noCommandsIssued true) wins over any verdict. */
interface WhyNotCard {
  pill: { label: string; tone: Tone; glyph?: string };
  headline: string;
  body: string | null;
  link: MessageKey | null;
}

function whyNotCard(nf: NonFiringExplanation): WhyNotCard {
  const at = nf.lastEvaluation?.at ?? null;
  const time = parseInstant(at) ? clockTimeWithDate(at) : null;
  const ranFine = nf.verdict === 'NEVER_TRIGGERED' && nf.lastRelevantRunId !== null;
  if (nf.noCommandsIssued === true) {
    return {
      // FE-114 D4: the pill labels are §7 rows (`whyNot.pill.*`), byte-identical — the widened lint reaches object properties.
      pill: { label: t('whyNot.pill.sentNothing'), tone: 'warn', glyph: MODE_GLYPHS.skipped },
      headline: time ? heroCopy('whyNot.headline.sentNothing', { time }) : heroCopy('whyNot.headline.sentNothing.noTime'),
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
            pill: { label: t('whyNot.pill.didRun'), tone: 'info' },
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
        headline: time ? heroCopy('whyNot.headline.actedButUnconfirmed', { time }) : heroCopy('whyNot.headline.actedButUnconfirmed.noTime'),
        body: heroCopy('whyNot.body.actedButUnconfirmed'),
        link: 'whyNot.link.actedButUnconfirmed',
      };
    case 'DISABLED':
      return { pill: verdictMeta(nf.verdict), headline: heroCopy('whyNot.headline.disabled'), body: disabledBody(nf), link: null };
    case 'FIRED_CONFIRMED':
      // FE-114 D3 (EXPLAIN-6): the v1.1.4 clean-confirmed-success verdict — Core's claim, shown as recorded
      // ("the record says", the Q1 register). `{time}` is lastEvaluation.at, the EVALUATION instant, so it
      // sits in the run clause. The run link as the other arms; the check glyph pairs the ok tone (label +
      // shape, never hue alone). Placed after the noCommandsIssued check: N6 still wins.
      return {
        pill: { label: t('whyNot.pill.firedConfirmed'), tone: 'ok', glyph: MODE_GLYPHS.confirmed },
        headline: time ? heroCopy('whyNot.headline.firedConfirmed', { time }) : heroCopy('whyNot.headline.firedConfirmed.noTime'),
        body: null,
        link: 'whyNot.link.ranFine',
      };
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

/** FE-114 D2 (EXPLAIN-8, SPEC §6): the DISABLED body when the v1.1.4 `disabledAt` is a VALUE —
 *  "Turned off {when}{reason}. …" with {when} = timeAgo(disabledAt) and {reason} = " — {disabledReason}"
 *  (shown as recorded: "repeated_failure" / "configuration") or "". Null / absent keep HEAD's
 *  "…isn't recorded" body — the tri-state; nothing the wire did not carry is claimed. */
function disabledBody(nf: NonFiringExplanation): string {
  if (typeof nf.disabledAt !== 'string' || !parseInstant(nf.disabledAt)) return heroCopy('whyNot.body.disabled');
  const reason = typeof nf.disabledReason === 'string' && nf.disabledReason !== '' ? ` — ${nf.disabledReason}` : '';
  return heroCopy('whyNot.body.disabled.at', { when: timeAgo(nf.disabledAt), reason });
}

function WhyNotDetail({ automationId }: { automationId: string }) {
  const state = useApi(() => api.getNonFiring(automationId));
  // v1.1.3: the registry census for the trigger ref (the same one-poll-loop read the
  // causal chain uses); 'unverified' until it is in — nothing is accused without it.
  const resolveRef = useRefResolver();
  return (
    <Page title={t('explain.whyNot.title')} meta={state.meta}>
      <p style={{ marginTop: 'calc(-1 * var(--hs-space-2))' }}>
        <a href={href('/explain/why-not')}>{t('explain.whyNot.back')}</a>
      </p>
      {/* HERO-1c correction D3: the hero's own loading / error rows (SPEC §7) ride Resource's labels. */}
      <Resource state={state} labels={{ loading: t('explain.loading'), errorTitle: t('explain.error.title'), errorBody: t('explain.error.body') }}>
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
                      `lastEvaluation: null` — since HERO-1c C6 the null case SAYS so
                      (SPEC §7 `whyNot.kv.neverChecked`, the HERO-1b audit's D7): a fact
                      in the value cell, never fabrication, never a throw. This exact
                      dereference, unguarded, was DX-16's crash (`can't access property
                      "at"`). An object with `at: null` keeps the pre-existing suppression
                      (no time to date-qualify). Date-qualified stamp per NEW-6: an
                      evaluation can be >24 h old. A null conditionsResult beside a time
                      is the FAILED/ABORTED/INTERRUPTED class
                      (StandardExplanationService:281–:288) — said in words. */}
                  {nf.lastEvaluation === null ? (
                    <div class="kvRow">
                      <dt>{t('whyNot.kv.lastChecked')}</dt>
                      <dd>{t('whyNot.kv.neverChecked')}</dd>
                    </div>
                  ) : nf.lastEvaluation?.at ? (
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
