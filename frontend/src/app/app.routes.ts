import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'rules', pathMatch: 'full' },
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'rules',
    canActivate: [authGuard],
    loadComponent: () => import('./features/rules/rules-list').then((m) => m.RulesList),
  },
  {
    path: 'rules/:modelKey',
    canActivate: [authGuard],
    loadComponent: () => import('./features/rules/rule-editor-page').then((m) => m.RuleEditorPage),
  },
  {
    path: 'ask-ai',
    canActivate: [authGuard],
    loadComponent: () => import('./features/chat/chat-page').then((m) => m.ChatPage),
  },
  {
    path: 'audit',
    canActivate: [authGuard],
    loadComponent: () => import('./features/audit/execution-search').then((m) => m.ExecutionSearch),
  },
  {
    path: 'audit/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./features/audit/execution-detail').then((m) => m.ExecutionDetailPage),
  },
];
