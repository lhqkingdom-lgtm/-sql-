import request from './request'

export function getStats() {
  return request.get('/api/dashboard/stats')
}
