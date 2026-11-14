import {createContext, type ReactNode, useCallback, useContext, useEffect, useState} from 'react';
import {api, ApiError, hasAuthorization, hasSession, onUnauthorized, setAuthorization} from './api';
import type {Principal} from './types';

interface Session {
	principal: Principal | null;
	/** Browsing without credentials, only public repositories are visible. */
	guest: boolean;
	continueAsGuest: () => void;
	login: (username: string, secret: string, remember: boolean) => Promise<void>;
	logout: () => void;
	notify: (message: string, kind?: ToastKind) => void;
}

type ToastKind = 'success' | 'error' | 'info';

interface Toast {
	id: number;
	message: string;
	kind: ToastKind;
}

const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
	const session = useContext(SessionContext);
	if (!session) {
		throw new Error('useSession must be used inside SessionProvider');
	}
	return session;
}

let nextToastId = 1;

const GUEST_KEY = 'lartifactory.guest';

function readGuest(): boolean {
	try {
		return sessionStorage.getItem(GUEST_KEY) === 'true';
	} catch {
		return false;
	}
}

function storeGuest(value: boolean): void {
	try {
		if (value) {
			sessionStorage.setItem(GUEST_KEY, 'true');
		} else {
			sessionStorage.removeItem(GUEST_KEY);
		}
	} catch {
		// Storage unavailable, guest mode only lasts for this page
	}
}

export function SessionProvider({children}: { children: (state: { ready: boolean; principal: Principal | null; guest: boolean; unreachable: Error | null; retry: () => void }) => ReactNode }) {
	const [principal, setPrincipal] = useState<Principal | null>(null);
	const [guest, setGuest] = useState(() => !hasAuthorization() && readGuest());
	const [ready, setReady] = useState(!hasAuthorization());
	// Set while the stored session could not be checked because the server is unreachable or failing, the session is kept for a retry
	const [unreachable, setUnreachable] = useState<Error | null>(null);
	const [attempt, setAttempt] = useState(0);
	const retry = useCallback(() => setAttempt(value => value + 1), []);
	const [toasts, setToasts] = useState<Toast[]>([]);
	
	const notify = useCallback((message: string, kind: ToastKind = 'success') => {
		const id = nextToastId++;
		setToasts(current => [...current, {id, message, kind}]);
		setTimeout(() => setToasts(current => current.filter(toast => toast.id !== id)), kind === 'error' ? 7000 : 4000);
	}, []);
	
	const logout = useCallback(() => {
		if (hasSession()) {
			// Revoke the session on the server, signing out locally does not wait for it
			api.deleteSession().catch(() => undefined);
		}
		setAuthorization(null);
		setPrincipal(null);
		storeGuest(false);
		setGuest(false);
	}, []);
	
	const continueAsGuest = useCallback(() => {
		setAuthorization(null);
		storeGuest(true);
		setGuest(true);
	}, []);
	
	useEffect(() => {
		onUnauthorized(() => {
			logout();
			notify('Your session has expired, please sign in again', 'info');
		});
	}, [logout, notify]);
	
	useEffect(() => {
		if (!hasAuthorization()) {
			return;
		}
		setReady(false);
		api.me().then(
			value => {
				setPrincipal(value);
				setUnreachable(null);
				setReady(true);
			},
			error => {
				// Only a rejected session signs out, a restarting or unreachable server keeps it so the next attempt can use it
				if (error instanceof ApiError && error.status === 401) {
					setAuthorization(null);
					setUnreachable(null);
					setReady(true);
				} else {
					setUnreachable(error instanceof Error ? error : new Error(String(error)));
				}
			},
		);
	}, [attempt]);
	
	const login = useCallback(async (username: string, secret: string, remember: boolean) => {
		// The password or token is only sent once to create a session, it is never stored in the browser.
		// A session created from a token has the level of the token and ends when the token is revoked.
		const session = await api.createSession(username || '__token__', secret, remember);
		setAuthorization(`Bearer ${session.token}`, remember);
		try {
			setPrincipal(await api.me());
			storeGuest(false);
			setGuest(false);
		} catch (error) {
			setAuthorization(null);
			throw error;
		}
	}, []);
	
	return (
		<SessionContext.Provider value={{principal, guest, continueAsGuest, login, logout, notify}}>
			{children({ready, principal, guest, unreachable, retry})}
			<div className="toasts" role="status" aria-live="polite">
				{toasts.map(toast => (
					<div key={toast.id} className={`toast toast-${toast.kind}`} onClick={() => setToasts(current => current.filter(entry => entry.id !== toast.id))}>
						{toast.message}
					</div>
				))}
			</div>
		</SessionContext.Provider>
	);
}
