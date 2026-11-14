import {type FormEvent, useEffect, useState} from 'react';
import {api, ApiError} from '../api';
import {Button, Field} from '../components/ui';
import {navigate, useRoute} from '../router';
import {type LoginError, LoginErrorBox, LoginShell} from './Login';
import type {PasswordResetInfo} from '../types';

function describe(error: unknown): LoginError {
	return error instanceof ApiError ? {title: 'Reset failed', detail: error.message} : {title: 'The server can not be reached', detail: 'Check your connection, or whether the server is running.'};
}

/**
 * Sets a new password with the token of a reset mail, opened from the link of the mail or by entering the reset code.
 */
export function PasswordReset() {
	const {params} = useRoute();
	const [token, setToken] = useState(params.get('token') ?? '');
	const [info, setInfo] = useState<PasswordResetInfo | null>(null);
	const [password, setPassword] = useState('');
	const [confirm, setConfirm] = useState('');
	const [code, setCode] = useState('');
	const [busy, setBusy] = useState(false);
	const [done, setDone] = useState(false);
	const [error, setError] = useState<LoginError>();
	
	const check = async (value: string) => {
		setBusy(true);
		setError(undefined);
		try {
			setInfo(await api.checkPasswordReset(value.trim()));
		} catch (e) {
			setError(describe(e));
		}
		setBusy(false);
	};
	
	useEffect(() => {
		const initial = params.get('token');
		if (initial) {
			// The token is only needed in memory, it does not stay in the address bar or the history
			window.history.replaceState(null, '', `${window.location.pathname}#/reset-password`);
			void check(initial);
		}
	}, []);
	
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		if (!info) {
			await check(token);
			return;
		}
		if (password !== confirm) {
			setError({title: 'The passwords do not match', detail: 'Enter the same new password twice.'});
			return;
		}
		setBusy(true);
		setError(undefined);
		try {
			await api.completePasswordReset(token.trim(), password, info.totpRequired ? code.trim() : undefined);
			setDone(true);
		} catch (e) {
			setError(describe(e));
		}
		setBusy(false);
	};
	
	return (
		<LoginShell>
			<form className="login-card" onSubmit={submit} noValidate>
				<div>
					<h1>Choose a new password</h1>
					<p className="muted">{done ? 'Your password was changed and all sessions were signed out. Sign in with the new password.'
						: info ? `For the account ${info.username}.` : 'Enter the reset code from the mail.'}</p>
				</div>
				{!done && !info && (
					<Field label="Reset code">
						<input value={token} onChange={event => setToken(event.target.value)} autoFocus autoComplete="off" className="mono"/>
					</Field>
				)}
				{!done && info && (
					<>
						<Field label="New password" hint="At least 12 characters">
							<input type="password" value={password} onChange={event => setPassword(event.target.value)} autoFocus autoComplete="new-password" minLength={12}/>
						</Field>
						<Field label="Confirm password">
							<input type="password" value={confirm} onChange={event => setConfirm(event.target.value)} autoComplete="new-password"/>
						</Field>
						{info.totpRequired && (
							<Field label="Authenticator code or recovery code" hint="Your account uses an authenticator app, the mail alone is not enough">
								<input value={code} onChange={event => setCode(event.target.value)} autoComplete="one-time-code" className="mono" placeholder="123456 or ABCD-EFGH" maxLength={9}/>
							</Field>
						)}
					</>
				)}
				<LoginErrorBox error={error}/>
				{!done && (
					<Button variant="primary" type="submit" className="login-submit"
							disabled={busy || (info ? password.length < 12 || (info.totpRequired && code.trim() === '') : token.trim() === '')}>
						{busy ? 'Checking…' : info ? 'Set password' : 'Continue'}
					</Button>
				)}
				<button type="button" className={done ? 'btn btn-primary login-submit' : 'link small login-forgot'} onClick={() => navigate('#/')}>
					{done ? 'Sign in' : 'Back to sign in'}
				</button>
			</form>
		</LoginShell>
	);
}
