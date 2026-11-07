import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('..', import.meta.url))
const composeFile = fileURLToPath(new URL('../../docker-compose.invitation-e2e.yml', import.meta.url))
const s3ComposeFile = fileURLToPath(new URL('../../docker-compose.invitation-s3-e2e.yml', import.meta.url))
const args = process.argv.slice(2)
if (args.length > 1 || args.some(argument => argument !== '--s3')) {
  throw new Error('Usage: node e2e/run-invitation-integration.mjs [--s3]')
}
const s3 = args.includes('--s3')
const compose = ['compose', '--project-name', 'kalo-invitation-e2e', '-f', composeFile]
if (s3) compose.push('-f', s3ComposeFile)
function run(command, args) {
  return new Promise(resolve => {
    const child = spawn(command, args, { cwd: frontend, stdio: 'inherit', shell: process.platform === 'win32',
      env: { ...process.env, KALO_EXPECTED_DOCUMENT_PROVIDER: s3 ? 's3' : 'local' } })
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
  if (result !== 0) await run('docker', [...compose, 'logs', '--no-color', '--tail', '160',
    'backend', ...(s3 ? ['object-store', 'object-store-init'] : [])])
  // Only this named fixture project; no developer or standard E2E stack is removed.
  const down = await run('docker', [...compose, 'down', '--volumes', '--remove-orphans'])
  if (down !== 0) result = 1
}
process.exitCode = result
