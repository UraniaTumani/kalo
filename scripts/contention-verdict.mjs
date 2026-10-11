/**
 * Turns a k6 contention summary into facts the validity gate can test.
 *
 *   node scripts/contention-verdict.mjs <summary.json>
 *
 * Exists so that one implementation of each judgement serves both the gate and
 * the unit tests. The gate is bash and the judgements are arithmetic over a
 * JSON document, which bash reads badly; before this, the overlap rule lived
 * in a `node -e` string inside the gate, where nothing could test it directly.
 * The rule that let a broken metric pass as proof of synchronisation was in
 * that string.
 *
 * Every judgement comes from load/lib/contention.js, which plain node can
 * exercise without a stack, a container or a summary file.
 *
 * Output is one `key=value` per line, values on a single line, for the gate to
 * read with sed. Unknown or absent inputs are reported as empty values rather
 * than as zeros: a missing metric and a metric that measured zero are
 * different facts, and collapsing them is how absent evidence starts reading
 * as good news.
 */
import fs from 'node:fs'
import { assessIntervalOverlap, reconcileAccepts } from '../load/lib/contention.js'

const file = process.argv[2]

if (!file) {
  console.log('error=no summary file given')
  process.exit(2)
}

let summary
try {
  summary = JSON.parse(fs.readFileSync(file, 'utf8'))
} catch (error) {
  console.log(`error=could not read ${file}: ${error.message}`)
  process.exit(2)
}

const metrics = (summary && summary.metrics) || {}

/** A Counter's total, or null when the metric is absent. */
function counter(name) {
  const m = metrics[name]
  if (!m) return null
  const v = m.count ?? m.value ?? (m.values && (m.values.count ?? m.values.value))
  return typeof v === 'number' ? v : null
}

/** One statistic off a Trend, or null when absent. */
function trend(name, stat) {
  const m = metrics[name]
  if (!m) return null
  const v = m.values || m
  const x = v[stat]
  return typeof x === 'number' ? x : null
}

const out = []
const say = (key, value) =>
  out.push(`${key}=${value === null || value === undefined ? '' : value}`)

/* ------------------------------------------------------------- the tallies */

const entered = counter('kalo_race_entered')
const contested = counter('kalo_contested_accepts')
const won = counter('kalo_accept_won')
const lost = counter('kalo_accept_lost')
const unexpected = counter('kalo_accept_unexpected')

say('entered', entered)
say('contested', contested)
say('won', won)
say('lost', lost)
say('unexpected', unexpected)
say('server_errors', counter('kalo_server_errors'))

/*
 * Every recorded reason a virtual user never reached an accept. Named
 * individually because "one attempt went missing" is not actionable and "one
 * booking failed" is.
 */
const bailed = {
  no_fleet: counter('kalo_race_no_fleet') ?? 0,
  no_customer_token: counter('kalo_race_no_customer_token') ?? 0,
  no_booking: counter('kalo_race_no_booking') ?? 0,
  no_partner_token: counter('kalo_race_no_partner_token') ?? 0,
}

for (const [key, value] of Object.entries(bailed)) say(`bailed_${key}`, value)

/* --------------------------------------------------- per-scenario evidence */

say('duplicate_attempts', counter('kalo_duplicate_attempts'))
say('duplicate_refused', counter('kalo_duplicate_refused'))
say('duplicate_accepted', counter('kalo_duplicate_accepted'))
say('cancel_attempts', counter('kalo_cancel_attempts'))
say('cancel_races', counter('kalo_cancel_races'))

/*
 * accept-versus-timeout is exported but not scheduled. Its absence is reported
 * as NOT VERIFIED rather than as nothing, so a reader is never left to infer
 * that a scenario which never ran was fine.
 */
say('timeout_races', counter('kalo_timeout_races'))

/* ------------------------------------------------------------- the overlap */

const timingSamples = counter('kalo_accept_timing_samples')

say('timing_samples', timingSamples)
say('accept_start_min', trend('kalo_accept_start_ms', 'min'))
say('accept_start_max', trend('kalo_accept_start_ms', 'max'))
say('accept_end_min', trend('kalo_accept_end_ms', 'min'))
say('accept_end_max', trend('kalo_accept_end_ms', 'max'))
say('accept_dur_min', trend('kalo_accept_duration_ms', 'min'))
say('accept_dur_max', trend('kalo_accept_duration_ms', 'max'))

/*
 * The diagnostic offset. Nothing decides overlap from it; it is reported so
 * that an impossible reading is visible, and the gate fails on a negative one
 * because the barrier and these timings share a clock.
 */
say('offset_samples', counter('kalo_accept_offset_samples'))
say('offset_min', trend('kalo_accept_offset_ms', 'min'))
say('offset_max', trend('kalo_accept_offset_ms', 'max'))

const overlap = assessIntervalOverlap({
  earliestStartMs: trend('kalo_accept_start_ms', 'min'),
  latestStartMs: trend('kalo_accept_start_ms', 'max'),
  earliestEndMs: trend('kalo_accept_end_ms', 'min'),
  latestEndMs: trend('kalo_accept_end_ms', 'max'),
  samples: timingSamples,
})

say('overlap_ok', overlap.overlapped ? 1 : 0)
say('overlap_all', overlap.allOverlapped ? 1 : 0)
say('overlap_spread_ms', overlap.spreadMs)
say('overlap_reason', overlap.reason.replace(/\s+/g, ' '))

/* --------------------------------------------------------- the arithmetic */

/*
 * Skipped when the denominator is absent.
 *
 * Without kalo_race_entered there is nothing to reconcile against, and
 * reconciling against an assumed zero produces a negative count of vanished
 * attempts — arithmetically true and useless to read. The gate fails on the
 * missing metric itself, which is the honest complaint, so this stays quiet
 * rather than adding a second confusing one.
 */
if (entered === null) {
  say('reconcile_skipped', 1)
  say('reconcile_ok', '')
  say('reconcile_problems', 0)
} else {
  const reconciled = reconcileAccepts({
    entered,
    contested: contested ?? 0,
    won: won ?? 0,
    lost: lost ?? 0,
    unexpected: unexpected ?? 0,
    bailed,
  })

  say('reconcile_skipped', 0)
  say('reconcile_ok', reconciled.balanced ? 1 : 0)
  reconciled.problems.forEach((p, i) =>
    say(`reconcile_problem_${i}`, p.replace(/\s+/g, ' ')),
  )
  say('reconcile_problems', reconciled.problems.length)
}

console.log(out.join('\n'))
