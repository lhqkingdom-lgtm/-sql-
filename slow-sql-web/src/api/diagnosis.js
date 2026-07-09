import request from './request'
import { ElMessage } from 'element-plus'

/**
 * 创建 SSE EventSource 监听诊断结果
 * @param {string} taskId
 * @param {{ onComplete, onFailed, onError }} handlers
 * @returns {EventSource}
 */
export function createEventSource(taskId, handlers) {
  const url = `/api/sql/stream/${taskId}`
  const es = new EventSource(url)

  es.addEventListener('diagnosis-complete', (e) => {
    try {
      const data = JSON.parse(e.data)
      handlers.onComplete?.(data)
    } catch {
      handlers.onComplete?.({ report: e.data })
    }
    es.close()
  })

  es.addEventListener('diagnosis-failed', (e) => {
    try {
      const data = JSON.parse(e.data)
      handlers.onFailed?.(data)
    } catch {
      handlers.onFailed?.({ error: e.data })
    }
    es.close()
  })

  es.onerror = () => {
    handlers.onError?.()
    es.close()
  }

  return es
}

/**
 * 轮询查询结果
 * @param {string} taskId
 * @returns {Promise<{status, report?, error?}>}
 */
export function pollResult(taskId) {
  return request.get(`/api/sql/result/${taskId}`)
}

/**
 * SSE 优先，超时或失败自动切轮询
 * @param {string} taskId
 * @param {object} opts - { sseTimeoutMs, pollIntervalMs, maxPolls }
 * @param {function} onStatusChange - (status, data) => void
 * @returns {{ cancel: () => void }}
 */
export function diagnoseWithFallback(taskId, onStatusChange, opts = {}) {
  const {
    sseTimeoutMs = 10000,
    pollIntervalMs = 2000,
    maxPolls = 90,
  } = opts

  let cancelled = false
  let es = null
  let pollTimer = null
  let pollCount = 0

  function cleanup() {
    cancelled = true
    if (es) { es.close(); es = null }
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  }

  function startPolling() {
    onStatusChange?.('polling', {})
    pollCount = 0

    const firstPoll = async () => {
      try {
        const r = await pollResult(taskId)
        if (cancelled) return
        if (r.status === 'completed') {
          onStatusChange?.('completed', r)
          cleanup()
          return
        }
        if (r.status === 'failed') {
          onStatusChange?.('failed', r)
          cleanup()
          return
        }
        // still pending/running — start interval
        startIntervalPolling()
      } catch {
        startIntervalPolling()
      }
    }
    firstPoll()
  }

  function startIntervalPolling() {
    pollCount = 0
    pollTimer = setInterval(async () => {
      if (cancelled) { clearInterval(pollTimer); return }
      pollCount++
      if (pollCount > maxPolls) {
        clearInterval(pollTimer)
        onStatusChange?.('failed', { error: '诊断超时，请稍后重试' })
        return
      }
      try {
        const r = await pollResult(taskId)
        if (r.status === 'completed') {
          onStatusChange?.('completed', r)
          cleanup()
        } else if (r.status === 'failed') {
          onStatusChange?.('failed', r)
          cleanup()
        }
      } catch { /* retry next poll */ }
    }, pollIntervalMs)
  }

  // Try SSE first
  let sseReceived = false
  const sseTimeout = setTimeout(() => {
    if (!sseReceived && es && !cancelled) {
      es.close()
      es = null
      ElMessage.info('SSE 连接超时，切换为轮询模式')
      startPolling()
    }
  }, sseTimeoutMs)

  es = createEventSource(taskId, {
    onComplete(data) {
      sseReceived = true
      clearTimeout(sseTimeout)
      onStatusChange?.('completed', data)
      cleanup()
    },
    onFailed(data) {
      sseReceived = true
      clearTimeout(sseTimeout)
      onStatusChange?.('failed', data)
      cleanup()
    },
    onError() {
      if (!sseReceived && !cancelled) {
        clearTimeout(sseTimeout)
        if (es) { es.close(); es = null }
        ElMessage.info('SSE 连接失败，切换为轮询模式')
        startPolling()
      }
    },
  })

  return { cancel: cleanup }
}
