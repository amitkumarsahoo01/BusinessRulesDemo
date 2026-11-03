import { DatePipe, JsonPipe } from '@angular/common';
import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ExecutionsApiService } from '../../core/api/executions-api.service';
import { ExecutionDetail } from '../../core/models/audit.models';
import { TraceView } from '../../shared/trace-view/trace-view';

@Component({
  selector: 'app-execution-detail-page',
  imports: [RouterLink, JsonPipe, DatePipe, TraceView],
  templateUrl: './execution-detail.html',
  styleUrl: './execution-detail.scss',
})
export class ExecutionDetailPage implements OnInit {
  readonly execution = signal<ExecutionDetail | null>(null);
  readonly loading = signal(true);
  readonly notFound = signal(false);

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly executionsApi: ExecutionsApiService,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.executionsApi.getById(id).subscribe({
      next: (execution) => {
        this.execution.set(execution);
        this.loading.set(false);
      },
      error: () => {
        this.notFound.set(true);
        this.loading.set(false);
      },
    });
  }

  askAiAboutThis(): void {
    const execution = this.execution();
    if (execution) {
      this.router.navigate(['/ask-ai'], { queryParams: { executionId: execution.id } });
    }
  }
}
