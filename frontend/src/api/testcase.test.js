import { describe, it, expect, vi, beforeEach } from 'vitest'

// v8.5: retryReset 事件接线测试——模拟 case→case→retryReset→case 序列，
// 断言 onRetryReset 恰在第二次与第三次 case 之间触发一次。

const listeners = {}

class FakeEventSource {
  constructor(url) {
    FakeEventSource.lastUrl = url
  }
  addEventListener(type, fn) {
    ;(listeners[type] ||= []).push(fn)
  }
  close() {}
}

function emit(type, data) {
  for (const fn of listeners[type] || []) {
    fn({ data })
  }
}

vi.mock('./request', () => ({
  default: { post: vi.fn(async () => ({ data: { ticket: 'ticket-1' } })) }
}))

describe('testcase SSE retryReset', () => {
  beforeEach(() => {
    for (const key of Object.keys(listeners)) delete listeners[key]
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  it('streamGenerate fires onRetryReset between case batches', async () => {
    const { streamGenerate } = await import('./testcase')
    const events = []
    await streamGenerate('p1', {
      onCase: (tc) => events.push(`case:${tc.title}`),
      onRetryReset: () => events.push('retryReset'),
      onComplete: (d) => events.push(`complete:${d.total}`)
    })

    const caseEvent = (id) =>
      emit('case', JSON.stringify({ testCase: { title: `case-${id}` } }))
    // v8.5 验收序列：case → case → retryReset → case
    caseEvent(1)
    caseEvent(2)
    emit('retryReset', '')
    caseEvent(3)
    emit('complete', JSON.stringify({ total: 1 }))

    expect(events).toEqual([
      'case:case-1',
      'case:case-2',
      'retryReset',
      'case:case-3',
      'complete:1'
    ])
  })

  it('streamGenerateAppend wires onRetryReset too', async () => {
    const { streamGenerateAppend } = await import('./testcase')
    let resetCount = 0
    await streamGenerateAppend('p1', '', {
      onRetryReset: () => { resetCount++ }
    })

    emit('retryReset', '')
    emit('retryReset', '')

    expect(resetCount).toBe(2)
  })

  it('omitted onRetryReset is a no-op (backward compatible)', async () => {
    const { streamGenerate } = await import('./testcase')
    await streamGenerate('p1', {})
    // 不传回调时触发不应抛错
    expect(() => emit('retryReset', '')).not.toThrow()
  })
})
