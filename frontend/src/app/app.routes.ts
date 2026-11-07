import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';
import { authGuard } from './core/auth/auth.guard';
import { guestGuard } from './core/auth/guest.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadComponent: () => import('./features/admin/admin-console').then((m) => m.AdminConsole),
  },
  {
    path: 'login',
    canActivate: [guestGuard],
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
    path: 'help',
    canActivate: [authGuard],
    loadComponent: () => import('./features/help/help-page').then((m) => m.HelpPage),
  },
  {
    path: 'audit/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./features/audit/execution-detail').then((m) => m.ExecutionDetailPage),
  },
  // Unknown URLs land on the dashboard (or login, via its guard) instead of a blank page.
  { path: '**', redirectTo: 'dashboard' },
];
