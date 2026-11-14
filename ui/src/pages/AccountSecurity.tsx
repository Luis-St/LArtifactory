import {type FormEvent, useState} from 'react';
import {api} from '../api';
import {QrCode} from '../components/QrCode';
import {Badge, Button, Card, CopyButton, ErrorBox, errorText, Field, Modal, Spinner} from '../components/ui';
import {useAsync} from '../hooks';
import {useSession} from '../session';
import {PasswordDialog} from './Users';

type Dialog =
	| { kind: 'password' }
	| { kind: 'email' }
	| { kind: 'totp' }
	| { kind: 'confirm'; title: string; message: string; label: string; danger?: boolean; action: (password: string) => Promise<string[] | null | void> }
	| { kind: 'codes'; codes: string[] };

/**
 * The security settings of the own account: password, email address, two-factor authentication and recovery codes.
 */
export function SecurityCard({username, signedInWithToken}: { username: string; signedInWithToken: boolean }) {
	const {notify} = useSession();
	const security = useAsync(() => (signedInWithToken ? Promise.resolve(null) : api.accountSecurity()), [signedInWithToken]);
	const [dialog, setDialog] = useState<Dialog | null>(null);
	const data = security.data;
	
	// Enabling the first factor returns the recovery codes, they are shown once
	const finish = (codes: string[] | null | void, message: string) => {
		security.reload();
		notify(message);
		setDialog(codes ? {kind: 'codes', codes} : null);
	};
	
	const confirm = (title: string, message: string, label: string, action: (password: string) => Promise<string[] | null | void>, done: string, danger = false) =>
		setDialog({kind: 'confirm', title, message, label, danger, action: async password => finish(await action(password), done)});
	
	return (
		<Card title="Security">
			<div className="setting-row">
				<div>
					<span>Password</span>
					<span className="muted small">{signedInWithToken ? 'Sign in with your password to change it.' : 'Change the password you use to sign in to the web ui.'}</span>
				</div>
				<Button onClick={() => setDialog({kind: 'password'})} disabled={signedInWithToken}>Change password</Button>
			</div>
			{signedInWithToken ? null : security.loading && !data ? <Spinner rows={2}/> : security.error ? <ErrorBox error={security.error} onRetry={security.reload}/> : data && (
				<>
					<div className="setting-row">
						<div>
							<span>Email address {data.email && <Badge tone="success">Verified</Badge>}</span>
							<span className="muted small">
								{!data.mailAvailable ? 'This server can not send mails, ask an administrator to configure smtp.'
									: data.email ?? 'Add an address to reset a forgotten password and to receive sign in codes.'}
							</span>
						</div>
						<span className="cell-inline">
							{data.email && !data.emailMfaEnabled && (
								<Button variant="ghost" onClick={() => confirm('Remove email address', `Remove ${data.email}? The password can then no longer be reset by email.`, 'Remove',
									password => api.removeEmail(password).then(() => undefined), 'Email address removed', true)}>Remove</Button>
							)}
							<Button onClick={() => setDialog({kind: 'email'})} disabled={!data.mailAvailable}>{data.email ? 'Change' : 'Add email'}</Button>
						</span>
					</div>
					<div className="setting-row">
						<div>
							<span>Authenticator app {data.totpEnabled && <Badge tone="success">On</Badge>}</span>
							<span className="muted small">A 6 digit code of an app like Aegis, Google Authenticator or 1Password is asked for after the password.</span>
						</div>
						{data.totpEnabled
							? <Button onClick={() => confirm('Turn off the authenticator app', 'Sign in no longer asks for a code of the app.', 'Turn off',
								password => api.disableMfa('totp', password).then(() => undefined), 'Authenticator app turned off', true)}>Turn off</Button>
							: <Button onClick={() => setDialog({kind: 'totp'})}>Set up</Button>}
					</div>
					<div className="setting-row">
						<div>
							<span>Codes by email {data.emailMfaEnabled && <Badge tone="success">On</Badge>}</span>
							<span className="muted small">{data.email ? `A 6 digit code is sent to ${data.email} after the password.` : 'Needs a verified email address.'}</span>
						</div>
						{data.emailMfaEnabled
							? <Button onClick={() => confirm('Turn off codes by email', 'Sign in no longer accepts a code sent by email.', 'Turn off',
								password => api.disableMfa('email', password).then(() => undefined), 'Codes by email turned off', true)}>Turn off</Button>
							: <Button disabled={!data.email || !data.mailAvailable} onClick={() => confirm('Turn on codes by email', `Sign in will ask for a code sent to ${data.email}.`, 'Turn on',
								password => api.enableEmailMfa(password).then(result => result.recoveryCodes), 'Codes by email turned on')}>Turn on</Button>}
					</div>
					{(data.totpEnabled || data.emailMfaEnabled) && (
						<div className="setting-row">
							<div>
								<span>Recovery codes</span>
								<span className="muted small">
									{data.recoveryCodesLeft} of 8 left. Each code signs in once when no other second factor is at hand.
									{data.recoveryCodesLeft <= 2 && ' Generate new ones soon.'}
								</span>
							</div>
							<Button onClick={() => confirm('Generate new recovery codes', 'All previous recovery codes stop working.', 'Generate',
								password => api.regenerateRecoveryCodes(password).then(result => result.recoveryCodes), 'New recovery codes generated')}>Generate new</Button>
						</div>
					)}
				</>
			)}
			{dialog?.kind === 'password' && <PasswordDialog username={username} onClose={() => setDialog(null)}/>}
			{dialog?.kind === 'email' && <EmailDialog onClose={() => setDialog(null)} onDone={() => finish(null, 'Email address verified')}/>}
			{dialog?.kind === 'totp' && <TotpDialog onClose={() => setDialog(null)} onDone={codes => finish(codes, 'Authenticator app turned on')}/>}
			{dialog?.kind === 'confirm' && <ConfirmPasswordDialog {...dialog} onClose={() => setDialog(null)}/>}
			{dialog?.kind === 'codes' && <RecoveryCodesDialog codes={dialog.codes} username={username} onClose={() => setDialog(null)}/>}
		</Card>
	);
}

function PasswordInput({value, onChange, autoFocus}: { value: string; onChange: (value: string) => void; autoFocus?: boolean }) {
	return (
		<Field label="Current password">
			<input type="password" value={value} onChange={event => onChange(event.target.value)} required autoFocus={autoFocus} autoComplete="current-password"/>
		</Field>
	);
}

function ConfirmPasswordDialog({title, message, label, danger, action, onClose}: { title: string; message: string; label: string; danger?: boolean; action: (password: string) => Promise<unknown>; onClose: () => void }) {
	const [password, setPassword] = useState('');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await action(password);
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};
	return (
		<Modal title={title} onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<p className="muted">{message}</p>
				<PasswordInput value={password} onChange={setPassword} autoFocus/>
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant={danger ? 'danger' : 'primary'} type="submit" disabled={busy || password === ''}>{busy ? 'Working…' : label}</Button>
				</div>
			</form>
		</Modal>
	);
}

function EmailDialog({onClose, onDone}: { onClose: () => void; onDone: () => void }) {
	const [email, setEmail] = useState('');
	const [password, setPassword] = useState('');
	const [code, setCode] = useState('');
	const [sent, setSent] = useState(false);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			if (sent) {
				await api.verifyEmail(code.trim());
				onDone();
				return;
			}
			await api.requestEmailChange(email.trim(), password);
			setSent(true);
		} catch (e) {
			setError(errorText(e));
		}
		setBusy(false);
	};
	return (
		<Modal title="Email address" onClose={onClose}>
			<form className="form" onSubmit={submit}>
				{!sent ? (
					<>
						<Field label="Email address" hint="A verification code is sent to it">
							<input type="email" value={email} onChange={event => setEmail(event.target.value)} required autoFocus autoComplete="email"/>
						</Field>
						<PasswordInput value={password} onChange={setPassword}/>
					</>
				) : (
					<Field label="Verification code" hint={`Sent to ${email.trim()}`}>
						<input value={code} onChange={event => setCode(event.target.value)} required autoFocus inputMode="numeric" maxLength={6} autoComplete="one-time-code" className="mono" placeholder="123456"/>
					</Field>
				)}
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					{sent && <Button variant="ghost" onClick={() => {
						setSent(false);
						setCode('');
					}} disabled={busy}>Back</Button>}
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || (sent ? code.trim().length !== 6 : email.trim() === '' || password === '')}>
						{busy ? 'Working…' : sent ? 'Verify' : 'Send code'}
					</Button>
				</div>
			</form>
		</Modal>
	);
}

function TotpDialog({onClose, onDone}: { onClose: () => void; onDone: (codes: string[] | null) => void }) {
	const [password, setPassword] = useState('');
	const [setup, setSetup] = useState<{ secret: string; uri: string } | null>(null);
	const [code, setCode] = useState('');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			if (setup) {
				onDone((await api.enableTotp(code.trim())).recoveryCodes);
				return;
			}
			setSetup(await api.setupTotp(password));
		} catch (e) {
			setError(errorText(e));
		}
		setBusy(false);
	};
	// The secret in groups of four is easier to type into an app by hand
	const grouped = setup?.secret.match(/.{1,4}/g)?.join(' ') ?? '';
	return (
		<Modal title="Set up an authenticator app" onClose={onClose}>
			<form className="form" onSubmit={submit}>
				{!setup ? (
					<>
						<p className="muted">After the password, sign in will ask for a 6 digit code of the app.</p>
						<PasswordInput value={password} onChange={setPassword} autoFocus/>
					</>
				) : (
					<>
						<p className="muted">Scan the code with your authenticator app, or enter the key by hand.</p>
						<div className="totp-setup">
							<QrCode value={setup.uri} label="QR code for the authenticator app"/>
							<div className="totp-secret">
								<span className="muted small">Key</span>
								<code>{grouped}</code>
								<CopyButton value={setup.secret} label="Copy key"/>
							</div>
						</div>
						<Field label="Code of the app" hint="Confirms the app is set up correctly">
							<input value={code} onChange={event => setCode(event.target.value)} required autoFocus inputMode="numeric" maxLength={6} autoComplete="one-time-code" className="mono" placeholder="123456"/>
						</Field>
					</>
				)}
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || (setup ? code.trim().length !== 6 : password === '')}>{busy ? 'Working…' : setup ? 'Turn on' : 'Continue'}</Button>
				</div>
			</form>
		</Modal>
	);
}

function RecoveryCodesDialog({codes, username, onClose}: { codes: string[]; username: string; onClose: () => void }) {
	const text = codes.join('\n');
	const download = () => {
		const url = URL.createObjectURL(new Blob([`LArtifactory recovery codes for ${username}\nEach code can be used once.\n\n${text}\n`], {type: 'text/plain'}));
		const link = document.createElement('a');
		link.href = url;
		link.download = `lartifactory-recovery-codes-${username}.txt`;
		link.click();
		URL.revokeObjectURL(url);
	};
	return (
		<Modal title="Recovery codes" onClose={onClose} footer={<Button variant="primary" onClick={onClose}>I stored them</Button>}>
			<p className="muted">Store these codes somewhere safe, they are only shown now. Each code signs in once if your other second factors are not at hand.</p>
			<ul className="recovery-codes">
				{codes.map(code => <li key={code}><code>{code}</code></li>)}
			</ul>
			<div className="cell-inline">
				<CopyButton value={text} label="Copy codes"/>
				<Button small variant="ghost" onClick={download}>Download</Button>
			</div>
		</Modal>
	);
}
