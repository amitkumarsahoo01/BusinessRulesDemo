import { Component, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ModelsApiService } from '../../core/api/models-api.service';
import { ModelSummary } from '../../core/models/rules.models';

@Component({
  selector: 'app-rules-list',
  imports: [RouterLink],
  templateUrl: './rules-list.html',
  styleUrl: './rules-list.scss',
})
export class RulesList implements OnInit {
  readonly models = signal<ModelSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  constructor(private readonly modelsApi: ModelsApiService) {}

  ngOnInit(): void {
    this.modelsApi.listModels().subscribe({
      next: (models) => {
        this.models.set(models);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load models.');
        this.loading.set(false);
      },
    });
  }
}
