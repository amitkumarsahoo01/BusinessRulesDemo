import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';

/** Attaches X-User so the backend's caller/author/approver identity matches who's logged in. */
export const userHeaderInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(AuthService).currentUser();
  if (!user) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { 'X-User': user.username } }));
};
