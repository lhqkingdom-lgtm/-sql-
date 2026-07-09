import request from './request'

export function getDocuments(category, keyword) {
  return request.get('/api/rag/documents', { params: { category, keyword } })
}

export function createDocument({ title, content, category, tags }) {
  return request.post('/api/rag/documents', { title, content, category, tags })
}

export function updateDocument(id, { title, content, category, tags, enabled }) {
  return request.put(`/api/rag/documents/${id}`, { title, content, category, tags, enabled })
}

export function deleteDocument(id) {
  return request.delete(`/api/rag/documents/${id}`)
}
