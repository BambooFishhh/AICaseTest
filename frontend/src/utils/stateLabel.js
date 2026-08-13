// 状态机展示翻译：把英文枚举/触发词转成中文，保留原始值便于机器识别

const TOKEN_MAP = {
  create: '创建',
  created: '已创建',
  draft: '草稿',
  pending: '待',
  pay: '支付',
  payment: '支付',
  paid: '已支付',
  unpaid: '未支付',
  ship: '发货',
  shipped: '已发货',
  shipping: '配送中',
  deliver: '送达',
  delivered: '已送达',
  confirm: '确认',
  confirmed: '已确认',
  complete: '完成',
  completed: '已完成',
  cancel: '取消',
  cancelled: '已取消',
  close: '关闭',
  closed: '已关闭',
  refund: '退款',
  refunding: '退款中',
  refunded: '已退款',
  reject: '拒绝',
  rejected: '已拒绝',
  approve: '批准',
  approved: '已批准',
  submit: '提交',
  submitted: '已提交',
  start: '开始',
  end: '结束',
  finish: '完成',
  finished: '已完成',
  fail: '失败',
  failed: '失败',
  success: '成功',
  process: '处理',
  processing: '处理中',
  lock: '锁定',
  locked: '已锁定',
  init: '初始',
  initial: '初始',
  new: '新建',
  active: '生效',
  inactive: '失效',
  expired: '已过期',
  normal: '正常',
  abnormal: '异常',
  open: '开启',
  login: '登录',
  logout: '退出',
  save: '保存',
  update: '更新',
  delete: '删除',
  add: '新增',
  input: '输入',
  click: '点击',
  verify: '验证',
  return: '退货',
  returned: '已退货',
  apply: '申请',
  review: '审核',
  approving: '申请中',
  reviewing: '审核中'
}

// 常见状态枚举前缀，翻译前剥离
const STATE_PREFIXES = [
  'STATUS_',
  'STATE_',
  'ORDER_STATUS_',
  'ORDER_',
  'PAYMENT_',
  'PAY_',
  'SHIP_',
  'REFUND_'
]

function stripPrefix(text) {
  let result = text
  let changed = true
  while (changed) {
    changed = false
    for (const p of STATE_PREFIXES) {
      if (result.startsWith(p)) {
        result = result.substring(p.length)
        changed = true
      }
    }
  }
  return result
}

function translate(text, keepOriginal) {
  if (!text || typeof text !== 'string') return text || ''
  const original = text
  const stripped = stripPrefix(text.toUpperCase())
  const tokens = stripped.split(/[_\- ]+/).filter(Boolean)
  const translated = tokens
    .map((t) => TOKEN_MAP[t.toLowerCase()] || t)
    .join('')
  // 没有可翻译内容或已经是中文，原样返回
  if (!translated || translated === original) return original
  return keepOriginal ? `${translated}（${original}）` : translated
}

// 状态名：中文（原始枚举）
export function displayState(name) {
  return translate(name, true)
}

// 触发词/条件：中文为主
export function displayTrigger(text) {
  return translate(text, false)
}
