'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

// ─── 类型 ────────────────────────────────────────────────────────────────────

interface DocumentItem {
  id: string;
  originalFilename: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  fileSize: number;
  chunkCount: number | null;
  createdAt: string;
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const STATUS_LABEL: Record<DocumentItem['status'], string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  FAILED: '失败',
};

const STATUS_CLASS: Record<DocumentItem['status'], string> = {
  PENDING: 'bg-yellow-100 dark:bg-yellow-900/40 text-yellow-700 dark:text-yellow-400',
  PROCESSING: 'bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-400',
  COMPLETED: 'bg-green-100 dark:bg-green-900/40 text-green-700 dark:text-green-400',
  FAILED: 'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-400',
};

// ─── 组件 ────────────────────────────────────────────────────────────────────

export default function DocumentsPage() {
  const [docs, setDocs] = useState<DocumentItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // ── 拉取列表 ──────────────────────────────────────────────────────────────

  const fetchDocs = useCallback(async () => {
    try {
      const res = await fetch('http://localhost:8080/api/documents');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setDocs(await res.json());
    } catch {
      // 轮询失败静默忽略，避免频繁弹错
    }
  }, []);

  // 首次加载 + 每 3 秒轮询
  useEffect(() => {
    fetchDocs();
    const timer = setInterval(fetchDocs, 3000);
    return () => clearInterval(timer);
  }, [fetchDocs]);

  // ── 上传 ─────────────────────────────────────────────────────────────────

  async function uploadFile(file: File) {
    setUploading(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await fetch('http://localhost:8080/api/documents', {
        method: 'POST',
        body: form,
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `HTTP ${res.status}`);
      }
      await fetchDocs();
    } catch (e) {
      setError(e instanceof Error ? e.message : '上传失败');
    } finally {
      setUploading(false);
    }
  }

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) uploadFile(file);
    // 重置 input，允许重复选同一文件
    e.target.value = '';
  }

  // ── 拖拽 ─────────────────────────────────────────────────────────────────

  function onDragOver(e: React.DragEvent) {
    e.preventDefault();
    setDragging(true);
  }

  function onDragLeave() {
    setDragging(false);
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) uploadFile(file);
  }

  // ─────────────────────────────────────────────────────────────────────────

  return (
    <main className="max-w-3xl mx-auto p-6 space-y-6">
      <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">文档管理</h1>

      {/* 上传区域 */}
      <div
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        className={`border-2 border-dashed rounded-xl p-10 text-center cursor-pointer transition-colors
          ${dragging
            ? 'border-blue-500 bg-blue-50 dark:bg-blue-950/40'
            : 'border-gray-300 dark:border-gray-600 hover:border-blue-400 hover:bg-gray-50 dark:hover:bg-gray-800/50'}
          ${uploading ? 'opacity-50 pointer-events-none' : ''}`}
      >
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.txt,.md,.doc,.docx"
          className="hidden"
          onChange={onFileChange}
        />
        <p className="text-4xl mb-2">📄</p>
        {uploading ? (
          <p className="text-gray-500 dark:text-gray-400">上传中…</p>
        ) : (
          <>
            <p className="text-gray-700 dark:text-gray-300 font-medium">拖拽文件到此处，或点击选择</p>
            <p className="text-sm text-gray-400 dark:text-gray-500 mt-1">支持 PDF、TXT、Markdown、Word</p>
          </>
        )}
      </div>

      {/* 错误提示 */}
      {error && (
        <div className="bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-800 text-red-700 dark:text-red-400 rounded-lg px-4 py-3 text-sm">
          {error}
        </div>
      )}

      {/* 文档列表 */}
      {docs.length === 0 ? (
        <p className="text-gray-400 dark:text-gray-500 text-center py-10">暂无文档，请先上传</p>
      ) : (
        <div className="space-y-2">
          {docs.map((doc) => (
            <div
              key={doc.id}
              className="flex items-center justify-between bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-700 rounded-lg px-4 py-3 shadow-sm"
            >
              {/* 文件名 + 元信息 */}
              <div className="min-w-0 flex-1 pr-4">
                <p className="font-medium truncate text-gray-900 dark:text-gray-100">{doc.originalFilename}</p>
                <p className="text-xs text-gray-400 dark:text-gray-500 mt-0.5">
                  {formatFileSize(doc.fileSize)}
                  {doc.chunkCount != null && ` · ${doc.chunkCount} 个分块`}
                  {' · '}
                  {new Date(doc.createdAt).toLocaleString('zh-CN')}
                </p>
              </div>

              {/* 状态徽章 */}
              <span
                className={`shrink-0 text-xs font-semibold px-2.5 py-1 rounded-full ${STATUS_CLASS[doc.status]}`}
              >
                {doc.status === 'PROCESSING' && (
                  <span className="mr-1 inline-block animate-spin">⟳</span>
                )}
                {STATUS_LABEL[doc.status]}
              </span>
            </div>
          ))}
        </div>
      )}
    </main>
  );
}
