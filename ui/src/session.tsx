import {createContext, type ReactNode, useCallback, useContext, useEffect, useState} from 'react';
import {api, basicAuthorization, hasAuthorization, hasSession, onUnauthorized, setAuthorization} from './api';
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

export function SessionProvider({children}: { children: (state: { ready: boolean; principal: Principal | null; guest: boolean }) => ReactNode }) {
	const [principal, setPrincipal] = useState<Principal | null>(null);
	const [guest, setGuest] = useState(() => !hasAuthorization() && readGuest());
	const [ready, setReady] = useState(!hasAuthorization());
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
		if (hasAuthorization()) {
			api.me().then(setPrincipal, () => setAuthorization(null)).finally(() => setReady(true));
		}
	}, [logout, notify]);
	
	const login = useCallback(async (username: string, secret: string, remember: boolean) => {
		if (username) {
			// The password is only sent once to create a session, it is never stored in the browser
			const session = await api.createSession(username, secret, remember);
			setAuthorization(`Bearer ${session.token}`, remember);
		} else {
			setAuthorization(basicAuthorization('__token__', secret), remember);
		}
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
			{children({ready, principal, guest})}
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
