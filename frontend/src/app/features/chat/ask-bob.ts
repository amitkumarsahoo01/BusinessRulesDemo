import { Component, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';
import { ChatApiService } from '../../core/api/chat-api.service';
import { MarkdownView } from '../../shared/markdown-view/markdown-view';
import { AskBobLauncher } from './ask-bob-launcher';
import { ChatContext, ChatSession } from './chat-session';

/** Reads the page's subject from the URL: /rules/:modelKey pins a model, /audit/:id an execution. */
export function contextFromUrl(url: string): ChatContext {
  const path = url.split(/[?#]/)[0];
  const rules = /^\/rules\/([^/]+)/.exec(path);
  if (rules) {
    return { modelKey: decodeURIComponent(rules[1]), executionId: null };
  }
  const audit = /^\/audit\/([0-9a-f-]{36})/i.exec(path);
  if (audit) {
    return { modelKey: null, executionId: audit[1] };
  }
  return { modelKey: null, executionId: null };
}

/**
 * "Ask Bob": the assistant as a popup in the bottom-right corner of every page. Same tools, same
 * citations and the same audit trail as the full Ask AI page; it just goes wherever you go.
 */
@Component({
  selector: 'app-ask-bob',
  imports: [FormsModule, MarkdownView, RouterLink],
  templateUrl: './ask-bob.html',
  styleUrl: './ask-bob.scss',
})
export class AskBob {
  private readonly router = inject(Router);
  readonly session = new ChatSession(inject(ChatApiService));
  readonly parseToolCalls = ChatSession.parseToolCalls;

  readonly open = signal(false);
  draft = '';

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /** Hidden on the full chat page, where it would just duplicate it. */
  readonly visible = computed(() => !this.url().startsWith('/ask-ai'));
  readonly pageContext = computed(() => contextFromUrl(this.url()));
  /** The page changed subject since this conversation started: offer to start one about it. */
  readonly contextChanged = computed(() => {
    const page = this.pageContext();
    const current = this.session.context();
    return (
      this.session.conversationId() !== null &&
      (page.modelKey !== current.modelKey || page.executionId !== current.executionId) &&
      (page.modelKey !== null || page.executionId !== null)
    );
  });

  private readonly messageList = viewChild<ElementRef<HTMLElement>>('messageList');
  private readonly input = viewChild<ElementRef<HTMLInputElement>>('composerInput');

  constructor() {
    // Open when another page asks (e.g. the audit page's "Ask Bob about this execution").
    const launcher = inject(AskBobLauncher);
    let seen = launcher.openRequests();
    effect(() => {
      const requested = launcher.openRequests();
      if (requested !== seen) {
        seen = requested;
        untracked(() => this.openForCurrentPage());
      }
    });

    // Keep the newest message in view while streaming.
    effect(() => {
      this.session.messages();
      this.session.streamingText();
      const list = this.messageList()?.nativeElement;
      if (list) {
        queueMicrotask(() => (list.scrollTop = list.scrollHeight));
      }
    });
  }

  toggle(): void {
    const opening = !this.open();
    this.open.set(opening);
    if (opening) {
      if (this.session.conversationId() === null && !this.session.error()) {
        this.session.start(this.pageContext());
      }
      queueMicrotask(() => this.input()?.nativeElement.focus());
    }
  }

  /** Opens the panel about this page, starting a fresh chat if the current one is about something else. */
  private openForCurrentPage(): void {
    const page = this.pageContext();
    const current = this.session.context();
    const sameSubject = page.modelKey === current.modelKey && page.executionId === current.executionId;
    if (this.session.conversationId() === null || !sameSubject) {
      this.session.start(page);
    }
    this.open.set(true);
    queueMicrotask(() => this.input()?.nativeElement.focus());
  }

  newChat(): void {
    this.session.start(this.pageContext());
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

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.open.set(false);
    }
  }

  fullChatQuery(): Record<string, string> {
    const { modelKey, executionId } = this.session.context();
    const query: Record<string, string> = {};
    if (modelKey) query['modelKey'] = modelKey;
    if (executionId) query['executionId'] = executionId;
    return query;
  }
}
