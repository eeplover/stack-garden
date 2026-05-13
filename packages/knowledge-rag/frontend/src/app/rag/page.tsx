'use client';

import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react';

// ─── SSE 解析 ────────────────────────────────────────────────────────────────

function parseSseLine(line: string): string | null {
  if (!line.startsWith('data:')) return null;
  let value = line.slice('data:'.length);
  if (value.startsWith(' ')) value = value.slice(1);
  value = value.trim();
  if (!value || value === '[DONE]') return null;
  return value;
}

// ─── 类型 ────────────────────────────────────────────────────────────────────

interface Conversation {
  id: string;
  title: string;
  createdAt: string;
}

interface HistoryMessage {
  id: string;
  conversationId: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  sources: string | null;   // JSON 字符串，反序列化为 RetrievedChunk[]
  feedback: 'LIKED' | 'DISLIKED' | null;
  createdAt: string;
}

interface RetrievedChunk {
  id: string;
  content: string;
  documentId: string;
  score: number;
}

// ─── 工具 ────────────────────────────────────────────────────────────────────

const API = 'http://localhost:8080';

function similarityPct(score: number) {
  return Math.round((1 - score / 2) * 100);
}

// ─── 子组件：消息气泡 ─────────────────────────────────────────────────────────

function MessageBubble({
  msg,
  onFeedback,
}: {
  msg: HistoryMessage;
  onFeedback: (id: string, type: 'LIKED' | 'DISLIKED') => void;
}) {
  const [open, setOpen] = useState(false);
  const chunks: RetrievedChunk[] = msg.sources ? JSON.parse(msg.sources) : [];

  if (msg.role === 'USER') {
    return (
      <div className="flex justify-end">
        <div className="max-w-[75%] bg-blue-500 text-white rounded-2xl rounded-tr-sm px-4 py-2.5 text-sm">
          {msg.content}
        </div>
      </div>
    );
  }

  return (
    <div className="flex justify-start">
      <div className="max-w-[85%] space-y-2">
        {/* 引用来源（可折叠） */}
        {chunks.length > 0 && (
          <div className="text-xs">
            <button
              onClick={() => setOpen((o) => !o)}
              className="text-gray-400 hover:text-gray-600 flex items-center gap-1"
            >
              <span>{open ? '▾' : '▸'}</span>
              <span>引用 {chunks.length} 个来源</span>
            </button>
            {open && (
              <div className="mt-1 space-y-1">
                {chunks.map((c, i) => (
                  <div key={c.id} className="border border-gray-200 dark:border-gray-700 rounded-lg p-2 bg-gray-50 dark:bg-gray-800">
                    <div className="flex justify-between text-gray-400 dark:text-gray-500 mb-0.5">
                      <span>#{i + 1} · {c.documentId.slice(0, 8)}…</span>
                      <span>{similarityPct(c.score)}%</span>
                    </div>
                    <p className="text-gray-700 dark:text-gray-300 line-clamp-2">{c.content}</p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* 回答正文 */}
        <div className="bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-2xl rounded-tl-sm px-4 py-2.5 text-sm text-gray-800 dark:text-gray-100 whitespace-pre-wrap">
          {msg.content}
        </div>

        {/* 反馈按钮 */}
        <div className="flex gap-2 pl-1">
          <button
            onClick={() => onFeedback(msg.id, 'LIKED')}
            className={`text-sm px-2 py-0.5 rounded-full border transition-colors ${
              msg.feedback === 'LIKED'
                ? 'border-green-500 text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-950/40'
                : 'border-gray-200 dark:border-gray-600 text-gray-400 hover:border-green-400 hover:text-green-500'
            }`}
          >
            👍
          </button>
          <button
            onClick={() => onFeedback(msg.id, 'DISLIKED')}
            className={`text-sm px-2 py-0.5 rounded-full border transition-colors ${
              msg.feedback === 'DISLIKED'
                ? 'border-red-400 text-red-500 dark:text-red-400 bg-red-50 dark:bg-red-950/40'
                : 'border-gray-200 dark:border-gray-600 text-gray-400 hover:border-red-300 hover:text-red-400'
            }`}
          >
            👎
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── 主组件 ───────────────────────────────────────────────────────────────────

export default function RagPage() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [history, setHistory] = useState<HistoryMessage[]>([]);

  const [input, setInput] = useState('');
  const [streamAnswer, setStreamAnswer] = useState('');
  const [streamSources, setStreamSources] = useState<RetrievedChunk[]>([]);
  const [busy, setBusy] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  // ── 加载对话列表 ─────────────────────────────────────────────────────────

  const fetchConversations = useCallback(async () => {
    try {
      const res = await fetch(`${API}/api/conversations`);
      if (res.ok) setConversations(await res.json());
    } catch {
      // 后端未启动或网络错误时静默忽略，不崩溃页面
    }
  }, []);

  useEffect(() => { fetchConversations(); }, [fetchConversations]);

  // ── 切换会话 → 加载历史 ──────────────────────────────────────────────────

  async function selectConversation(id: string) {
    setActiveId(id);
    setStreamAnswer('');
    setStreamSources([]);
    try {
      const res = await fetch(`${API}/api/conversations/${id}/messages`);
      if (res.ok) setHistory(await res.json());
    } catch {
      // 忽略
    }
  }

  // ── 新建对话 ─────────────────────────────────────────────────────────────

  async function newConversation(title: string) {
    const res = await fetch(`${API}/api/conversations`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    });
    if (!res.ok) return null;
    const conv: Conversation = await res.json();
    await fetchConversations();
    return conv;
  }

  // ── 保存消息到 DB ────────────────────────────────────────────────────────

  async function saveMessage(
    conversationId: string,
    role: 'USER' | 'ASSISTANT',
    content: string,
    sources: RetrievedChunk[] | null,
  ): Promise<HistoryMessage> {
    const res = await fetch(`${API}/api/conversations/${conversationId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        role,
        content,
        sources: sources ? JSON.stringify(sources) : null,
      }),
    });
    return res.json();
  }

  // ── 反馈 ────────────────────────────────────────────────────────────────

  async function onFeedback(messageId: string, type: 'LIKED' | 'DISLIKED') {
    await fetch(`${API}/api/messages/${messageId}/feedback`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, comment: null }),
    });
    setHistory((prev) =>
      prev.map((m) => (m.id === messageId ? { ...m, feedback: type } : m)),
    );
  }

  // ── 自动滚底 ────────────────────────────────────────────────────────────

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [history, streamAnswer]);

  // ── 提交问题 ─────────────────────────────────────────────────────────────

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const question = input.trim();
    if (!question || busy) return;

    abortRef.current?.abort();
    abortRef.current = new AbortController();
    const signal = abortRef.current.signal;

    // 确保有会话
    let convId = activeId;
    if (!convId) {
      const conv = await newConversation(question.slice(0, 30));
      if (!conv) return;
      convId = conv.id;
      setActiveId(convId);
      setHistory([]);
    }

    setBusy(true);
    setInput('');
    setStreamAnswer('');
    setStreamSources([]);

    try {
      // Step 1：同步获取引用 chunks
      const srcRes = await fetch(`${API}/api/rag/sources`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, conversationId: convId }),
        signal,
      });
      if (!srcRes.ok) throw new Error(`sources: ${srcRes.status}`);
      const chunks: RetrievedChunk[] = await srcRes.json();
      setStreamSources(chunks);

      // Step 2：流式读取回答
      const streamRes = await fetch(`${API}/api/rag/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, conversationId: convId }),
        signal,
      });
      if (!streamRes.ok || !streamRes.body) throw new Error(`stream: ${streamRes.status}`);

      const reader = streamRes.body.getReader();
      const decoder = new TextDecoder();
      let sseBuffer = '';
      let fullAnswer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        sseBuffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = sseBuffer.indexOf('\n')) >= 0) {
          const line = sseBuffer.slice(0, idx).trimEnd();
          sseBuffer = sseBuffer.slice(idx + 1);
          const token = parseSseLine(line);
          if (token) {
            fullAnswer += token;
            setStreamAnswer(fullAnswer);
          }
        }
      }

      // Step 3：保存到 DB，追加到 history
      const userMsg = await saveMessage(convId, 'USER', question, null);
      const assistantMsg = await saveMessage(convId, 'ASSISTANT', fullAnswer, chunks);

      setHistory((prev) => [...prev, userMsg, assistantMsg]);
      setStreamAnswer('');
      setStreamSources([]);
    } catch (err) {
      if (err instanceof Error && err.name !== 'AbortError') {
        setStreamAnswer('[请求出错：' + err.message + ']');
      }
    } finally {
      setBusy(false);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────

  return (
    <div className="flex h-screen bg-gray-50 dark:bg-gray-950">
      {/* ── 左栏：对话列表 ──────────────────────────────────────────────── */}
      <aside className="w-60 shrink-0 bg-gray-800 dark:bg-gray-900 text-white flex flex-col">
        <div className="p-3 border-b border-gray-700 dark:border-gray-800">
          <button
            onClick={() => {
              setActiveId(null);
              setHistory([]);
              setStreamAnswer('');
              setStreamSources([]);
            }}
            className="w-full text-left px-3 py-2 rounded-lg bg-gray-700 dark:bg-gray-800 hover:bg-gray-600 dark:hover:bg-gray-700 text-sm font-medium transition-colors"
          >
            ＋ 新建对话
          </button>
        </div>

        <nav className="flex-1 overflow-y-auto p-2 space-y-0.5">
          {conversations.length === 0 && (
            <p className="text-gray-500 text-xs px-3 py-4 text-center">暂无对话</p>
          )}
          {conversations.map((c) => (
            <button
              key={c.id}
              onClick={() => selectConversation(c.id)}
              className={`w-full text-left px-3 py-2 rounded-lg text-sm truncate transition-colors ${
                c.id === activeId
                  ? 'bg-gray-600 dark:bg-gray-700 text-white'
                  : 'text-gray-300 hover:bg-gray-700 dark:hover:bg-gray-800'
              }`}
            >
              {c.title}
            </button>
          ))}
        </nav>
      </aside>

      {/* ── 右栏：聊天区 ────────────────────────────────────────────────── */}
      <main className="flex flex-col flex-1 min-w-0">
        {/* 消息历史 */}
        <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4 max-w-3xl mx-auto w-full">
          {history.length === 0 && !busy && streamAnswer === '' && (
            <div className="flex flex-col items-center justify-center h-full text-gray-400 dark:text-gray-500 space-y-2">
              <p className="text-4xl">💬</p>
              <p className="text-sm">选择或新建对话，开始 RAG 问答</p>
            </div>
          )}

          {history.map((msg) => (
            <MessageBubble key={msg.id} msg={msg} onFeedback={onFeedback} />
          ))}

          {/* 当前流式输出（尚未保存到 DB） */}
          {(busy || streamAnswer) && (
            <>
              {streamSources.length > 0 && (
                <div className="text-xs text-gray-400 dark:text-gray-500 pl-1">
                  正在引用 {streamSources.length} 个来源…
                </div>
              )}
              <div className="flex justify-start">
                <div className="max-w-[85%] bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-2xl rounded-tl-sm px-4 py-2.5 text-sm text-gray-800 dark:text-gray-100 whitespace-pre-wrap">
                  {streamAnswer || <span className="text-gray-300 dark:text-gray-600">检索中…</span>}
                  {busy && <span className="animate-pulse ml-0.5">▋</span>}
                </div>
              </div>
            </>
          )}

          <div ref={bottomRef} />
        </div>

        {/* 输入框 */}
        <div className="border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 px-6 py-4">
          <form onSubmit={onSubmit} className="flex gap-2 max-w-3xl mx-auto">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="输入问题，将基于已上传文档回答…"
              disabled={busy}
              className="flex-1 border border-gray-300 dark:border-gray-600 rounded-xl px-4 py-2.5 text-sm
                         bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100
                         placeholder:text-gray-400 dark:placeholder:text-gray-500
                         focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
            />
            <button
              type="submit"
              disabled={busy || !input.trim()}
              className="bg-blue-500 text-white px-5 py-2.5 rounded-xl text-sm font-medium disabled:opacity-50 hover:bg-blue-600 transition-colors"
            >
              {busy ? '…' : '发送'}
            </button>
          </form>
        </div>
      </main>
    </div>
  );
}
