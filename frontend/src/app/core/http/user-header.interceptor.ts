import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';

/**
 * Attaches X-User so the backend's caller/author/approver identity matches who's logged in, and
 * the signed session token (when present) for endpoints that verify identity, like the admin API.
 * Only same-origin /api calls get the token.
 */
export const userHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(AuthService).currentUser();
  if (!user) {
    return next(req);
  }
  const headers: Record<string, string> = { 'X-User': user.username };
  if (user.token && req.url.startsWith('/api/')) {
    headers['Authorization'] = `Bearer ${user.token}`;
  }
  return next(req.clone({ setHeaders: headers }));
};
