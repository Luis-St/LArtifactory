import {type FormEvent, useEffect, useMemo, useState} from 'react';
import {api} from '../api';
import {Icon} from '../components/icons';
import {Badge, Button, CodeBlock, ConfirmDialog, Empty, ErrorBox, errorText, Field, Modal, PageHeader, Spinner} from '../components/ui';
import {levelTone} from './Users';
import {formatDate, formatRelative} from '../format';
import {useAsync} from '../hooks';
import {useSession} from '../session';
import {ACCESS_LEVELS, type AccessLevel, type Token} from '../types';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';
import {href, navigate} from '../router';

export function Tokens({params}: { params: URLSearchParams }) {
	const {principal, notify} = useSession();
	const [owner, setOwner] = useState(principal?.username ?? '');
	const users = useAsync(() => (principal?.admin ? api.users() : Promise.resolve([])), [principal?.admin]);
	// Tokens can only be managed after signing in with a password, a token can not create or list tokens
	const signedInWithToken = principal?.tokenLevel != null;
	const tokens = useAsync(() => (signedInWithToken ? Promise.resolve([]) : api.tokens(owner || undefined)), [owner, signedInWithToken]);
	const [creating, setCreating] = useState(false);
	const [created, setCreated] = useState<Token | null>(null);
	const [revoking, setRevoking] = useState<Token | null>(null);
	
	// ?new=1 opens the create dialog right away, e.g. from the account page
	const openNew = params.get('new') === '1';
	useEffect(() => {
		if (openNew && !signedInWithToken) {
			setCreating(true);
			navigate(href('tokens'));
		}
	}, [openNew, signedInWithToken]);
	
	const now = new Date().toISOString();
	const rows = useMemo(() => [...(tokens.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt)), [tokens.data]);
	const {sorted, sort, toggle} = useSort<Token, 'name' | 'level' | 'restrictions' | 'used' | 'created' | 'expires'>(rows, {
		name: token => token.name,
		level: token => ACCESS_LEVELS.indexOf(token.level),
		restrictions: token => (token.allowedIps ? 1 : 0) + (token.rateLimit > 0 ? 1 : 0),
		used: token => token.lastUsedAt,
		created: token => token.createdAt,
		expires: token => token.expiresAt,
	});
	if (signedInWithToken) {
		return (
			<>
				<PageHeader title="Access tokens" subtitle="Tokens authenticate package managers and CI. They can be restricted to a lower access level and expire."/>
				<Empty title="Password required">You signed in with an access token. Sign in with your password to create, list or revoke tokens.</Empty>
			</>
		);
	}
	return (
		<>
			<PageHeader
				title="Access tokens"
				subtitle="Tokens authenticate package managers and CI. They can be restricted to a lower access level and expire."
				actions={<Button variant="primary" onClick={() => setCreating(true)}><Icon name="plus" size={16}/>New token</Button>}
			/>
			{principal?.admin && (
				<div className="toolbar">
					<label className="cell-inline">
						<span className="muted">Showing tokens of</span>
						<Select label="Token owner" value={owner} onChange={setOwner} options={(users.data ?? [{username: principal.username}]).map(user => ({value: user.username, label: user.username}))}/>
					</label>
				</div>
			)}
			{tokens.loading && !tokens.data && <Spinner/>}
			{tokens.error && <ErrorBox error={tokens.error} onRetry={tokens.reload}/>}
			{tokens.data && (tokens.data.length === 0 ? <Empty title="No tokens">Create a token to use with your package manager.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="name" sort={sort} onSort={toggle}>Name</SortHeader>
							<SortHeader id="level" sort={sort} onSort={toggle}>Level</SortHeader>
							<SortHeader id="restrictions" sort={sort} onSort={toggle}>Restrictions</SortHeader>
							<SortHeader id="used" sort={sort} onSort={toggle}>Last used</SortHeader>
							<SortHeader id="created" sort={sort} onSort={toggle}>Created</SortHeader>
							<SortHeader id="expires" sort={sort} onSort={toggle}>Expires</SortHeader>
							<th/>
						</tr>
						</thead>
						<tbody>
						{sorted.map(token => {
							const expired = token.expiresAt !== null && token.expiresAt < now;
							return (
								<tr key={token.id}>
									<td><span className="cell-inline"><strong>{token.name}</strong><code className="id-chip" title={token.id}>{token.id.substring(0, 8)}</code></span></td>
									<td><Badge tone={levelTone(token.level)}>{token.level}</Badge></td>
									<td>
										{token.allowedIps && <div title={token.allowedIps}>IP allowlist</div>}
										{token.rateLimit > 0 && <div className="muted">{token.rateLimit}/min</div>}
										{!token.allowedIps && token.rateLimit === 0 && <span className="muted">None</span>}
									</td>
									<td className="muted nowrap">
										{token.lastUsedAt
											? <span title={`${formatDate(token.lastUsedAt)}${token.lastUsedIp ? ` from ${token.lastUsedIp}` : ''}`}>{formatRelative(token.lastUsedAt)}</span>
											: <span className="muted">Never</span>}
									</td>
									<td className="muted nowrap" title={formatDate(token.createdAt)}>{formatRelative(token.createdAt)}</td>
									<td className="nowrap">{token.expiresAt ? (expired ? <Badge tone="danger">expired</Badge> : <span className="muted" title={formatDate(token.expiresAt)}>{formatRelative(token.expiresAt)}</span>) :
										<span className="muted">Never</span>}</td>
									<td className="actions"><Button small variant="ghost" className="danger-text" onClick={() => setRevoking(token)}>Revoke</Button></td>
								</tr>
							);
						})}
						</tbody>
					</table>
				</div>
			))}
			
			{creating && (
				<CreateTokenDialog
					owner={owner || principal?.username || ''}
					maxLevel="DELETE"
					onClose={() => setCreating(false)}
					onCreated={token => {
						setCreating(false);
						setCreated(token);
						tokens.reload();
					}}
				/>
			)}
			{created && (
				<Modal title="Token created" onClose={() => setCreated(null)} footer={<Button variant="primary" onClick={() => setCreated(null)}>Done</Button>}>
					<p>Copy the token now, it will not be shown again.</p>
					<CodeBlock title={`${created.name} · ${created.level}`} code={created.token ?? ''}/>
				</Modal>
			)}
			{revoking && (
				<ConfirmDialog
					title="Revoke token"
					confirmLabel="Revoke"
					message={<>Revoke <strong>{revoking.name}</strong>? Clients using it lose access immediately.</>}
					onConfirm={async () => {
						await api.deleteToken(revoking.id);
						notify(`Token ${revoking.name} revoked`);
						tokens.reload();
					}}
					onClose={() => setRevoking(null)}
				/>
			)}
		</>
	);
}

function CreateTokenDialog({owner, maxLevel, onClose, onCreated}: { owner: string; maxLevel: AccessLevel; onClose: () => void; onCreated: (token: Token) => void }) {
	const [name, setName] = useState('');
	const levels = ACCESS_LEVELS.slice(0, ACCESS_LEVELS.indexOf(maxLevel) + 1);
	const [level, setLevel] = useState<AccessLevel>(levels.includes('WRITE') ? 'WRITE' : 'READ');
	const [expires, setExpires] = useState('90');
	const [allowedIps, setAllowedIps] = useState('');
	const [rateLimit, setRateLimit] = useState('');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			onCreated(await api.createToken({
				name: name.trim(),
				username: owner,
				level,
				expiresInDays: expires ? Number(expires) : undefined,
				allowedIps: allowedIps.trim() || undefined,
				rateLimitPerMinute: rateLimit.trim() ? Number(rateLimit) : undefined,
			}));
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};
	
	return (
		<Modal title={`New token for ${owner}`} onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<Field label="Name" hint="Where the token is used, e.g. github actions">
					<input value={name} onChange={event => setName(event.target.value)} required autoFocus maxLength={128}/>
				</Field>
				<Field label="Access level" hint="Limited to the permissions of the user">
					<Select<AccessLevel> label="Access level" value={level} onChange={setLevel} options={levels.map(entry => ({value: entry, label: entry}))}/>
				</Field>
				<Field label="Expiration">
					<Select label="Expiration" value={expires} onChange={setExpires} options={[
						{value: '7', label: '7 days'},
						{value: '30', label: '30 days'},
						{value: '90', label: '90 days'},
						{value: '365', label: '1 year'},
						{value: '', label: 'Never'},
					]}/>
				</Field>
				<Field label="Allowed IPs" hint="Optional comma/space separated IPs or CIDR ranges, e.g. 10.0.0.0/8 203.0.113.5">
					<input value={allowedIps} onChange={event => setAllowedIps(event.target.value)} placeholder="Any address" maxLength={2048}/>
				</Field>
				<Field label="Rate limit" hint="Optional maximum requests per minute, empty or 0 means unlimited">
					<input type="number" min={0} value={rateLimit} onChange={event => setRateLimit(event.target.value)} placeholder="Unlimited"/>
				</Field>
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || !name.trim()}>{busy ? 'Creating…' : 'Create token'}</Button>
				</div>
			</form>
		</Modal>
	);
}
