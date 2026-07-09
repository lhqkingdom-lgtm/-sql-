import request from './request'

export function checkHealth() {
  return request.get('/health')
}
