import { describe, it, expect, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import Portal from './Portal.vue';
vi.mock('./views/home/index.vue', () => ({ default: { template: '<div>授权后的客服页面</div>' } }));
afterEach(() => { vi.unstubAllGlobals(); });
const body = (value: any, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
describe('account portal workflow', () => {
  it('registers, logs in, and removes account content after logout', async () => {
    const requests: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit = {}) => {
      requests.push(url);
      if (url.endsWith('/csrf')) return body({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
      if (url.endsWith('/me')) return new Response(null, { status: 401 });
      expect(new Headers(options.headers).get('X-CSRF-TOKEN')).toBe('test-token');
      if (url.endsWith('/register')) {
        expect(JSON.parse(options.body as string).role).toBeUndefined();
        return body({ id: 8, username: 'learner', displayName: '学习用户', role: 'USER' }, 201);
      }
      if (url.endsWith('/login')) return body({ id: 8, username: 'learner', displayName: '学习用户', role: 'USER' });
      if (url.endsWith('/logout')) return new Response(null, { status: 204 });
      throw new Error('Unexpected URL: ' + url);
    }));
    const wrapper = mount(Portal); await flushPromises();
    expect(wrapper.text()).not.toContain('授权后的客服页面');
    await wrapper.get('button[type="button"]').trigger('click');
    await wrapper.get('input[autocomplete="username"]').setValue('learner');
    await wrapper.get('input[autocomplete="name"]').setValue('学习用户');
    await wrapper.get('input[type="password"]').setValue('local-password-123');
    await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('注册成功');
    await wrapper.get('input[type="password"]').setValue('local-password-123');
    await wrapper.get('form').trigger('submit'); await flushPromises();
    expect(wrapper.text()).toContain('授权后的客服页面');
    expect(wrapper.text()).toContain('学习用户');
    await wrapper.findAll('button').find(button => button.text() === '退出登录')!.trigger('click'); await flushPromises();
    expect(wrapper.text()).not.toContain('授权后的客服页面');
    expect(requests).toContain('/api/auth/register'); expect(requests).toContain('/api/auth/logout'); wrapper.unmount();
  });
  it('shows administrator assignment controls and submits an explicit user ID', async () => {
    const assign = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (url: string, options: RequestInit = {}) => {
      if (url.endsWith('/csrf')) return body({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
      if (url.endsWith('/me')) return body({ id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN' });
      if (url.endsWith('/users')) return body([{ id: 8, username: 'learner', displayName: '学习用户', role: 'USER' }]);
      if (url.endsWith('/assignments') && options.method === 'POST') { assign(JSON.parse(options.body as string)); return body({ message: 'ok' }); }
      if (url.endsWith('/assignments')) return body([{ bookingNumber: '101', name: '云小宝', userId: null, username: null }]);
      throw new Error('Unexpected URL: ' + url);
    }));
    const wrapper = mount(Portal); await flushPromises();
    expect(wrapper.text()).toContain('未分配'); await wrapper.get('select').setValue('8');
    await wrapper.findAll('button').find(button => button.text() === '分配')!.trigger('click'); await flushPromises();
    expect(assign).toHaveBeenCalledWith({ bookingNumber: '101', userId: 8 }); wrapper.unmount();
  });
});
