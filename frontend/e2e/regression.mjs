/**
 * The whole regression suite, in the order that fails cheapest first.
 *
 * Four stages, each a gate on the next:
 *
 *   1. backend      — 240-odd integration tests against a real PostgreSQL
 *   2. typecheck    — tsc, which `npm run build` runs first anyway
 *   3. lint         — oxlint
 *   4. browser E2E  — the slowest, and the one that needs the other three
 *
 * The order is not cosmetic. A browser run costs five to nine minutes and
 * brings up two containers; discovering a compile error that way is the most
 * expensive possible way to learn it. Every stage stops the run on failure, so
 * the first thing you read is the first thing that broke.
 *
 * A Node script rather than a shell chain because `&&` does not mean the same
 * thing in PowerShell as it does in bash, and this has to work on both.
 */

import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const FRONTEND = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const ROOT = path.resolve(FRONTEND, '..')

const WINDOWS = process.platform === 'win32'

/** `./mvnw` on POSIX, `mvnw.cmd` on Windows. */
const MVNW = WINDOWS ? 'mvnw.cmd' : './mvnw'

function run(command, args, cwd) {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      cwd,
      stdio: 'inherit',
      shell: WINDOWS,
    })
    child.on('close', (code) => resolve(code ?? 1))
    child.on('error', () => resolve(1))
  })
}

const stages = [
  {
    name: 'Backend integration tests (Testcontainers PostgreSQL)',
    command: MVNW,
    args: ['-B', 'clean', 'test'],
    cwd: ROOT,
  },
  {
    name: 'Frontend typecheck and build',
    command: 'npm',
    args: ['run', 'build'],
    cwd: FRONTEND,
  },
  {
    name: 'Frontend lint',
    command: 'npm',
    args: ['run', 'lint'],
    cwd: FRONTEND,
  },
  {
    name: 'Browser E2E (isolated stack on ports 55432/18080)',
    command: 'npm',
    args: ['run', 'test:e2e'],
    cwd: FRONTEND,
  },
]

const started = Date.now()

for (const [index, stage] of stages.entries()) {
  console.log(`\n${'='.repeat(72)}`)
  console.log(`▸ ${index + 1}/${stages.length}  ${stage.name}`)
  console.log(`${'='.repeat(72)}\n`)

  const code = await run(stage.command, stage.args, stage.cwd)

  if (code !== 0) {
    console.error(`\n✗ ${stage.name} failed (exit ${code}).`)
    console.error('  The later stages were not run.\n')
    process.exit(code)
  }
}

const minutes = ((Date.now() - started) / 60_000).toFixed(1)

console.log(`\n${'='.repeat(72)}`)
console.log(`✓ Full regression suite passed in ${minutes} minutes.`)
console.log(`${'='.repeat(72)}\n`)
