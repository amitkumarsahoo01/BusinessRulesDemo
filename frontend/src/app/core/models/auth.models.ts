export interface User {
  username: string;
  displayName: string;
  role: string;
  /** Signed session token from /api/auth/login; sent as `Authorization: Bearer`. Absent for sessions stored before tokens existed. */
  token?: string;
}

export const ADMIN_ROLE = 'admin';
