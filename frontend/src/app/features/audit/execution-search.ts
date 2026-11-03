import { DatePipe } from '@angular/common';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ExecutionsApiService } from '../../core/api/executions-api.service';
import { ExecutionSearchFilter, ExecutionSummary, PageResult } from '../../core/models/audit.models';

@Component({
  selector: 'app-execution-search',
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './execution-search.html',
  styleUrl: './execution-search.scss',
})
export class ExecutionSearch implements OnInit {
  readonly result = signal<PageResult<ExecutionSummary> | null>(null);
  readonly loading = signal(true);

  modelKey = '';
  status = '';
  caller = '';
  page = 0;
  readonly pageSize = 20;

  constructor(private readonly executionsApi: ExecutionsApiService) {}

  ngOnInit(): void {
    this.search();
  }

  search(): void {
    this.loading.set(true);
    const filter: ExecutionSearchFilter = {
      modelKey: this.modelKey || undefined,
      status: this.status || undefined,
      caller: this.caller || undefined,
      page: this.page,
      size: this.pageSize,
    };
    this.executionsApi.search(filter).subscribe({
      next: (result) => {
        this.result.set(result);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  nextPage(): void {
    const r = this.result();
    if (r && (this.page + 1) * this.pageSize < r.total) {
      this.page++;
      this.search();
    }
  }

  prevPage(): void {
    if (this.page > 0) {
      this.page--;
      this.search();
    }
  }
}
