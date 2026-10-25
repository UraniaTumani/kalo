import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

// Execute the same TypeScript function imported by useDriverTracking,
// without a browser, backend, database, or extra test dependencies.
const source = readFileSync(
  fileURLToPath(new URL('../src/lib/gpsFreshness.ts', import.meta.url)),
  'utf8',
)
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
}).outputText
const { isFreshGpsFix } = await import(
  'data:text/javascript;base64,' + Buffer.from(compiled).toString('base64')
)

const now = 1_800_000_000_000

test('accepts a new GPS fix', () => {
  assert.equal(isFreshGpsFix(now - 1_000, now), true)
})

test('accepts a fix exactly 30 seconds old', () => {
  assert.equal(isFreshGpsFix(now - 30_000, now), true)
})

test('rejects a fix older than 30 seconds', () => {
  assert.equal(isFreshGpsFix(now - 30_001, now), false)
})

test('rejects invalid GPS timestamps', () => {
  for (const timestamp of [NaN, Infinity, -Infinity, undefined]) {
    assert.equal(isFreshGpsFix(timestamp, now), false)
  }
})

test('rejects fixes more than five seconds in the future', () => {
  assert.equal(isFreshGpsFix(now + 5_001, now), false)
  assert.equal(isFreshGpsFix(now + 5_000, now), true)
})
