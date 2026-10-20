export type User = { id: number; username: string; displayName: string; role: string };
export async function api(path: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  if (options.method && !['GET','HEAD'].includes(options.method.toUpperCase())) {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
    if (!csrfResponse.ok) throw new Error('无法获取安全令牌，请刷新页面重试。');
    const csrf = await csrfResponse.json(); headers.set(csrf.headerName, csrf.token);
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    if (response.status === 401) {
      window.dispatchEvent(new Event('auth-expired'));
      throw new Error('请登录，或检查用户名和密码。');
    }
    let detail = '';
    try { detail = (await response.json()).message || ''; } catch { /* response may have no body */ }
    throw new Error(detail || (response.status === 403 ? '无操作权限或安全令牌已失效，请刷新后重试。' : '请求失败，请稍后重试。'));
  }
  return response;
}
export async function json(path: string, options: RequestInit = {}) { return (await api(path, options)).json(); }

/** Decode complete SSE frames; one UTF-8 character or frame may span multiple network chunks. */
export async function consumeChat(response: Response, append: (text: string) => void) {
  if (!response.body) throw new Error('浏览器不支持流式响应。');
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''; let ended = false;
  const frame = (block: string) => {
    let event = 'message'; const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (event === 'error') throw new Error(data.join('\n') || '对话失败，请核对订单后重试。');
    if (event === 'done') ended = true;
    else if (data.length) append(data.join('\n'));
  };
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, '\n');
      let split: number;
      while ((split = buffer.indexOf('\n\n')) >= 0) { frame(buffer.slice(0, split)); buffer = buffer.slice(split + 2); }
      if (done) break;
    }
    if (!ended) throw new Error('连接中断，请先核对订单与审计，避免重复操作。');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
