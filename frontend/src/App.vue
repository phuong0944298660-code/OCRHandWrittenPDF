<script setup>
import { computed, ref } from 'vue'
import {
  displayValue,
  fieldConfidenceClass,
  fieldConfidenceLabel,
  fieldsForPage,
  hasFieldValue,
  visibleOcrLines
} from './ocrPresentation.js'

const apiBase = import.meta.env.VITE_API_BASE || ''

const file = ref(null)
const fileInput = ref(null)
const response = ref(null)
const activePage = ref(1)
const resultTab = ref('fields')
const loading = ref(false)
const error = ref('')
const progress = ref(0)
const progressStage = ref('')
let progressTimer = null

const pages = computed(() => response.value?.pages || [])
const currentPage = computed(() => {
  return pages.value.find((page) => page.page === activePage.value) || pages.value[0] || null
})
const currentPageIndex = computed(() => {
  const index = pages.value.findIndex((page) => page.page === activePage.value)
  return index >= 0 ? index + 1 : 0
})
const highlightedLineCount = computed(() => {
  return visibleLines.value.filter((line) => line.hasUserInput).length
})
const visibleLines = computed(() => visibleOcrLines(currentPage.value?.lines || []))
const filteredLineCount = computed(() => {
  return Math.max(0, (currentPage.value?.lines || []).length - visibleLines.value.length)
})
const currentFieldSections = computed(() => {
  return fieldsForPage(response.value?.extractedFields || [], activePage.value)
})
const currentFieldCount = computed(() => {
  return currentFieldSections.value.reduce((total, section) => total + section.fields.length, 0)
})
const extractedFieldCount = computed(() => {
  return (response.value?.extractedFields || []).filter((field) => field.present).length
})
const jsonPreview = computed(() => {
  if (!response.value) return ''
  const compact = {
    ...response.value,
    pages: response.value.pages.map((page) => ({
      ...page,
      sourceImageDataUrl: summarizeDataUrl(page.sourceImageDataUrl)
    }))
  }
  return JSON.stringify(compact, null, 2)
})

function onFileChange(event) {
  setFile(event.target.files?.[0])
}

function onDrop(event) {
  setFile(event.dataTransfer.files?.[0])
}

function setFile(selected) {
  if (!selected) return
  file.value = selected
  response.value = null
  activePage.value = 1
  resultTab.value = 'fields'
  error.value = ''
}

function openFilePicker() {
  fileInput.value?.click()
}

function startProgress() {
  progress.value = 0
  progressStage.value = '上传文件'
  const startTime = Date.now()
  const expectedMs = 120000
  progressTimer = setInterval(() => {
    const elapsed = Date.now() - startTime
    let next = (elapsed / expectedMs) * 100
    if (next > 92) next = 92 + (next - 92) * 0.08
    progress.value = Math.min(next, 98)

    if (progress.value < 20) progressStage.value = '上传文件'
    else if (progress.value < 48) progressStage.value = 'PDF 渲染与字段裁剪'
    else if (progress.value < 76) progressStage.value = 'PP-OCRv5 识别字段内容'
    else progressStage.value = '整理分页结果'
  }, 160)
}

function stopProgress(success) {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
  progress.value = success ? 100 : 0
  progressStage.value = success ? '完成' : ''
}

async function submitOcr() {
  if (!file.value || loading.value) return
  loading.value = true
  error.value = ''
  response.value = null
  startProgress()

  try {
    const body = new FormData()
    body.append('file', file.value)
    const result = await fetch(`${apiBase}/api/ocr`, { method: 'POST', body })
    if (!result.ok) {
      throw new Error(`HTTP ${result.status}`)
    }
    response.value = await result.json()
    activePage.value = response.value?.pages?.[0]?.page || 1
    resultTab.value = response.value?.extractedFields?.length ? 'fields' : 'text'
    stopProgress(true)
  } catch (exception) {
    error.value = `OCR 识别未完成：${exception.message || '请确认后端、Python OCR 服务与 PP-OCRv5 模型已启动'}`
    stopProgress(false)
  } finally {
    loading.value = false
  }
}

function selectPage(page) {
  activePage.value = page
  resultTab.value = response.value?.extractedFields?.length ? 'fields' : 'text'
}

function pageSignalCount(page) {
  const fieldCount = fieldsForPage(response.value?.extractedFields || [], page.page).reduce(
    (total, section) => total + section.fields.length,
    0
  )
  return fieldCount || visibleOcrLines(page.lines || []).filter((line) => line.hasUserInput).length
}

function reset() {
  file.value = null
  response.value = null
  activePage.value = 1
  resultTab.value = 'fields'
  error.value = ''
  progress.value = 0
  progressStage.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

function formatSize(bytes) {
  if (!bytes) return ''
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function summarizeDataUrl(value) {
  if (!value) return ''
  if (value.startsWith('data:')) return `${value.slice(0, 56)}...`
  return value
}
</script>

<template>
  <main class="ocr-app">
    <header class="app-header">
      <div class="brand-block">
        <p class="eyebrow">Hong Kong Immigration OCR Demo</p>
        <h1>申请材料 OCR 识别演示</h1>
        <p class="header-copy">英文、简体中文、繁体中文；机写和手写内容统一进入同一识别视图。</p>
      </div>
      <div class="model-pill">
        <span>解析模型</span>
        <strong>PP-OCRv5 det/rec</strong>
      </div>
    </header>

    <section v-if="!response" class="upload-stage">
      <div class="upload-panel">
        <div class="upload-heading">
          <h2>上传源文件</h2>
          <p>支持 PDF、PNG、JPG。结果页会保留源文件快照，并在右侧展示 OCR 文本。</p>
        </div>

        <button class="dropzone" type="button" @click="openFilePicker" @drop.prevent="onDrop" @dragover.prevent>
          <input
            ref="fileInput"
            type="file"
            accept="application/pdf,image/png,image/jpeg,image/jpg,image/webp"
            @change="onFileChange"
          />
          <span class="upload-glyph" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M12 16V4" />
              <path d="m7 9 5-5 5 5" />
              <path d="M5 20h14" />
            </svg>
          </span>
          <strong>{{ file?.name || '选择或拖入申请材料' }}</strong>
          <small v-if="file">{{ formatSize(file.size) }}</small>
          <small v-else>PDF / PNG / JPG</small>
        </button>

        <div class="capability-row" aria-label="识别能力">
          <span>English</span>
          <span>简体中文</span>
          <span>繁體中文</span>
          <span>手写内容</span>
        </div>

        <div v-if="loading" class="progress-box" aria-live="polite">
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: `${progress}%` }" />
          </div>
          <div class="progress-meta">
            <span>{{ progressStage }}</span>
            <strong>{{ progress.toFixed(0) }}%</strong>
          </div>
        </div>

        <button v-else class="primary-action" type="button" :disabled="!file" @click="submitOcr">
          开始 OCR 识别
        </button>
        <p v-if="error" class="error-text">{{ error }}</p>
      </div>
    </section>

    <section v-else class="result-stage">
      <div class="result-toolbar">
        <div class="file-summary">
          <span class="file-label">源文件</span>
          <strong>{{ response.filename }}</strong>
          <span>{{ response.pageCount }} 页</span>
        </div>
        <div class="toolbar-actions">
          <span class="status-chip">{{ response.engineStatus?.extractionMode }}</span>
          <button class="secondary-action" type="button" @click="reset">重新上传</button>
        </div>
      </div>

      <nav class="page-strip" aria-label="分页">
        <button
          v-for="page in pages"
          :key="page.page"
          type="button"
          :class="{ active: page.page === activePage }"
          @click="selectPage(page.page)"
        >
          <span>Page {{ page.page }}</span>
          <strong>{{ pageSignalCount(page) }}</strong>
        </button>
      </nav>

      <div class="split-workspace">
        <section class="source-pane" aria-label="源文件快照">
          <div class="pane-header">
            <div>
              <h2>源文件快照</h2>
              <p>第 {{ currentPageIndex }} / {{ pages.length }} 页</p>
            </div>
          </div>
          <div class="document-canvas">
            <img v-if="currentPage?.sourceImageDataUrl" :src="currentPage.sourceImageDataUrl" alt="源文件页面快照" />
            <div v-else class="empty-panel">Python OCR 服务未返回该页快照</div>
          </div>
        </section>

        <section class="ocr-pane" aria-label="OCR 识别结果">
          <div class="pane-header ocr-header">
            <div>
              <h2>{{ resultTab === 'fields' ? '字段提取结果' : 'OCR 识别结果' }}</h2>
              <p v-if="resultTab === 'fields'">
                本页 {{ currentFieldCount }} 个字段，全文共 {{ extractedFieldCount }} 个命中项
              </p>
              <p v-else>
                {{ highlightedLineCount }} 行包含疑似填写或勾选内容，已过滤 {{ filteredLineCount }} 行噪声
              </p>
            </div>
            <div class="segmented-control" role="tablist" aria-label="结果视图">
              <button type="button" :class="{ active: resultTab === 'fields' }" @click="resultTab = 'fields'">字段提取</button>
              <button type="button" :class="{ active: resultTab === 'text' }" @click="resultTab = 'text'">OCR 文本</button>
              <button type="button" :class="{ active: resultTab === 'json' }" @click="resultTab = 'json'">调试 JSON</button>
            </div>
          </div>

          <div v-if="resultTab === 'fields'" class="fields-document">
            <div v-if="!currentFieldSections.length" class="empty-panel">该页没有命中的结构化字段</div>
            <section
              v-for="section in currentFieldSections"
              :key="`${activePage}-${section.name}`"
              class="field-section"
            >
              <h3>{{ section.name }}</h3>
              <div class="field-table">
                <div
                  v-for="field in section.fields"
                  :key="field.key"
                  class="field-row"
                  :class="{ 'field-row-empty': !hasFieldValue(field) }"
                >
                  <div class="field-label">
                    <strong>{{ field.label }}</strong>
                    <span>{{ field.key }}</span>
                  </div>
                  <div class="field-value">
                    <strong>{{ displayValue(field) }}</strong>
                    <span v-if="field.option">{{ field.option }}</span>
                  </div>
                  <div class="field-confidence" :class="fieldConfidenceClass(field)">
                    {{ fieldConfidenceLabel(field) }}
                  </div>
                </div>
              </div>
            </section>
          </div>

          <div v-else-if="resultTab === 'text'" class="ocr-document">
            <div v-if="!visibleLines.length" class="empty-panel">该页未返回可展示文本，或原始 OCR 内容已被判定为噪声</div>
            <p
              v-for="line in visibleLines"
              :key="`${currentPage.page}-${line.lineNumber}`"
              class="ocr-line"
              :class="{ 'line-with-input': line.hasUserInput }"
            >
              <template v-for="(span, index) in line.spans" :key="`${line.lineNumber}-${index}`">
                <mark v-if="span.userInput" class="filled-text">{{ span.text }}</mark>
                <span v-else>{{ span.text }}</span>
              </template>
            </p>
          </div>

          <pre v-else class="json-panel">{{ jsonPreview }}</pre>
        </section>
      </div>
    </section>
  </main>
</template>
