import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

// v13.17: 待裁决清单页——把「冲突可见 + 可裁决」的接线固化成测试。
// v13.19: 用例来源原则——「不生成」「暂缓」两类不可混入待裁决视图。
// 重点覆盖：默认按待裁决过滤且带 manualRequired、映射文案（含新语义）、
// 裁决按钮提交正确参数并刷新清单、接口失败不崩。

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'ffff967b' } })
}))

vi.mock('@/api/evidence', () => ({
  listEvidenceConflicts: vi.fn(),
  submitEvidenceVerdict: vi.fn()
}))

import EvidenceConflicts from './EvidenceConflicts.vue'
import { listEvidenceConflicts, submitEvidenceVerdict } from '@/api/evidence'

// el-table 依赖 ResizeObserver，jsdom 未实现
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const ROWS = [
  {
    id: 'ffff967b-ec11aa22bb33',
    conflictKey: 'ec11aa22bb33',
    dimension: 'STATE_FLOW',
    anchor: '订单状态流转',
    direction: 'PRD_ONLY',
    requirementState: 'NEW',
    authority: 'prd',
    manualRequired: false,
    reason: '新增需求：以 PRD 为准（代码可能尚未实现，或已实现但与业务意图不符）',
    verdict: 'pending'
  },
  {
    id: 'ffff967b-ec44cc55dd66',
    conflictKey: 'ec44cc55dd66',
    dimension: 'STATE_FLOW',
    anchor: '退款状态流转',
    direction: 'MIXED',
    requirementState: 'STABLE',
    authority: 'human',
    manualRequired: true,
    reason: '存量需求：PRD 有、代码无，可能漏实现或需求已废弃，需人工裁决',
    verdict: 'pending'
  }
]

function mountView() {
  return mount(EvidenceConflicts, { global: { plugins: [ElementPlus] } })
}

describe('EvidenceConflicts 待裁决清单', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listEvidenceConflicts.mockResolvedValue({ data: { conflicts: ROWS, total: 2, pending: 1 } })
    submitEvidenceVerdict.mockResolvedValue({ data: {} })
  })

  it('默认按「待裁决」过滤且只取需人工的项，并渲染映射文案', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 第三个参数很关键：skip/deferred 虽未裁决，但不该进待裁决视图
    expect(listEvidenceConflicts).toHaveBeenCalledWith('ffff967b', 'pending', true)

    const text = wrapper.text()
    expect(text).toContain('共 2 条，其中待裁决 1 条')
    expect(text).toContain('订单状态流转')
    expect(text).toContain('退款状态流转')
    // 方向映射
    expect(text).toContain('PRD 有·代码无')
    expect(text).toContain('两侧均有差异')
    // 需求状态映射
    expect(text).toContain('新增')
    expect(text).toContain('存量')
    // 权威归属映射：自动判给 PRD 的与需人工的都要能看出来
    expect(text).toContain('需人工裁决')
  })

  it('点击「以 PRD 为准」提交裁决，并刷新清单', async () => {
    const wrapper = mountView()
    await flushPromises()
    listEvidenceConflicts.mockClear()

    const button = wrapper
      .findAll('button')
      .find((b) => b.text().trim() === '以 PRD 为准')
    expect(button, '应渲染「以 PRD 为准」操作按钮').toBeTruthy()

    await button.trigger('click')
    await flushPromises()

    expect(submitEvidenceVerdict).toHaveBeenCalledWith(
      'ffff967b',
      'ec11aa22bb33',
      'prd_authoritative'
    )
    expect(listEvidenceConflicts).toHaveBeenCalled()
  })

  it('点击「以代码为准」提交 code_authoritative', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper
      .findAll('button')
      .find((b) => b.text().trim() === '以代码为准')
    expect(button, '应渲染「以代码为准」操作按钮').toBeTruthy()

    await button.trigger('click')
    await flushPromises()

    expect(submitEvidenceVerdict).toHaveBeenCalledWith(
      'ffff967b',
      'ec11aa22bb33',
      'code_authoritative'
    )
  })

  it('切到「全部」时不带 manualRequired，以便看到跳过/暂缓的全貌', async () => {
    const wrapper = mountView()
    await flushPromises()
    listEvidenceConflicts.mockClear()

    // 第 2 个单选项为「全部」
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios.length).toBe(2)
    await radios[1].setValue(true)
    await flushPromises()

    expect(listEvidenceConflicts).toHaveBeenCalledWith('ffff967b', '', false)
  })

  it('「不生成」「暂缓」展示为各自文案，不得误显示为需人工', async () => {
    listEvidenceConflicts.mockResolvedValue({
      data: {
        conflicts: [
          {
            conflictKey: 'ec-skip',
            dimension: 'STATE_FLOW',
            anchor: '代码多出的状态',
            direction: 'CODE_ONLY',
            requirementState: 'STABLE',
            authority: 'skip',
            manualRequired: false,
            reason: '存量需求：代码有、PRD 无，属文档滞后，无需求依据不生成用例（建议补 PRD）',
            verdict: 'pending'
          },
          {
            conflictKey: 'ec-defer',
            dimension: 'STATE_FLOW',
            anchor: '变更中的流程',
            direction: 'PRD_ONLY',
            requirementState: 'MODIFIED',
            authority: 'deferred',
            manualRequired: false,
            reason: '需求变更中：PRD 尚未定稿，暂缓生成，等 PRD 更新后重跑',
            verdict: 'pending'
          }
        ],
        total: 2,
        pending: 0
      }
    })

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('不生成用例')
    expect(text).toContain('暂缓')
    // 这两类都不该占用人的注意力
    expect(text).not.toContain('需人工裁决')
    expect(text).toContain('其中待裁决 0 条')
  })

  it('清单接口失败时不崩，清单保持为空', async () => {
    listEvidenceConflicts.mockRejectedValue(new Error('boom'))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('共 0 条，其中待裁决 0 条')
    expect(wrapper.findAll('button').length).toBeGreaterThan(0)
  })

  it('空清单渲染空态文案而非报错', async () => {
    listEvidenceConflicts.mockResolvedValue({ data: { conflicts: [], total: 0, pending: 0 } })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无冲突记录')
  })
})
