// 状态机展示翻译：英文枚举/触发词 → 中文，保留原始值；未识别的 token 做智能分词后逐词翻译

const TOKEN_MAP = {
  create: '创建',
  created: '已创建',
  draft: '草稿',
  pending: '待',
  pay: '支付',
  payment: '支付',
  paid: '已支付',
  unpaid: '未支付',
  prepay: '预付',
  settled: '已结算',
  clearing: '清算中',
  uncleared: '未清算',
  ship: '发货',
  shipped: '已发货',
  shipping: '配送中',
  deliver: '送达',
  delivered: '已送达',
  logistics: '物流',
  express: '快递',
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
  audit: '审核',
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
  freeze: '冻结',
  frozen: '已冻结',
  suspend: '挂起',
  suspended: '已挂起',
  resume: '恢复',
  resumed: '已恢复',
  block: '阻塞',
  blocked: '已阻塞',
  init: '初始',
  initial: '初始',
  ready: '就绪',
  idle: '空闲',
  waiting: '等待中',
  paused: '已暂停',
  new: '新建',
  active: '生效',
  inactive: '失效',
  valid: '有效',
  invalid: '无效',
  enabled: '启用',
  disabled: '停用',
  expired: '已过期',
  timeout: '超时',
  archived: '已归档',
  deleted: '已删除',
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
  sign: '签名',
  return: '退货',
  returned: '已退货',
  apply: '申请',
  review: '评审',
  approving: '申请中',
  reviewing: '审核中',
  user: '用户',
  account: '账户',
  balance: '余额',
  stock: '库存',
  inventory: '库存',
  coupon: '优惠券',
  cart: '购物车',
  order: '订单',
  item: '商品',
  goods: '商品',
  product: '商品',
  sku: 'SKU',
  address: '地址',
  merchant: '商家',
  store: '门店',
  channel: '渠道'
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
  'REFUND_',
  'BIZ_',
  'COMMON_'
]

// 智能分词：下划线/横线/空格 + 驼峰（PaymentSuccess → Payment Success）
function splitWords(text) {
  const parts = String(text).split(/[_\-\s]+/).filter(Boolean)
  const words = []
  for (const part of parts) {
    const camel = part
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    words.push(...camel.split(/\s+/).filter(Boolean))
  }
  return words
}

function translate(text, keepOriginal) {
  if (!text || typeof text !== 'string') return text || ''
  const original = text
  // 用大写版匹配前缀，再从原文本去掉等长前缀（保留驼峰大小写用于切分）
  const upper = text.toUpperCase()
  let strippedUpper = upper
  let changed = true
  while (changed) {
    changed = false
    for (const p of STATE_PREFIXES) {
      if (strippedUpper.startsWith(p)) {
        strippedUpper = strippedUpper.substring(p.length)
        changed = true
      }
    }
  }
  const prefixLen = upper.length - strippedUpper.length
  const words = splitWords(original.substring(prefixLen))
  const translated = words
    .map((w) => TOKEN_MAP[w.toLowerCase()] || w)
    .join('')
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
