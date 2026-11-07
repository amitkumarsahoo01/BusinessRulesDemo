import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';

/**
 * Same-origin /api calls carry the signed-in user's session token (`Authorization: Bearer`); the
 * server derives the caller identity from it. `X-Requested-With` tells the server this is the web
 * app, so a 401 doesn't trigger the browser's own Basic-auth password dialog.
 *
 * A 401 means the session is missing, expired or predates tokens: sign out and go to the login page.
 */
export const userHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);
  const user = auth.currentUser();
  const headers: Record<string, string> = { 'X-Requested-With': 'XMLHttpRequest' };
  if (user?.token) {
    headers['Authorization'] = `Bearer ${user.token}`;
  }
  return next(req.clone({ setHeaders: headers })).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !req.url.startsWith('/api/auth/login')) {
        auth.logout();
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
