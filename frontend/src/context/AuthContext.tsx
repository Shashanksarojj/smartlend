import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from 'react';
import axios from 'axios';
import type { AuthUser } from '../types';
import { authApi, setInMemoryToken } from '../services/api';

const STORAGE_KEY = 'smartlend_user';

interface AuthContextValue {
  user: AuthUser | null;
  isAdmin: boolean;
  isRestoring: boolean;
  login: (user: AuthUser) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** Read profile from localStorage — token field is never persisted. */
function loadStoredProfile(): AuthUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  } catch {
    return null;
  }
}

/** Persist profile without the token. */
function saveProfile(user: AuthUser): void {
  const { token: _, ...profileOnly } = user;
  localStorage.setItem(STORAGE_KEY, JSON.stringify(profileOnly));
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  // true while we attempt to restore the session from the HttpOnly cookie on mount
  const [isRestoring, setIsRestoring] = useState(true);

  useEffect(() => {
    const stored = loadStoredProfile();
    if (!stored) {
      setIsRestoring(false);
      return;
    }

    // Profile exists — try to restore in-memory token via the HttpOnly cookie
    authApi.me()
      .then((fresh) => {
        setInMemoryToken(fresh.token ?? null);
        saveProfile(fresh);
        setUser({ ...fresh, token: undefined });
      })
      .catch((err) => {
        if (axios.isAxiosError(err) && err.response?.status === 401) {
          // Cookie is genuinely expired/invalid — sign out everywhere
          localStorage.removeItem(STORAGE_KEY);
          setUser(null);
        } else {
          // Transient failure (network, 500, timeout) — keep stored profile so
          // the user stays logged in; _token remains null until they navigate
          // to a page that triggers a fresh login prompt via the 401 interceptor
          setUser(stored);
        }
      })
      .finally(() => setIsRestoring(false));
  }, []);

  const login = useCallback((u: AuthUser) => {
    setInMemoryToken(u.token ?? null);
    saveProfile(u);
    setUser({ ...u, token: undefined });
  }, []);

  const logout = useCallback(() => {
    setInMemoryToken(null);
    setUser(null);
    localStorage.removeItem(STORAGE_KEY);
    // Clear the HttpOnly cookie server-side (fire-and-forget)
    authApi.logout().catch(() => {});
  }, []);

  return (
    <AuthContext.Provider
      value={{ user, isAdmin: user?.role === 'ADMIN', isRestoring, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}