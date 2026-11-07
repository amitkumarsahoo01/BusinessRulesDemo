import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ChatApiService } from '../../core/api/chat-api.service';
import { ChatSession } from './chat-session';
import { MarkdownView } from '../../shared/markdown-view/markdown-view';

@Component({
  selector: 'app-chat-page',
  imports: [FormsModule, MarkdownView],
  templateUrl: './chat-page.html',
  styleUrl: './chat-page.scss',
})
export class ChatPage implements OnInit {
  readonly session = new ChatSession(inject(ChatApiService));
  readonly parseToolCalls = ChatSession.parseToolCalls;
  draft = '';

  constructor(private readonly route: ActivatedRoute) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    this.session.start({ modelKey: params.get('modelKey'), executionId: params.get('executionId') });
  }

  ask(question: string): void {
    this.draft = question;
    this.send();
  }

  send(): void {
    if (this.session.send(this.draft)) {
      this.draft = '';
    }
  }
}
