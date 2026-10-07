import { useState } from 'react';
import { Badge, Button, Card, PageHeader } from '../components/ui';
import { href } from '../router';
import { useSession } from '../session';
import { PasswordDialog } from './Users';

export function Account() {
	const { principal, logout } = useSession();
	const [changing, setChanging] = useState(false);
	if (!principal) {
		return null;
	}
	return (
		<>
			<PageHeader title="Account" subtitle="Your profile on this instance." />
			<div className="stack">
				<Card title="Profile">
					<dl className="details">
						<dt>Username</dt><dd>{principal.username}</dd>
						<dt>Role</dt><dd>{principal.admin ? <Badge tone="accent">Administrator</Badge> : <Badge>User</Badge>}</dd>
						<dt>Signed in with</dt><dd>{principal.tokenLevel ? <>Access token ({principal.tokenLevel})</> : 'Password'}</dd>
					</dl>
				</Card>
				<Card title="Password">
					<div className="danger-row">
						<div className="muted">{principal.tokenLevel ? 'Sign in with your password to change it.' : 'Change the password you use to sign in.'}</div>
						<Button onClick={() => setChanging(true)} disabled={principal.tokenLevel !== null}>Change password</Button>
					</div>
				</Card>
				<Card title="Access tokens">
					<div className="danger-row">
						<div className="muted">Create tokens for your package managers and CI pipelines.</div>
						<a className="btn btn-secondary" href={href('tokens')}>Manage tokens</a>
					</div>
				</Card>
				<div><Button variant="danger" onClick={logout}>Sign out</Button></div>
			</div>
			{changing && <PasswordDialog username={principal.username} onClose={() => setChanging(false)} />}
		</>
	);
}
