import assert from 'node:assert/strict'
import test from 'node:test'
import {
  accountHealth,
  defaultPortForSecurity,
  looksLikeEmail,
  moveAccountIds,
  needsSeparateFromAddress,
  portAfterSecurityChange,
} from '../src/smtpAccounts.ts'

test('default ports match SMTP security modes and only auto-switch untouched defaults', () => {
  assert.equal(defaultPortForSecurity('SSL'), 465)
  assert.equal(defaultPortForSecurity('STARTTLS'), 587)
  assert.equal(defaultPortForSecurity('NONE'), 25)

  assert.equal(portAfterSecurityChange(465, 'SSL', 'STARTTLS'), 587)
  assert.equal(portAfterSecurityChange(587, 'STARTTLS', 'NONE'), 25)
  assert.equal(portAfterSecurityChange(2525, 'STARTTLS', 'SSL'), 2525)
  assert.equal(portAfterSecurityChange(undefined, 'SSL', 'STARTTLS'), 587)
})

test('moveAccountIds reorders accounts within bounds and rejects out-of-range moves', () => {
  const accounts = [{ id: 10 }, { id: 20 }, { id: 30 }]
  assert.deepEqual(moveAccountIds(accounts, 0, 1), [20, 10, 30])
  assert.deepEqual(moveAccountIds(accounts, 2, -1), [10, 30, 20])
  assert.equal(moveAccountIds(accounts, 0, -1), null)
  assert.equal(moveAccountIds(accounts, 2, 1), null)
})

test('needsSeparateFromAddress prompts when SMTP username is not an email address', () => {
  assert.equal(looksLikeEmail('bot@example.com'), true)
  assert.equal(looksLikeEmail('apikey'), false)
  assert.equal(looksLikeEmail('AKIA123456'), false)

  assert.equal(needsSeparateFromAddress('apikey', ''), true)
  assert.equal(needsSeparateFromAddress('apikey', 'noreply@example.com'), false)
  assert.equal(needsSeparateFromAddress('bot@example.com', ''), false)
  assert.equal(needsSeparateFromAddress('', ''), false)
})

test('accountHealth reflects enabled state and latest success vs failure timestamps', () => {
  assert.equal(accountHealth({ enabled: false, lastSuccessAt: '2026-11-25T10:00:00' }), 'disabled')
  assert.equal(accountHealth({ enabled: true }), 'idle')
  assert.equal(accountHealth({
    enabled: true,
    lastSuccessAt: '2026-11-25T11:00:00',
    lastFailureAt: '2026-11-25T10:00:00',
  }), 'healthy')
  assert.equal(accountHealth({
    enabled: true,
    lastSuccessAt: '2026-11-25T10:00:00',
    lastFailureAt: '2026-11-25T11:00:00',
  }), 'failed')
  assert.equal(accountHealth({
    enabled: true,
    lastFailureAt: '2026-11-25T11:00:00',
  }), 'failed')
})
