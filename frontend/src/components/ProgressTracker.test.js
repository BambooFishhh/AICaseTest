import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProgressTracker from './ProgressTracker.vue'

const iconStub = { template: '<i><slot /></i>' }

describe('ProgressTracker', () => {
  it('renders completed message and description', () => {
    const wrapper = mount(ProgressTracker, {
      props: { status: 'completed', message: '处理完成', description: '全部通过' },
      global: { stubs: { 'el-icon': iconStub } }
    })
    expect(wrapper.text()).toContain('处理完成')
    expect(wrapper.text()).toContain('全部通过')
  })

  it('falls back to default text for running status', () => {
    const wrapper = mount(ProgressTracker, {
      props: { status: 'running' },
      global: { stubs: { 'el-icon': iconStub } }
    })
    expect(wrapper.text()).toContain('正在处理中...')
  })
})
