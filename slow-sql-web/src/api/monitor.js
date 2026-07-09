import request from './request'

export function getRecords(limit = 50) {
  return request.get('/api/monitor/records', { params: { limit } })
}

export function deleteRecord(id) {
  return request.post('/api/monitor/records/delete', { id })
}

export function clearRecords() {
  return request.post('/api/monitor/records/clear')
}
