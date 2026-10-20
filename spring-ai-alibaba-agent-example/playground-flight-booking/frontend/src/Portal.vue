<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import Home from './views/home/index.vue';
import { api, json, type User } from './auth';
const user = ref<User | null>(null), loading = ref(true), legacy = ref(false), register = ref(false), busy = ref(false);
const username = ref(''), password = ref(''), displayName = ref(''), error = ref(''), notice = ref('');
const users = ref<User[]>([]), assignments = ref<any[]>([]), selected = ref<Record<string,number>>({});
const audits = ref<any[] | null>(null); const homeKey = ref(0);
function expired() { user.value = null; audits.value = null; assignments.value = []; users.value = []; }
async function current() {
  loading.value = true;
  try {
    const csrf = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
    if (csrf.status === 404) { legacy.value = true; return; }
    if (!csrf.ok) throw new Error('服务暂不可用，请检查启动窗口及 Redis 连接。');
    const me = await fetch('/api/auth/me', { credentials: 'same-origin', cache: 'no-store' });
    if (me.status === 401) return;
    if (!me.ok) throw new Error('无法读取登录状态，请重试。');
    user.value = await me.json(); await adminData();
  } catch (e: any) { error.value = e.message; } finally { loading.value = false; }
}
async function submit() {
  if (busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try {
    if (register.value) {
      await json('/api/auth/register', { method: 'POST', body: JSON.stringify({ username: username.value, password: password.value, displayName: displayName.value }) });
      register.value = false; notice.value = '注册成功，请登录。管理员分配订单后即可查看。';
    } else {
      user.value = await json('/api/auth/login', { method: 'POST', body: JSON.stringify({ username: username.value, password: password.value }) });
      await adminData();
    }
  } catch (e: any) { error.value = e.message; } finally { password.value = ''; busy.value = false; }
}
async function logout() {
  if (busy.value) return; busy.value = true;
  try { await api('/api/auth/logout', { method: 'POST', body: '{}' }); expired(); }
  catch (e: any) { error.value = e.message; } finally { busy.value = false; }
}
async function adminData() {
  if (user.value?.role !== 'ADMIN') return;
  [users.value, assignments.value] = await Promise.all([json('/api/admin/users'), json('/api/admin/assignments')]);
}
async function assign(number: string) {
  if (busy.value || !selected.value[number]) return; busy.value = true; error.value = '';
  try {
    await json('/api/admin/assignments', { method: 'POST', body: JSON.stringify({ bookingNumber: number, userId: Number(selected.value[number]) }) });
    await adminData(); homeKey.value++; notice.value = '订单归属已更新，原用户不能再查询或操作该订单。';
  } catch (e: any) { error.value = e.message; } finally { busy.value = false; }
}
async function showAudits() {
  error.value = ''; try { audits.value = await json('/api/booking-audits'); } catch (e: any) { error.value = e.message; }
}
onMounted(() => { window.addEventListener('auth-expired', expired); current(); });
onUnmounted(() => window.removeEventListener('auth-expired', expired));
</script>
<template>
  <div v-if="loading" class="login-card">正在检查登录状态…</div>
  <template v-else-if="legacy">
    <p class="banner">当前为无登录演示模式。完整登录流程请使用 start-mysql.cmd，或在 IDEA 激活 mysql,secure。</p>
    <Home :legacy="true" />
  </template>
  <main v-else-if="!user" class="login-card">
    <h1>Funnair 机票售后助手</h1><h2>{{ register ? '创建账号' : '登录' }}</h2>
    <p>登录后可查询自己的订单，通过客服对话生成改签或取消方案，确认后执行。</p>
    <form @submit.prevent="submit">
      <label>用户名<input v-model="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_]{3,32}" autocomplete="username" placeholder="3至32位字母、数字或下划线" /></label>
      <label v-if="register">显示名称<input v-model="displayName" required maxlength="40" autocomplete="name" /></label>
      <label>密码<input v-model="password" type="password" required :minlength="register ? 12 : 1" maxlength="72" :autocomplete="register ? 'new-password' : 'current-password'" placeholder="注册密码至少12个字符" /></label>
      <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <button type="submit" :disabled="busy">{{ busy ? '请稍候…' : register ? '注册' : '登录' }}</button>
      <button type="button" :disabled="busy" @click="register = !register; error = ''; password = ''">{{ register ? '返回登录' : '注册账号' }}</button>
    </form>
    <p>首次管理员账号由本机启动配置创建；普通注册账号没有默认订单。</p>
    <button v-if="error" @click="current">重新连接</button>
  </main>
  <template v-else>
    <header class="toolbar"><strong>Funnair · {{ user.displayName }}</strong><span>{{ user.role === 'ADMIN' ? '管理员' : '我的订单与客服' }}</span>
      <button @click="showAudits">查看操作记录</button><button :disabled="busy" @click="logout">退出登录</button>
    </header>
    <p v-if="error" role="alert" class="error banner">{{ error }}</p><p v-if="notice" role="status" class="banner">{{ notice }}</p>
    <details v-if="user.role === 'ADMIN'" class="admin">
      <summary>管理员：分配已有模拟订单</summary>
      <p>历史订单不会按姓名自动绑定。先让用户注册，再将订单明确分配给账号。重新分配会撤销原账号权限。</p>
      <button @click="adminData().catch(e => error = e.message)">刷新用户与归属</button>
      <div v-for="item in assignments" :key="item.bookingNumber" class="assignment">
        <span>订单 {{ item.bookingNumber }} · {{ item.name }} · 当前：{{ item.username || '未分配' }}</span>
        <select v-model="selected[item.bookingNumber]" :aria-label="'订单'+item.bookingNumber+'归属账号'">
          <option :value="undefined" disabled>选择账号</option><option v-for="u in users" :key="u.id" :value="u.id">{{ u.username }} · {{ u.displayName }}</option>
        </select><button :disabled="busy || !selected[item.bookingNumber]" @click="assign(item.bookingNumber)">分配</button>
      </div>
    </details>
    <section v-if="audits !== null" class="admin"><button @click="audits = null">收起记录</button><p v-if="!audits.length">暂无操作记录。</p>
      <article v-for="(item,index) in audits" :key="index"><strong>订单 {{ item.bookingNumber }} · {{ item.action }} · {{ item.result }}</strong>
        <p>{{ item.message }} · {{ item.occurredAt }} · 操作账号ID：{{ item.actorUserId ?? '历史记录/系统' }}</p>
        <p>{{ item.before?.date }} → {{ item.after?.date }} · {{ item.after?.departure }} → {{ item.after?.destination }}</p>
      </article>
    </section>
    <Home :key="user.id+':'+homeKey" />
  </template>
</template>
<style scoped>
.login-card{max-width:480px;margin:7vh auto;padding:32px;background:white;border:1px solid #ddd;border-radius:12px}.login-card label{display:block;margin:16px 0}.login-card input{display:block;width:100%;padding:10px;margin-top:6px;border:1px solid #aaa;border-radius:5px;box-sizing:border-box}button,select{padding:7px 12px;margin:4px;border:1px solid #aaa;border-radius:5px;cursor:pointer;background:white}button:disabled{opacity:.5;cursor:wait}.toolbar{display:flex;align-items:center;gap:20px;padding:12px 20px;background:#edf4ff;flex-wrap:wrap}.banner{margin:0;padding:10px 20px;background:#fff9df}.error{color:#a41616}.admin{padding:16px 20px;background:#f8fafc;border-bottom:1px solid #ddd}.assignment{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.admin article{border-top:1px solid #ddd;padding:8px}summary{cursor:pointer;font-weight:bold}
</style>
