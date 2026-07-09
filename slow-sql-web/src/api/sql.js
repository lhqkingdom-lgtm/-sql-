import request from './request'

export function getProjects() {
  return request.get('/api/sql/projects')
}

export function analyzeSql({ instanceId, sql, sessionId, projectCode, type }) {
  return request.post('/api/sql/analyze', {
    instanceId,
    sql,
    ...(sessionId && { sessionId }),
    ...(projectCode && { projectCode }),
    ...(type && { type }),
  })
}
