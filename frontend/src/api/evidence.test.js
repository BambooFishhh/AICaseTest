import { describe, it, expect, vi, beforeEach } from 'vitest'

// v13.17: 证据冲突接口契约——锁住 URL 形状与参数，避免前端与后端路由漂移
// （后端为 @RequestMapping("/api/projects/{projectId}/evidence-conflicts")，前端 baseURL 已含 /api）
vi.mock('./request', () => ({
  default: { get: vi.fn(), post: vi.fn() }
}))

describe('evidence api', () => {
  let request

  beforeEach(async () => {
    vi.clearAllMocks()
    request = (await import('./request')).default
  })

  it('listEvidenceConflicts 命中清单路由并带上 verdict 过滤', async () => {
    const { listEvidenceConflicts } = await import('./evidence')

    await listEvidenceConflicts('p1', 'pending')

    expect(request.get).toHaveBeenCalledWith('/projects/p1/evidence-conflicts', {
      params: { verdict: 'pending' }
    })
  })

  it('verdict 为空串时不带过滤参数（对应「全部」）', async () => {
    const { listEvidenceConflicts } = await import('./evidence')

    await listEvidenceConflicts('p1', '')

    expect(request.get).toHaveBeenCalledWith('/projects/p1/evidence-conflicts', { params: {} })
  })

  it('manualRequired 为真时带上该参数——待裁决视图只取需人工的项', async () => {
    const { listEvidenceConflicts } = await import('./evidence')

    await listEvidenceConflicts('p1', 'pending', true)

    expect(request.get).toHaveBeenCalledWith('/projects/p1/evidence-conflicts', {
      params: { verdict: 'pending', manualRequired: true }
    })
  })

  it('submitEvidenceVerdict 走 conflictKey 子路由并提交裁决值', async () => {
    const { submitEvidenceVerdict } = await import('./evidence')

    await submitEvidenceVerdict('p1', 'ec11aa22bb33', 'prd_authoritative', '产品已确认')

    expect(request.post).toHaveBeenCalledWith(
      '/projects/p1/evidence-conflicts/ec11aa22bb33/verdict',
      { verdict: 'prd_authoritative', note: '产品已确认' }
    )
  })
})
