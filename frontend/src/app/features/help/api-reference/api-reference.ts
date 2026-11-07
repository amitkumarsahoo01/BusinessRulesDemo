import { Component, computed, signal } from '@angular/core';
import { ACCESS_HELP, ACCESS_LABELS, API_GROUPS, Access, ApiEndpoint, SAMPLE_EVALUATE_BODY } from './api-endpoints';

interface Example {
  id: string;
  title: string;
  command: string;
}

/** REST API reference for the decision engine, with copy-ready curl examples for this deployment. */
@Component({
  selector: 'app-api-reference',
  templateUrl: './api-reference.html',
  styleUrl: './api-reference.scss',
})
export class ApiReference {
  readonly groups = API_GROUPS;
  readonly accessLabels = ACCESS_LABELS;
  readonly accessHelp = ACCESS_HELP;
  readonly accessLevels: Access[] = ['open', 'any', 'users'];
  readonly baseUrl = typeof window === 'undefined' ? 'https://<your-host>' : window.location.origin;
  readonly filter = signal('');
  readonly copied = signal<string | null>(null);

  readonly filteredGroups = computed(() => {
    const q = this.filter().trim().toLowerCase();
    if (!q) return this.groups;
    return this.groups
      .map((g) => ({
        ...g,
        endpoints: g.endpoints.filter((e) => `${e.method} ${e.path} ${e.summary}`.toLowerCase().includes(q)),
      }))
      .filter((g) => g.endpoints.length > 0);
  });

  readonly endpointCount = API_GROUPS.reduce((n, g) => n + g.endpoints.length, 0);

  readonly examples: Example[] = [
    {
      id: 'evaluate',
      title: 'Evaluate a decision (HTTP Basic)',
      command: [
        `curl -u analyst:demo \\`,
        `  -X POST "${this.baseUrl}/api/decisions/loan-eligibility/evaluate?trace=true" \\`,
        `  -H "Content-Type: application/json" \\`,
        `  -d '${JSON.stringify(SAMPLE_EVALUATE_BODY)}'`,
      ].join('\n'),
    },
    {
      id: 'api-client',
      title: 'Integration account (evaluate, simulate and read only)',
      command: [
        `curl -u "api-client:$API_CLIENT_PASSWORD" \\`,
        `  -X POST "${this.baseUrl}/api/decisions/loan-eligibility/simulate" \\`,
        `  -H "Content-Type: application/json" \\`,
        `  -d '${JSON.stringify(SAMPLE_EVALUATE_BODY)}'`,
      ].join('\n'),
    },
    {
      id: 'search',
      title: 'Search the audit trail',
      command: `curl -u auditor:demo "${this.baseUrl}/api/executions?modelKey=loan-eligibility&status=SUCCEEDED&size=5"`,
    },
    {
      id: 'token',
      title: 'Bearer token instead of Basic (what the web app does)',
      command: [
        `TOKEN=$(curl -s -X POST "${this.baseUrl}/api/auth/login" \\`,
        `  -H "Content-Type: application/json" \\`,
        `  -d '{"username":"analyst","password":"demo"}' | jq -r .token)`,
        `curl -H "Authorization: Bearer $TOKEN" "${this.baseUrl}/api/models"`,
      ].join('\n'),
    },
  ];

  copy(id: string, text: string): void {
    const done = () => {
      this.copied.set(id);
      setTimeout(() => this.copied.set(this.copied() === id ? null : this.copied()), 1500);
    };
    try {
      void navigator.clipboard?.writeText(text).then(done, () => undefined);
    } catch {
      // Clipboard unavailable (e.g. insecure context): the text is still selectable.
    }
  }

  fullPath(endpoint: ApiEndpoint): string {
    return endpoint.path;
  }
}
