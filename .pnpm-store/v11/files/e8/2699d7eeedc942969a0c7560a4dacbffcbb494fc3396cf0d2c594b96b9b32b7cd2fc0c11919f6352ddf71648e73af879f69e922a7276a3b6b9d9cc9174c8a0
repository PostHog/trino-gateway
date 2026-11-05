/**
 * Escape HTML angle brackets in markdown text, preserving code blocks and inline code.
 *
 * In `format='md'` mode, @mdx-js/mdx uses `rehypeRemoveRaw` which strips all raw HTML nodes.
 * This causes user-typed HTML-like content (e.g. `<AgentChat />`) to silently disappear.
 * By escaping `<` to `&lt;` outside of code spans/blocks, the markdown parser treats them
 * as literal text instead of HTML tags.
 */
export declare function escapeHtmlInMarkdown(text: string): string;
