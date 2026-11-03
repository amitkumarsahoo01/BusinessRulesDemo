import { Component, input } from '@angular/core';
import { TraceStep } from '../../core/models/audit.models';

@Component({
  selector: 'app-trace-view',
  templateUrl: './trace-view.html',
  styleUrl: './trace-view.scss',
})
export class TraceView {
  readonly trace = input<TraceStep[]>([]);

  resultText(result: unknown): string {
    if (result === null || result === undefined) {
      return '—';
    }
    return typeof result === 'object' ? JSON.stringify(result) : String(result);
  }
}
