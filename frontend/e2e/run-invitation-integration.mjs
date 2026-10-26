import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('..', import.meta.url))
const composeFile = fileURLToPath(new URL('../../docker-compose.invitation-e2e.yml', import.meta.url))
const compose = ['compose', '--project-name', 'kalo-invitation-e2e', '-f', composeFile]
function run(command, args) {
  return new Promise(resolve => {
    const child = spawn(command, args, { cwd: frontend, stdio: 'inherit', shell: process.platform === 'win32' })
    child.on('error', () => resolve(1))
    child.on('close', code => resolve(code ?? 1))
  })
}
let result = 1
try {
  // Clear only this disposable fixture project so fixed seed identities start fresh.
  const clean = await run('docker', [...compose, 'down', '--volumes', '--remove-orphans'])
  const up = clean === 0 ? await run('docker', [...compose, 'up', '-d', '--build', '--wait']) : clean
  if (up !== 0) console.error('Dedicated invitation test stack did not start. Check Docker and port 18881.')
  else result = await run('npx', ['playwright', 'test', '--config', 'playwright.invitation-integration.config.ts'])
} finally {
  if (result !== 0) await run('docker', [...compose, 'logs', '--no-color', '--tail', '160', 'backend'])
  // Only this named fixture project; no developer or standard E2E stack is removed.
  const down = await run('docker', [...compose, 'down', '--volumes', '--remove-orphans'])
  if (down !== 0) result = 1
}
process.exitCode = result
