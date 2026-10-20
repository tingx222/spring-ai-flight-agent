declare module 'vue3-markdown-it' {
  import type { DefineComponent, Plugin } from 'vue';
  const Markdown: DefineComponent<{ source?: string; html?: boolean }> & Plugin;
  export default Markdown;
}
