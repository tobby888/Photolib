import assert from 'node:assert/strict'
import test from 'node:test'
import { FEEDBACK_NEXT_STATUSES, feedbackThreadUrl } from '../src/feedback.ts'

test('反馈通知跳进自己的工单线程', () => {
  assert.equal(feedbackThreadUrl({ eventType: 'FEEDBACK_REPLIED', actionUrl: '/notifications/feedback/42' }),
    '/notifications/feedback/42')
  assert.equal(feedbackThreadUrl({ eventType: 'FEEDBACK_CREATED', actionUrl: '/notifications/feedback/7' }),
    '/notifications/feedback/7')
})

test('其余通知、或缺跳转地址的反馈通知不改去向', () => {
  assert.equal(feedbackThreadUrl({ eventType: 'REQUEST_CREATED', actionUrl: '/requests' }), null)
  assert.equal(feedbackThreadUrl({ eventType: 'DIRECT_MESSAGE', actionUrl: null }), null)
  assert.equal(feedbackThreadUrl({ eventType: 'FEEDBACK_UPDATED', actionUrl: null }), null)
})

test('状态按钮只列后端允许的流转', () => {
  const targets = (status: keyof typeof FEEDBACK_NEXT_STATUSES) =>
    FEEDBACK_NEXT_STATUSES[status].map(next => next.status)
  assert.deepEqual(targets('PENDING'), ['IN_PROGRESS', 'RESOLVED'])
  assert.deepEqual(targets('IN_PROGRESS'), ['RESOLVED'])
  assert.deepEqual(targets('RESOLVED'), ['IN_PROGRESS'])
  for (const status of ['PENDING', 'IN_PROGRESS', 'RESOLVED'] as const) {
    assert.ok(!targets(status).includes(status), `${status} 不能改成自己`)
    assert.ok(!targets(status).includes('PENDING'), '没有回到待处理的路')
  }
})
