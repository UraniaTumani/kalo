import test from 'node:test'
import assert from 'node:assert/strict'
import { readInvitationFragment, validActivationPassword, driverInvitationsEnabled } from '../src/lib/driverInvitation.ts'

test('extracts a token from the SMS fragment', () => {
  assert.equal(readInvitationFragment('#invite=' + 'A'.repeat(43)), 'A'.repeat(43))
})
test('rejects missing, short and malformed invitation tokens', () => {
  for (const fragment of ['', '#token=' + 'A'.repeat(43), '#invite=short', '#invite=' + '!'.repeat(43)])
    assert.equal(readInvitationFragment(fragment), null)
})
test('rejects duplicate invitation parameters', () => {
  assert.equal(readInvitationFragment('#invite=' + 'A'.repeat(43) + '&invite=' + 'B'.repeat(43)), null)
})
test('password limits match the BCrypt UTF-8 boundary', () => {
  assert.equal(validActivationPassword('a'.repeat(8)), true)
  assert.equal(validActivationPassword('a'.repeat(72)), true)
  assert.equal(validActivationPassword('é'.repeat(36)), true)
  assert.equal(validActivationPassword('é'.repeat(37)), false)
  assert.equal(validActivationPassword('a'.repeat(73)), false)
})
test('rejects short and blank passwords', () => {
  for (const password of ['', 'short', ' '.repeat(8)]) assert.equal(validActivationPassword(password), false)
})
test('UI defaults to disabled without build configuration', () => {
  assert.equal(driverInvitationsEnabled, false)
})
