import { describe, it, expect } from 'vitest';
import { consumeChat } from './auth';
function response(text: string) {
  const bytes = new TextEncoder().encode(text);
  // Deliberately split every UTF-8 character and SSE delimiter across network chunks.
  return new Response(new ReadableStream({ start(controller) {
    for (const byte of bytes) controller.enqueue(Uint8Array.of(byte)); controller.close();
  }}));
}
describe('authenticated SSE stream', () => {
  it('preserves Chinese text and multiline previews across UTF-8 chunks', async () => {
    let result = '';
    await consumeChat(response('event:message\r\ndata:请确认\r\ndata:订单未修改\r\n\r\nevent:done\r\ndata:\r\n\r\n'), text => result += text);
    expect(result).toBe('请确认\n订单未修改');
  });
  it('does not describe an interrupted connection as success', async () => {
    await expect(consumeChat(response('data:部分回复\n\n'), () => {})).rejects.toThrow('连接中断');
  });
  it('surfaces the server error event', async () => {
    await expect(consumeChat(response('event:error\ndata:暂时失败\n\n'), () => {})).rejects.toThrow('暂时失败');
  });
});
