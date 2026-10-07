import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, basicAuthorization, hasAuthorization, onUnauthorized, setAuthorization } from './api';
import type { Principal } from './types';

interface Session {
	principal: Principal | null;
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

export function SessionProvider({ children }: { children: (state: { ready: boolean; principal: Principal | null }) => ReactNode }) {
	const [principal, setPrincipal] = useState<Principal | null>(null);
	const [ready, setReady] = useState(!hasAuthorization());
	const [toasts, setToasts] = useState<Toast[]>([]);

	const notify = useCallback((message: string, kind: ToastKind = 'success') => {
		const id = nextToastId++;
		setToasts(current => [...current, { id, message, kind }]);
		setTimeout(() => setToasts(current => current.filter(toast => toast.id !== id)), kind === 'error' ? 7000 : 4000);
	}, []);

	const logout = useCallback(() => {
		setAuthorization(null);
		setPrincipal(null);
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
		setAuthorization(basicAuthorization(username || '__token__', secret), remember);
		try {
			setPrincipal(await api.me());
		} catch (error) {
			setAuthorization(null);
			throw error;
		}
	}, []);

	return (
		<SessionContext.Provider value={{ principal, login, logout, notify }}>
			{children({ ready, principal })}
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
