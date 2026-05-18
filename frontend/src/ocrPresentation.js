const LOC_TOKEN = /<LOC_\d+>/g
const REPLACEMENT_CHAR = /�/g
const EXPLANATION_ONLY = /^(if |please |n\/a$|na$)/i

export function lineText(line) {
  return (line?.spans || [])
    .map((span) => span.text || '')
    .join('')
    .replace(LOC_TOKEN, '')
    .replace(/\s+/g, ' ')
    .trim()
}

export function visibleOcrLines(lines = []) {
  return lines
    .filter((line) => {
      const text = lineText(line)
      if (!text) return false
      if (hasHeavyLocatorNoise(line)) return false
      if (hasHeavyReplacementNoise(text)) return false
      if (looksLikeGeneratedNoise(text) && !line.hasUserInput) return false
      return true
    })
    .map((line) => ({
      ...line,
      spans: cleanSpans(line.spans || [])
    }))
}

export function fieldsForPage(fields = [], page) {
  const sections = new Map()
  for (const field of fields) {
    if (field.page !== page) continue
    if (!shouldShowField(field)) continue

    const sectionName = field.sectionName || '字段'
    if (!sections.has(sectionName)) {
      sections.set(sectionName, {
        name: sectionName,
        fields: []
      })
    }
    sections.get(sectionName).fields.push(field)
  }
  return Array.from(sections.values())
}

export function shouldShowField(field) {
  if (!field?.present) return false
  if (String(field.value || '').toLowerCase() === 'unchecked') return false
  if (!hasFieldValue(field) && field.confidence < 0.4) return false
  return true
}

export function hasFieldValue(field) {
  const value = normalizeFieldValue(field?.value || '')
  if (!value) return false
  return !EXPLANATION_ONLY.test(value)
}

export function displayValue(field) {
  const value = String(field?.value ?? '')
  const lower = value.trim().toLowerCase()
  if (lower === 'checked') return '已勾选'
  if (lower === 'present') return '有内容'
  if (lower === 'missing') return '未发现'
  if (!value.trim() && field?.present) return '\u6709\u586b\u5199\u75d5\u8ff9\uff0cOCR \u672a\u7a33\u5b9a\u8bc6\u522b'
  return value || ''
}

export function fieldConfidenceLabel(field) {
  const confidence = Number(field?.confidence || 0)
  return `${(confidence * 100).toFixed(0)}%`
}

export function fieldConfidenceClass(field) {
  const confidence = Number(field?.confidence || 0)
  if (confidence >= 0.8) return 'confidence-high'
  if (confidence >= 0.5) return 'confidence-medium'
  return 'confidence-low'
}

function cleanSpans(spans) {
  return spans
    .map((span) => ({
      ...span,
      text: String(span.text || '')
        .replace(LOC_TOKEN, '')
        .replace(/\s+/g, ' ')
    }))
    .filter((span) => span.text.trim())
}

function hasHeavyLocatorNoise(line) {
  const raw = (line?.spans || []).map((span) => span.text || '').join('')
  return (raw.match(LOC_TOKEN) || []).length >= 2
}

function hasHeavyReplacementNoise(text) {
  const count = (text.match(REPLACEMENT_CHAR) || []).length
  return count >= 2 || count / Math.max(1, text.length) > 0.04
}

function looksLikeGeneratedNoise(text) {
  if (text.length < 100) return false
  const latinWords = (text.match(/[A-Za-z]{2,}/g) || []).length
  const symbols = (text.match(/[{}<>|`^~\\]/g) || []).length
  const cjk = (text.match(/[\u3400-\u9fff]/g) || []).length
  return symbols > 8 || (latinWords > 25 && cjk > 15)
}

function normalizeFieldValue(value) {
  return String(value || '')
    .replace(LOC_TOKEN, '')
    .replace(REPLACEMENT_CHAR, '')
    .replace(/\s+/g, ' ')
    .replace(/^[()[\]{}，。,.;:：；\s]+|[()[\]{}，。,.;:：；\s]+$/g, '')
    .trim()
}
