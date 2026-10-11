import { useState, type FormEvent } from 'react';
import { Button, ErrorBox, Field, errorText } from '../components/ui';
import { useSession } from '../session';

export function Login() {
	const { login } = useSession();
	const [username, setUsername] = useState('');
	const [secret, setSecret] = useState('');
	const [remember, setRemember] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();

	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await login(username.trim(), secret, remember);
		} catch (e) {
			setError(errorText(e).includes('Authentication required') ? 'Invalid username, password or token' : errorText(e));
			setBusy(false);
		}
	};

	return (
		<div className="login">
			<form className="login-card" onSubmit={submit}>
				<div className="brand brand-large">
					<img src="./favicon.svg" alt="" width={40} height={40} />
					<span>LArtifactory</span>
				</div>
				<p className="muted">Sign in with your password or an access token.</p>
				<Field label="Username" hint="Leave empty when signing in with a token">
					<input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" autoFocus />
				</Field>
				<Field label="Password or token">
					<input type="password" value={secret} onChange={event => setSecret(event.target.value)} autoComplete="current-password" required />
				</Field>
				<label className="checkbox">
					<input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)} />
					Keep me signed in on this device
				</label>
				{error && <ErrorBox error={error} />}
				<Button variant="primary" type="submit" disabled={busy || !secret}>{busy ? 'Signing in…' : 'Sign in'}</Button>
			</form>
		</div>
	);
}
