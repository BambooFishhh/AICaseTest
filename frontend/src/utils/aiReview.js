import { getTestCase } from '@/api/testcase'

export function hasSuggestedChanges(review) {
  const s = review?.suggestedChanges
  if (!s) return false
  return Object.values(s).some((v) => v !== null && v !== undefined)
}

export async function pollAiReview(projectId, tcId, { intervalMs = 2000, maxAttempts = 180 } = {}) {
  for (let i = 0; i < maxAttempts; i++) {
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
    const res = await getTestCase(projectId, tcId)
    const review = res.data?.executionHints?.aiReview
    if (!review || review.status !== 'reviewing') return review || null
  }
  return null
}
