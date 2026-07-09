<template>
  <div class="markdown-body" v-html="renderedHtml"></div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ content: { type: String, default: '' } })

const renderedHtml = computed(() => {
  if (!props.content) return '<p class="empty-hint">暂无内容</p>'
  let text = props.content
  // escape HTML first
  text = text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

  // code blocks ```
  text = text.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) =>
    `<pre><code class="language-${lang}">${code.trim()}</code></pre>`)

  // inline code `
  text = text.replace(/`([^`]+)`/g, '<code>$1</code>')

  // bold **text**
  text = text.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')

  // headings ### / ## / #
  text = text.replace(/^### (.+)$/gm, '<h3>$1</h3>')
  text = text.replace(/^## (.+)$/gm, '<h2>$1</h2>')
  text = text.replace(/^# (.+)$/gm, '<h1>$1</h1>')

  // horizontal rules ---
  text = text.replace(/^---$/gm, '<hr>')

  // table rows | col | col |
  text = text.replace(/^\|(.+)\|$/gm, (match) => {
    const cells = match.split('|').filter(c => c.trim()).map(c => c.trim())
    if (cells.every(c => /^[-:]+$/.test(c))) return '<thead-sep></thead-sep>'
    const tag = match.replace(/^\|/, '').replace(/\|$/, '').includes('---') ? 'th' : 'td'
    return '<tr>' + cells.map(c => `<${tag}>${c}</${tag}>`).join('') + '</tr>'
  })

  // wrap consecutive tr in table
  text = text.replace(/(<tr>[\s\S]*?<\/tr>(\s*<thead-sep><\/thead-sep>)?)+/g, (match) => {
    const hasHeader = match.includes('<thead-sep>')
    let result = '<table>'
    if (hasHeader) {
      const parts = match.split('<thead-sep></thead-sep>')
      result += '<thead>' + (parts[0] || '') + '</thead>'
      result += '<tbody>' + (parts[1] || '') + '</tbody>'
    } else {
      result += '<tbody>' + match + '</tbody>'
    }
    result += '</table>'
    return result
  })

  // unordered lists - item
  text = text.replace(/^- (.+)$/gm, '<li>$1</li>')
  text = text.replace(/(<li>[\s\S]*?<\/li>)+/g, '<ul>$&</ul>')

  // blockquote >
  text = text.replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>')

  // paragraphs (double newline)
  text = text.replace(/\n\n/g, '</p><p>')
  text = '<p>' + text + '</p>'

  // clean up empty tags
  text = text.replace(/<p>\s*<\/p>/g, '')
  text = text.replace(/<p>(<[hutc])/g, '$1')
  text = text.replace(/(<\/[hutc]>[\s\S]*?)<\/p>/g, '$1')

  return text
})
</script>

<style scoped>
.markdown-body { line-height: 1.8; word-break: break-word; }
.markdown-body :deep(h1) { font-size: 20px; margin: 16px 0 8px; color: var(--accent-cyan); border-left: 3px solid var(--accent-cyan); padding-left: 12px; }
.markdown-body :deep(h2) { font-size: 17px; margin: 14px 0 6px; color: var(--accent-blue); border-left: 3px solid var(--accent-blue); padding-left: 10px; }
.markdown-body :deep(h3) { font-size: 15px; margin: 12px 0 4px; color: var(--text-primary); }
.markdown-body :deep(pre) {
  background: #1e1e1e; border: 1px solid var(--border-color);
  border-radius: var(--radius-sm); padding: 14px 16px;
  overflow-x: auto; margin: 10px 0; position: relative;
}
.markdown-body :deep(code) {
  font-family: var(--font-mono); font-size: 13px;
  background: rgba(255,255,255,0.06); padding: 2px 6px;
  border-radius: 3px; color: var(--accent-orange);
}
.markdown-body :deep(pre code) {
  background: transparent; padding: 0; color: var(--text-primary);
}
.markdown-body :deep(table) {
  width: 100%; border-collapse: collapse; margin: 10px 0;
  font-size: 13px;
}
.markdown-body :deep(th) {
  background: var(--bg-elevated); text-align: left;
  padding: 8px 12px; border: 1px solid var(--border-color);
  font-weight: 600; color: var(--accent-cyan);
}
.markdown-body :deep(td) {
  padding: 6px 12px; border: 1px solid var(--border-color);
}
.markdown-body :deep(ul) { padding-left: 24px; margin: 8px 0; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(strong) { color: var(--accent-green); }
.markdown-body :deep(blockquote) {
  border-left: 3px solid var(--accent-orange); padding: 6px 14px;
  margin: 8px 0; background: rgba(247, 103, 7, 0.05);
  color: var(--text-secondary); font-style: italic;
}
.markdown-body :deep(hr) {
  border: none; border-top: 1px solid var(--border-color); margin: 16px 0;
}
.empty-hint { color: var(--text-muted); text-align: center; padding: 40px; }
</style>
