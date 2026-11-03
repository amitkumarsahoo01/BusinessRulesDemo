import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { ChatMessage, StartConversationRequest } from '../models/chat.models';

@Injectable({ providedIn: 'root' })
export class ChatApiService {
  constructor(
    private readonly http: HttpClient,
    private readonly auth: AuthService,
  ) {}

  startConversation(request: Partial<StartConversationRequest>): Observable<{ conversationId: string }> {
    return this.http.post<{ conversationId: string }>('/api/ai/conversations', request);
  }

  history(conversationId: string): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>(`/api/ai/conversations/${conversationId}/messages`);
  }

  /**
   * Streams the assistant's reply token-by-token. Uses fetch() with a manual SSE line parser
   * (not the native EventSource) because sending a POST body and an X-User header isn't possible
   * with EventSource, which only supports GET with no custom headers.
   */
  async *streamMessage(conversationId: string, content: string): AsyncGenerator<string, void, unknown> {
    const username = this.auth.currentUser()?.username ?? 'anonymous';
    const response = await fetch(`/api/ai/conversations/${conversationId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User': username },
      body: JSON.stringify({ content }),
    });
    if (!response.ok || !response.body) {
      throw new Error(`Chat request failed: ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        if (line.startsWith('data:')) {
          yield line.slice(5).replace(/^ /, '');
        }
      }
    }
  }
}
