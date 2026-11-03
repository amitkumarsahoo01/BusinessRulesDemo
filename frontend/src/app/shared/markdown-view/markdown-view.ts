import { Component, ViewEncapsulation, computed, input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';

@Component({
  selector: 'app-markdown-view',
  template: `<div class="markdown-body" [innerHTML]="html()"></div>`,
  styleUrl: './markdown-view.scss',
  // Angular's emulated view encapsulation can't scope selectors onto content set via
  // [innerHTML] (it never runs through Angular's template compiler), so these styles need to be
  // unencapsulated to reach the markdown-generated HTML at all.
  encapsulation: ViewEncapsulation.None,
})
export class MarkdownView {
  readonly content = input('');

  // marked output only ever contains markdown-generated HTML (headings, lists, emphasis, code,
  // links) from the assistant's own response text, not raw user-supplied HTML, so sanitizing it
  // (stripping anything unsafe like script tags) rather than trusting it outright is the right
  // balance of safety and not hand-rolling a renderer.
  readonly html = computed<SafeHtml>(() => {
    const parsed = marked.parse(this.content(), { async: false, breaks: true }) as string;
    return this.sanitizer.sanitize(1 /* SecurityContext.HTML */, parsed) ?? '';
  });

  constructor(private readonly sanitizer: DomSanitizer) {}
}
