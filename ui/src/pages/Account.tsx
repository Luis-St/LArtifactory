import {useState} from 'react';
import {Icon} from '../components/icons';
import {Badge, Button, Card, PageHeader, Segmented} from '../components/ui';
import {href} from '../router';
import {useSession} from '../session';
import {type ThemePreference, useTheme} from '../theme';
import {PasswordDialog} from './Users';

export function Account() {
	const {principal, logout} = useSession();
	const {preference, setPreference} = useTheme();
	const [changing, setChanging] = useState(false);
	if (!principal) {
		return null;
	}
	return (
		<>
			<PageHeader title="Account" subtitle="Your profile and preferences on this instance."/>
			<div className="two-columns">
				<Card title="Profile" actions={<Button small onClick={logout}><Icon name="logout" size={15}/>Sign out</Button>}>
					<div className="setting-row">
						<span className="cell-inline">
							<span className="avatar" aria-hidden>{principal.username.substring(0, 1).toUpperCase()}</span>
							<span>
								<strong>{principal.username}</strong>
								<div className="muted small">Signed in with {principal.tokenLevel ? `an access token (${principal.tokenLevel})` : 'a password'}</div>
							</span>
						</span>
						{principal.admin ? <Badge tone="accent">Administrator</Badge> : <Badge>User</Badge>}
					</div>
				</Card>
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
							<span className="muted small">{principal.tokenLevel ? 'Sign in with your password to change it.' : 'Change the password you use to sign in.'}</span>
						</div>
						<Button onClick={() => setChanging(true)} disabled={principal.tokenLevel !== null}>Change password</Button>
					</div>
					<div className="setting-row">
						<div>
							<span>Access tokens</span>
							<span className="muted small">Tokens for your package managers and CI pipelines.</span>
						</div>
						<a className="btn btn-secondary" href={href('tokens')}>Manage tokens</a>
					</div>
				</Card>
			</div>
			{changing && <PasswordDialog username={principal.username} onClose={() => setChanging(false)}/>}
		</>
	);
}
