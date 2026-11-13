import {type FormEvent, useEffect, useState} from 'react';
import {api, ApiError} from '../api';
import {Icon} from '../components/icons';
import {ThemeToggle} from '../components/Layout';
import {Button, Field, Tabs, TYPE_INFO} from '../components/ui';
import {useSession} from '../session';
import {REPOSITORY_TYPES} from '../types';

type Method = 'password' | 'token';

/** Access tokens are issued with this prefix, a pasted token is recognized even in the password tab. */
const TOKEN_PREFIX = 'lat_';

interface LoginError {
	title: string;
	detail: string;
}

/**
 * Turns a failed sign in into a message that says what went wrong and what to do about it.
 */
function describeError(error: unknown, withToken: boolean): LoginError {
	if (error instanceof ApiError) {
		if (error.status === 401) {
			return withToken
				? {title: 'The token was not accepted', detail: 'It may be revoked or expired. Create a new one or sign in with your password.'}
				: {title: 'Wrong username or password', detail: 'Check both and try again.'};
		}
		if (error.status === 403) {
			return {title: 'Access denied', detail: error.message || 'The token is not allowed from this address.'};
		}
		if (error.status === 429) {
			return {title: 'Too many attempts', detail: 'Sign in is paused for this address after several failed attempts. Wait a few minutes and try again.'};
		}
		if (error.status >= 500) {
			return {title: 'The server ran into a problem', detail: `${error.status}: ${error.message}. Try again in a moment or check the server logs.`};
		}
		return {title: 'Sign in failed', detail: error.message};
	}
	return {title: 'The server can not be reached', detail: 'Check your connection, or whether the server is running.'};
}

export function Login() {
	const {login, continueAsGuest} = useSession();
	const [publicCount, setPublicCount] = useState(0);
	const [method, setMethod] = useState<Method>('password');
	const [username, setUsername] = useState('');
	const [secret, setSecret] = useState('');
	const [remember, setRemember] = useState(false);
	const [showSecret, setShowSecret] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<LoginError>();
	// A token pasted into the password field is used as a token, a password login with it would always be refused
	const detectedToken = method === 'password' && secret.trim().startsWith(TOKEN_PREFIX);
	const withToken = method === 'token' || detectedToken;
	const canSubmit = withToken ? secret.trim() !== '' : username.trim() !== '' && secret !== '';
	
	const changeMethod = (value: Method) => {
		setMethod(value);
		setSecret('');
		setShowSecret(false);
		setError(undefined);
	};
	
	useEffect(() => {
		// Without credentials the server lists only the public repositories, guest access is offered when there are any
		api.repositories().then(repositories => setPublicCount(repositories.length), () => setPublicCount(0));
	}, []);

	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await login(withToken ? '' : username.trim(), withToken ? secret.trim() : secret, remember);
		} catch (e) {
			setError(describeError(e, withToken));
			setBusy(false);
		}
	};

	return (
		<div className="login">
			<div className="login-theme"><ThemeToggle/></div>
			<div className="login-shell">
				<section className="login-brand" aria-hidden>
					<div className="brand brand-large">
						<img src="./favicon.svg" alt="" width={40} height={40}/>
						<span>LArtifactory</span>
					</div>
					<p className="login-tagline">One place for your packages, images and build artifacts.</p>
					<ul className="login-formats">
						{REPOSITORY_TYPES.map(type => (
							<li key={type} style={{'--badge': TYPE_INFO[type].color} as React.CSSProperties}>
								<span className="dot" style={{background: TYPE_INFO[type].color}}/>
								<span>{TYPE_INFO[type].label}</span>
								<span className="login-format-hint">{TYPE_INFO[type].ecosystem}</span>
							</li>
						))}
					</ul>
				</section>
				<form className="login-card" onSubmit={submit} noValidate>
					<div>
						<h1>Sign in</h1>
						<p className="muted">{method === 'password' ? 'Enter your username and password to start a session.' : 'Enter one of your access tokens.'}</p>
					</div>
					<Tabs<Method>
						tabs={[{id: 'password', label: 'Password'}, {id: 'token', label: 'Access token'}]}
						active={method}
						onChange={changeMethod}
					/>
					{method === 'password' && (
						<Field label="Username">
							<input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" autoFocus disabled={detectedToken}/>
						</Field>
					)}
					<Field label={method === 'password' ? 'Password' : 'Access token'}
						   hint={detectedToken ? 'This is an access token, it is used as one and the username is not needed' : method === 'token' ? `Starts with ${TOKEN_PREFIX}, the token identifies its owner` : undefined}>
						<span className="password-field">
							<input type={showSecret ? 'text' : 'password'} value={secret} onChange={event => setSecret(event.target.value)}
								   autoComplete={method === 'token' ? 'off' : 'current-password'} autoFocus={method === 'token'} aria-invalid={error ? true : undefined}/>
							<button type="button" className="password-toggle" onClick={() => setShowSecret(value => !value)} aria-label={showSecret ? 'Hide' : 'Show'} title={showSecret ? 'Hide' : 'Show'}>
								<Icon name={showSecret ? 'eye-off' : 'eye'} size={16}/>
							</button>
						</span>
					</Field>
					<label className="checkbox">
						<input type="checkbox" checked={remember} onChange={event => setRemember(event.target.checked)}/>
						Keep me signed in on this device
					</label>
					{error && (
						<div className="login-error" role="alert">
							<strong>{error.title}</strong>
							<span>{error.detail}</span>
						</div>
					)}
					<Button variant="primary" type="submit" className="login-submit" disabled={busy || !canSubmit}>{busy ? 'Signing in…' : withToken ? 'Sign in with token' : 'Sign in'}</Button>
					{publicCount > 0 && (
						<>
							<div className="login-divider"><span>or</span></div>
							<Button variant="ghost" className="login-submit" onClick={continueAsGuest} disabled={busy}>
								Continue without signing in
							</Button>
							<p className="muted small login-guest-hint">
								Browse and download from {publicCount === 1 ? 'the public repository' : `${publicCount} public repositories`}.
							</p>
						</>
					)}
				</form>
			</div>
		</div>
	);
}
