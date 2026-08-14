import { describe, it, expect } from 'vitest'
import { displayState, displayTrigger } from './stateLabel'

describe('stateLabel', () => {
  it('translates known tokens', () => {
    expect(displayState('PAID')).toContain('已支付')
    expect(displayTrigger('create')).toBe('创建')
    // PAYMENT_ 是常见状态前缀，剥离后按剩余词翻译
    expect(displayTrigger('PAYMENT_SUCCESS')).toBe('成功')
  })

  it('keeps unknown tokens readable', () => {
    expect(displayState('WEIRD_UNKNOWN')).toContain('WEIRD')
  })

  it('handles empty and null input', () => {
    expect(displayState(null)).toBe('')
    expect(displayState('')).toBe('')
    expect(displayTrigger('')).toBe('')
  })
})
