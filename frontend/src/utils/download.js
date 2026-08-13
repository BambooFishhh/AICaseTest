// 带认证的下载/预览工具
// window.open 打开的新标签页不会携带 Authorization 头，登录后直接打开会 401，
// 这里统一先用带 token 的 fetch 拿到内容，再走 blob 预览/下载。

function authHeaders() {
  return {
    Authorization: `Bearer ${localStorage.getItem('aicase-token') || ''}`
  }
}

export async function authFetch(url) {
  const resp = await fetch(url, { headers: authHeaders() })
  if (!resp.ok) {
    const text = await resp.text().catch(() => '')
    throw new Error(`请求失败(${resp.status}): ${text.slice(0, 120)}`)
  }
  return resp
}

function filenameFromDisposition(resp, fallback) {
  const disposition = resp.headers.get('content-disposition') || ''
  const match = disposition.match(/filename="?([^";]+)"?/)
  return match ? decodeURIComponent(match[1].replace(/^"|"$/g, '')) : fallback
}

// 预览（报告等 HTML）：新标签页打开 blob
export async function openAuthPreview(url) {
  const resp = await authFetch(url)
  const blob = await resp.blob()
  const objUrl = URL.createObjectURL(blob)
  window.open(objUrl, '_blank')
  setTimeout(() => URL.revokeObjectURL(objUrl), 60000)
}

// 下载：blob 触发保存
export async function downloadAuth(url, fallbackName = 'download') {
  const resp = await authFetch(url)
  const blob = await resp.blob()
  const name = filenameFromDisposition(resp, fallbackName)
  const objUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objUrl
  a.download = name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(objUrl), 60000)
}
