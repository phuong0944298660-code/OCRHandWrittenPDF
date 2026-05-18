import test from 'node:test'
import assert from 'node:assert/strict'
import {
  displayValue,
  fieldsForPage,
  hasFieldValue,
  visibleOcrLines
} from './ocrPresentation.js'

test('visibleOcrLines removes locator tokens and noisy replacement text', () => {
  const lines = visibleOcrLines([
    {
      lineNumber: 1,
      hasUserInput: false,
      spans: [{ text: '姓名 Surname in English KUSUMA', userInput: false }]
    },
    {
      lineNumber: 2,
      hasUserInput: false,
      spans: [{ text: '並樣錄記確認<LOC_252><LOC_638><LOC_137>', userInput: false }]
    },
    {
      lineNumber: 3,
      hasUserInput: false,
      spans: [{ text: '公司性別與DX或DXaset類別XDS/DX/����俱異性別QH/QX', userInput: false }]
    }
  ])

  assert.equal(lines.length, 1)
  assert.equal(lines[0].spans[0].text, '姓名 Surname in English KUSUMA')
})

test('fieldsForPage groups extracted fields by page and hides unchecked/empty fields', () => {
  const grouped = fieldsForPage([
    {
      page: 1,
      sectionName: '1. 申请类别 Application Type',
      key: 'applicationType.entryFromAbroad.entryVisa.checked',
      label: '来港受雇为外籍家庭佣工 + 入境签证是否勾选',
      value: 'checked',
      present: true,
      confidence: 0.98
    },
    {
      page: 1,
      sectionName: '2. 个人资料 Personal Particulars',
      key: 'surnameEn.value',
      label: '姓（英文）Surname in English',
      value: '',
      present: true,
      confidence: 0.42
    },
    {
      page: 2,
      sectionName: '2. 个人资料 Personal Particulars',
      key: 'presentAddress.value',
      label: '现时住址 Present address',
      value: 'Flat 7',
      present: true,
      confidence: 0.86
    },
    {
      page: 1,
      sectionName: '1. 申请类别 Application Type',
      key: 'applicationType.contractRenewal.entryVisa.checked',
      label: '续约入境签证是否勾选',
      value: 'unchecked',
      present: false,
      confidence: 0.7
    }
  ], 1)

  assert.equal(grouped.length, 2)
  assert.equal(grouped[0].fields.length, 1)
  assert.equal(grouped[0].fields[0].key, 'applicationType.entryFromAbroad.entryVisa.checked')
  assert.equal(grouped[1].fields[0].key, 'surnameEn.value')
})

test('displayValue distinguishes checked, visible marks, and empty low-confidence text', () => {
  assert.equal(displayValue({ value: 'checked', present: true }), '已勾选')
  assert.equal(displayValue({ value: 'unchecked', present: false }), '未勾选')
  assert.equal(displayValue({ value: 'present', present: true }), '有内容')
  assert.equal(displayValue({ value: '', present: true }), '有填写痕迹，OCR 未稳定识别')
})

test('displayValue returns handwritten OCR text verbatim', () => {
  assert.equal(displayValue({ value: ' KUSUMA/DEWI ', present: true }), ' KUSUMA/DEWI ')
  assert.equal(displayValue({ value: '妧', present: true }), '妧')
})

test('hasFieldValue rejects explanation-only values', () => {
  assert.equal(hasFieldValue({ value: '(if applicable)', present: true }), false)
  assert.equal(hasFieldValue({ value: 'KUSUMA', present: true }), true)
})
