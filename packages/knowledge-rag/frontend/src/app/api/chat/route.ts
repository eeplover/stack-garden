import { createUIMessageStream, createUIMessageStreamResponse } from 'ai';

/**
 * 这个 Next.js API Route 是"SSE → UI Message Stream" 适配层。
 *
 * Spring Boot（Spring AI）输出的是**纯文本 token** 的 SSE：
 *   data: 你\n\n
 *   data: 好\n\n
 *   data: [DONE]\n\n
 *
 * AI SDK 6 里 DefaultChatTransport 期望响应体是 **JSON 事件的 SSE**（每条 data: 一行 JSON），
 * 对应内部的 UIMessageChunk（text-start / text-delta / …）。
 *
 * 旧版文档里的 createDataStreamResponse / writeTextDelta 已在 ai@6 中移除；
 * 请使用 createUIMessageStream + createUIMessageStreamResponse，并用 writer.write() 发出 chunk。
 *
 * Spring 发出的 SSE 常为 `data:正文`（冒号后无空格）。若只匹配 `data: `，会导致正文全部被丢弃。
 */
function ssePayloadFromLine(line: string): string | null {
  const trimmed = line.trimEnd();
  if (!trimmed.startsWith('data:')) return null;
  let value = trimmed.slice('data:'.length);
  if (value.startsWith(' ')) value = value.slice(1);
  value = value.trim();
  if (!value || value === '[DONE]') return null;
  return value;
}

function textFromUiMessage(message: { parts?: Array<{ type: string; text?: string }>; content?: string }) {
  if (message && typeof message.content === 'string') return message.content;
  if (!message?.parts?.length) return '';
  return message.parts
    .filter((p): p is { type: 'text'; text: string } => p.type === 'text')
    .map((p) => p.text)
    .join('');
}

export async function POST(req: Request) {
  const { messages } = await req.json();

  // useChat 把完整消息历史传来，我们只取最后一条发给 Spring Boot。
  // 对话历史由后端 MessageChatMemoryAdvisor 管理，前端无需传递全部历史。
  const lastMessage = messages[messages.length - 1];
  const userText = textFromUiMessage(lastMessage);

  const stream = createUIMessageStream({
    execute: async ({ writer }) => {
      const textId = 'assistant-text';

      writer.write({ type: 'start' });
      writer.write({ type: 'start-step' });
      writer.write({ type: 'text-start', id: textId });

      const response = await fetch('http://localhost:8080/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: userText }),
      });

      if (!response.ok || !response.body) {
        writer.write({
          type: 'error',
          errorText: `Spring upstream failed: ${response.status}`,
        });
        writer.write({ type: 'text-end', id: textId });
        writer.write({ type: 'finish-step' });
        writer.write({ type: 'finish', finishReason: 'error' });
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let sseBuffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        sseBuffer += decoder.decode(value, { stream: true });

        let newlineIndex: number;
        while ((newlineIndex = sseBuffer.indexOf('\n')) >= 0) {
          const line = sseBuffer.slice(0, newlineIndex).trimEnd();
          sseBuffer = sseBuffer.slice(newlineIndex + 1);

          const token = ssePayloadFromLine(line);
          if (token) {
            writer.write({ type: 'text-delta', id: textId, delta: token });
          }
        }
      }

      sseBuffer += decoder.decode();
      if (sseBuffer.trim()) {
        for (const line of sseBuffer.split('\n')) {
          const trimmed = line.trimEnd();
          const token = ssePayloadFromLine(trimmed);
          if (token) {
            writer.write({ type: 'text-delta', id: textId, delta: token });
          }
        }
      }

      writer.write({ type: 'text-end', id: textId });
      writer.write({ type: 'finish-step' });
      writer.write({ type: 'finish', finishReason: 'stop' });
    },
  });

  return createUIMessageStreamResponse({ stream });
}