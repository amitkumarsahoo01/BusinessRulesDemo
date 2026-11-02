import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'rules', pathMatch: 'full' },
  {
    path: 'rules',
    loadComponent: () => import('./features/rules/rules-page').then((m) => m.RulesPage),
  },
  {
    path: 'ask-ai',
    loadComponent: () => import('./features/chat/chat-page').then((m) => m.ChatPage),
  },
  {
    path: 'audit',
    loadComponent: () => import('./features/audit/audit-page').then((m) => m.AuditPage),
  },
];
