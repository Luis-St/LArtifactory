import {useState} from 'react';
import {api} from '../api';
import {Icon} from '../components/icons';
import {Avatar, Badge, Button, Card, Empty, PageHeader, Segmented, Spinner} from '../components/ui';
import {formatDate, formatRelative} from '../format';
import {useAsync} from '../hooks';
import {href} from '../router';
import {useSession} from '../session';
import {type ThemePreference, useTheme} from '../theme';
import {levelTone, PasswordDialog} from './Users';

export function Account() {
	const {principal, logout} = useSession();
	const {preference, setPreference} = useTheme();
	const [changing, setChanging] = useState(false);
	const signedInWithToken = principal?.tokenLevel != null;
	// A token can not list tokens, the list is only loaded for password sessions
	const tokens = useAsync(() => (principal && !signedInWithToken ? api.tokens(principal.username) : Promise.resolve(null)), [principal?.username, signedInWithToken]);
	const repositories = useAsync(() => api.repositories(), []);
	if (!principal) {
		return null;
	}
	const now = new Date().toISOString();
	const activeTokens = (tokens.data ?? []).filter(token => !token.expiresAt || token.expiresAt > now);
	const recentTokens = [...activeTokens].sort((a, b) => (b.lastUsedAt ?? b.createdAt).localeCompare(a.lastUsedAt ?? a.createdAt)).slice(0, 4);

	return (
		<>
			<PageHeader title="Account" subtitle="Your profile and preferences on this instance."/>

			<section className="card account-hero">
				<Avatar name={principal.username} size="large"/>
				<div className="account-identity">
					<div className="cell-inline">
						<h2>{principal.username}</h2>
						{principal.admin ? <Badge tone="accent">Administrator</Badge> : <Badge>User</Badge>}
					</div>
					<span className="muted">Signed in with {signedInWithToken ? `an access token (${principal.tokenLevel})` : 'a password'}</span>
				</div>
				<dl className="account-facts">
					<div>
						<dt>Repositories</dt>
						<dd>{repositories.data ? repositories.data.length : '…'}</dd>
					</div>
					<div>
						<dt>Active tokens</dt>
						<dd>{signedInWithToken ? '—' : tokens.data ? activeTokens.length : '…'}</dd>
					</div>
					<div>
						<dt>Access</dt>
						<dd>{principal.admin ? 'Full' : 'Per repository'}</dd>
					</div>
				</dl>
				<Button onClick={logout}><Icon name="logout" size={15}/>Sign out</Button>
			</section>

			<div className="account-grid">
				<div className="stack">
					<Card title="Appearance">
						<div className="setting-row">
							<div>
								<span>Theme</span>
								<span className="muted small">System follows the setting of your operating system.</span>
							</div>
							<Segmented<ThemePreference>
								label="Theme"
								value={preference}
								onChange={setPreference}
								options={[
									{id: 'light', label: 'Light', icon: 'sun'},
									{id: 'dark', label: 'Dark', icon: 'moon'},
									{id: 'system', label: 'System', icon: 'monitor'},
								]}
							/>
						</div>
					</Card>
					<Card title="Security">
						<div className="setting-row">
							<div>
								<span>Password</span>
								<span className="muted small">{signedInWithToken ? 'Sign in with your password to change it.' : 'Change the password you use to sign in to the web ui.'}</span>
							</div>
							<Button onClick={() => setChanging(true)} disabled={signedInWithToken}>Change password</Button>
						</div>
					</Card>
				</div>

				<Card title="Access tokens">
					{signedInWithToken ? (
						<Empty title="Password required">Sign in with your password to see your tokens.</Empty>
					) : tokens.loading && !tokens.data ? <Spinner/> : recentTokens.length === 0 ? (
						<Empty title="No active tokens">Create a token for your package managers and CI pipelines.</Empty>
					) : (
						<ul className="list token-list">
							{recentTokens.map(token => (
								<li key={token.id} className="list-row">
									<span className="list-main">
										<span className="list-title">{token.name}</span>
										<Badge tone={levelTone(token.level)}>{token.level}</Badge>
									</span>
									<span className="list-meta" title={token.expiresAt ? `Expires ${formatDate(token.expiresAt)}` : 'Never expires'}>
										{token.lastUsedAt ? `Used ${formatRelative(token.lastUsedAt)}` : 'Never used'}
									</span>
								</li>
							))}
						</ul>
					)}
					{!signedInWithToken && (
						<div className="account-token-actions">
							<a className="btn btn-primary btn-small" href={`${href('tokens')}?new=1`}><Icon name="plus" size={15}/>New token</a>
							<a className="btn btn-secondary btn-small" href={href('tokens')}>Manage tokens</a>
						</div>
					)}
				</Card>
			</div>
			{changing && <PasswordDialog username={principal.username} onClose={() => setChanging(false)}/>}
		</>
	);
}
