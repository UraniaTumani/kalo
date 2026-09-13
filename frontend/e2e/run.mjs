/**
 * Brings up the E2E stack, runs the browser tests, and takes the stack down
 * again — including when the tests fail, which is when leaving a database and
 * two containers behind would be most annoying.
 *
 * A Node script rather than a chain of shell operators because `&&` and `;`
 * do not mean the same thing on Windows and on a POSIX shell, and this has to
 * work on both.
 */

import { spawn } from 'node:child_process'

const COMPOSE = ['compose', '-f', '../docker-compose.e2e.yml']

function run(command, args, { quiet = false } = {}) {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      stdio: quiet ? 'ignore' : 'inherit',
      shell: process.platform === 'win32',
    })
    child.on('close', (code) => resolve(code ?? 1))
    child.on('error', () => resolve(1))
  })
}

const keepUp = process.argv.includes('--keep-up')
const passthrough = process.argv.filter((arg) => arg !== '--keep-up').slice(2)

console.log('\n▸ starting the E2E stack (its own database, seeded fresh)\n')

const up = await run('docker', [...COMPOSE, 'up', '-d', '--build', '--wait'])

if (up !== 0) {
  console.error(
    '\n✗ the stack did not come up.\n' +
      '  Docker must be running. If a port is taken, `npm run e2e:down` clears a previous run.\n',
  )
  process.exit(up)
}

console.log('\n▸ running the browser tests\n')

const tests = await run('npx', ['playwright', 'test', ...passthrough])

if (keepUp) {
  console.log('\n▸ leaving the stack up (--keep-up). `npm run e2e:down` when finished.\n')
} else {
  console.log('\n▸ tearing the stack down\n')
  await run('docker', [...COMPOSE, 'down', '-v', '--remove-orphans'], { quiet: true })
}

process.exit(tests)
