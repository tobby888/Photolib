import { Button, Input, Space, Tag, Tooltip, Typography } from 'antd'
import type { GetRef } from 'antd'
import { CheckCircleFilled, CloseCircleFilled, LoadingOutlined, UndoOutlined } from '@ant-design/icons'
import { useEffect, useRef, useState } from 'react'
import { api } from './api'
import type { SalaryFormulaCheck, SalaryFormulaReference } from './types'
import { DEFAULT_SALARY_FORMULA, SALARY_FORMULA_MAX_LENGTH, insertSnippet } from './salary'

export type FormulaStatus = 'checking' | 'valid' | 'invalid'

/**
 * 工资公式编辑框：点字段、函数按钮插到光标处，边写边让后端检查（SalaryFormula 只在后端解析，
 * 前端不另写一套），写对了就用示例值试算一遍给人看。
 */
export default function SalaryFormulaEditor({ reference, value, onChange, onStatusChange }: {
  reference: SalaryFormulaReference | null
  value: string
  onChange: (value: string) => void
  onStatusChange: (status: FormulaStatus) => void
}) {
  const inputRef = useRef<GetRef<typeof Input.TextArea>>(null)
  const [check, setCheck] = useState<SalaryFormulaCheck | null>(null)
  const [checking, setChecking] = useState(false)
  const [requestError, setRequestError] = useState('')
  const sequence = useRef(0)
  const defaultFormula = reference?.defaultFormula || DEFAULT_SALARY_FORMULA

  // 停笔 350ms 再检查；只认最后一次请求的结果，免得慢的旧结果盖掉新的。
  useEffect(() => {
    const current = ++sequence.current
    setChecking(true)
    onStatusChange('checking')
    const timer = window.setTimeout(() => {
      api<SalaryFormulaCheck>({ method: 'POST', url: '/salary-calculations/formula/check', data: { formula: value } })
        .then(result => {
          if (current !== sequence.current) return
          setCheck(result)
          setRequestError('')
          onStatusChange(result.valid ? 'valid' : 'invalid')
        })
        .catch(error => {
          if (current !== sequence.current) return
          setCheck(null)
          setRequestError((error as Error).message)
          onStatusChange('invalid')
        })
        .finally(() => { if (current === sequence.current) setChecking(false) })
    }, 350)
    return () => window.clearTimeout(timer)
    // onStatusChange 由父组件传入，变化不需要重新检查。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])

  const insert = (snippet: string) => {
    const textArea = inputRef.current?.resizableTextArea?.textArea
    const start = textArea?.selectionStart ?? value.length
    const end = textArea?.selectionEnd ?? value.length
    const next = insertSnippet(value, start, end, snippet)
    if (next.text.length > SALARY_FORMULA_MAX_LENGTH) return
    onChange(next.text)
    window.requestAnimationFrame(() => {
      textArea?.focus()
      textArea?.setSelectionRange(next.cursor, next.cursor)
    })
  }

  const blank = !value.trim()
  return <div className="salary-formula-editor">
    <Input.TextArea ref={inputRef} value={value} maxLength={SALARY_FORMULA_MAX_LENGTH} showCount
      autoSize={{ minRows: 2, maxRows: 5 }} spellCheck={false} aria-label="工资公式"
      placeholder={`留空则用默认公式：${defaultFormula}`} style={{ fontFamily: 'var(--ant-font-family-code, monospace)' }}
      onChange={event => onChange(event.target.value)} />
    <div className="salary-formula-palette">
      <Typography.Text type="secondary">字段</Typography.Text>
      {(reference?.variables ?? []).map(variable => <Tooltip key={variable.name}
        title={`${variable.description}；英文写法 ${variable.alias}；试算示例值 ${variable.sample}`}>
        <Tag className="salary-formula-chip" color="blue" onClick={() => insert(variable.name)}>{variable.name}</Tag>
      </Tooltip>)}
    </div>
    <div className="salary-formula-palette">
      <Typography.Text type="secondary">函数</Typography.Text>
      {(reference?.functions ?? []).map(fn => <Tooltip key={fn.name}
        title={`${fn.signature}：${fn.description}（也可以写成「${fn.chineseName}」）`}>
        <Tag className="salary-formula-chip" color="purple" onClick={() => insert(`${fn.name}()`)}>{fn.name}</Tag>
      </Tooltip>)}
      <Typography.Text type="secondary">运算</Typography.Text>
      {['+', '-', '*', '/', '>', '<', '>=', '<=', '==', '!='].map(op =>
        <Tag key={op} className="salary-formula-chip" onClick={() => insert(op)}>{op}</Tag>)}
      <Button type="link" size="small" icon={<UndoOutlined />} disabled={value === defaultFormula}
        onClick={() => onChange(defaultFormula)}>恢复默认</Button>
    </div>
    <div className="salary-formula-status" aria-live="polite">
      {checking
        ? <Typography.Text type="secondary"><LoadingOutlined /> 正在检查公式…</Typography.Text>
        : requestError
          ? <Typography.Text type="danger"><CloseCircleFilled /> 没能检查公式：{requestError}</Typography.Text>
          : check && !check.valid
            ? <Typography.Text type="danger"><CloseCircleFilled /> {check.error}
              {check.position != null && `（第 ${check.position + 1} 个字符）`}</Typography.Text>
            : check && <Space direction="vertical" size={0}>
              <Typography.Text type="success"><CheckCircleFilled /> 公式可以用
                {blank && `（留空，按默认公式 ${defaultFormula} 算）`}
                {check.variables.length > 0 && `，用到了 ${check.variables.join('、')}`}</Typography.Text>
              {check.sampleProcess && <Typography.Text type="secondary">
                试算（字段取示例值）：{check.sampleProcess} 元</Typography.Text>}
              {check.error && <Typography.Text type="warning">{check.error}</Typography.Text>}
            </Space>}
    </div>
  </div>
}
