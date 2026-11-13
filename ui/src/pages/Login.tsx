import {type FormEvent, useState} from 'react';
import {ApiError} from '../api';
import {Icon} from '../components/icons';
import {ThemeToggle} from '../components/Layout';
import {Button, Field, TYPE_INFO} from '../components/ui';
import {useSession} from '../session';
import {REPOSITORY_TYPES} from '../types';

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
				? {title: 'The token was not accepted', detail: 'It may be revoked or expired. To sign in with a password, enter your username as well.'}
				: {title: 'Wrong username or password', detail: 'Check both and try again. To sign in with an access token, leave the username empty.'};
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
	const {login} = useSession();
	const [username, setUsername] = useState('');
	const [secret, setSecret] = useState('');
	const [remember, setRemember] = useState(false);
	const [showSecret, setShowSecret] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<LoginError>();
	const withToken = username.trim() === '';

	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await login(username.trim(), secret, remember);
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
						<p className="muted">{withToken ? 'Enter an access token, or add your username to use a password.' : 'Enter your password to start a session.'}</p>
					</div>
					<Field label="Username" hint="Leave empty to sign in with an access token">
						<input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" autoFocus/>
					</Field>
					<Field label={withToken ? 'Access token' : 'Password'}>
						<span className="password-field">
							<input type={showSecret ? 'text' : 'password'} value={secret} onChange={event => setSecret(event.target.value)}
								   autoComplete={withToken ? 'off' : 'current-password'} aria-invalid={error ? true : undefined}/>
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
					<Button variant="primary" type="submit" className="login-submit" disabled={busy || !secret}>{busy ? 'Signing in…' : withToken ? 'Sign in with token' : 'Sign in'}</Button>
				</form>
			</div>
		</div>
	);
}
