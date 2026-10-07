import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { Badge, Button, CodeBlock, ConfirmDialog, Empty, ErrorBox, Field, Modal, PageHeader, Spinner, errorText } from '../components/ui';
import { formatDate, formatRelative } from '../format';
import { useAsync } from '../hooks';
import { useSession } from '../session';
import { ACCESS_LEVELS, type AccessLevel, type Token } from '../types';

export function Tokens() {
	const { principal, notify } = useSession();
	const [owner, setOwner] = useState(principal?.username ?? '');
	const users = useAsync(() => (principal?.admin ? api.users() : Promise.resolve([])), [principal?.admin]);
	const tokens = useAsync(() => api.tokens(owner || undefined), [owner]);
	const [creating, setCreating] = useState(false);
	const [created, setCreated] = useState<Token | null>(null);
	const [revoking, setRevoking] = useState<Token | null>(null);

	const now = new Date().toISOString();
	return (
		<>
			<PageHeader
				title="Access tokens"
				subtitle="Tokens authenticate package managers and CI. They can be restricted to a lower access level and expire."
				actions={<Button variant="primary" onClick={() => setCreating(true)}>New token</Button>}
			/>
			{principal?.admin && (
				<div className="toolbar">
					<label className="cell-inline">
						<span className="muted">Tokens of</span>
						<select value={owner} onChange={event => setOwner(event.target.value)}>
							{(users.data ?? [{ username: principal.username }]).map(user => <option key={user.username} value={user.username}>{user.username}</option>)}
						</select>
					</label>
				</div>
			)}
			{tokens.loading && !tokens.data && <Spinner />}
			{tokens.error && <ErrorBox error={tokens.error} onRetry={tokens.reload} />}
			{tokens.data && (tokens.data.length === 0 ? <Empty title="No tokens">Create a token to use with your package manager.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead><tr><th>Name</th><th>Level</th><th>Created</th><th>Expires</th><th /></tr></thead>
						<tbody>
							{tokens.data.sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(token => {
								const expired = token.expiresAt !== null && token.expiresAt < now;
								return (
									<tr key={token.id}>
										<td><strong>{token.name}</strong> <span className="muted small mono">{token.id.substring(0, 8)}</span></td>
										<td><Badge tone={token.level === 'DELETE' ? 'danger' : token.level === 'WRITE' ? 'warning' : 'neutral'}>{token.level}</Badge></td>
										<td className="muted" title={formatDate(token.createdAt)}>{formatRelative(token.createdAt)}</td>
										<td>{token.expiresAt ? (expired ? <Badge tone="danger">expired</Badge> : <span className="muted" title={formatDate(token.expiresAt)}>{formatRelative(token.expiresAt)}</span>) : <span className="muted">Never</span>}</td>
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
					maxLevel={principal?.tokenLevel ?? 'DELETE'}
					onClose={() => setCreating(false)}
					onCreated={token => { setCreating(false); setCreated(token); tokens.reload(); }}
				/>
			)}
			{created && (
				<Modal title="Token created" onClose={() => setCreated(null)} footer={<Button variant="primary" onClick={() => setCreated(null)}>Done</Button>}>
					<p>Copy the token now, it will not be shown again.</p>
					<CodeBlock title={`${created.name} · ${created.level}`} code={created.token ?? ''} />
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

function CreateTokenDialog({ owner, maxLevel, onClose, onCreated }: { owner: string; maxLevel: AccessLevel; onClose: () => void; onCreated: (token: Token) => void }) {
	const [name, setName] = useState('');
	const levels = ACCESS_LEVELS.slice(0, ACCESS_LEVELS.indexOf(maxLevel) + 1);
	const [level, setLevel] = useState<AccessLevel>(levels.includes('WRITE') ? 'WRITE' : 'READ');
	const [expires, setExpires] = useState('90');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();

	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			onCreated(await api.createToken({ name: name.trim(), username: owner, level, expiresInDays: expires ? Number(expires) : undefined }));
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};

	return (
		<Modal title={`New token for ${owner}`} onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<Field label="Name" hint="Where the token is used, e.g. github actions">
					<input value={name} onChange={event => setName(event.target.value)} required autoFocus maxLength={128} />
				</Field>
				<Field label="Access level" hint="Limited to the permissions of the user">
					<select value={level} onChange={event => setLevel(event.target.value as AccessLevel)}>
						{levels.map(entry => <option key={entry} value={entry}>{entry}</option>)}
					</select>
				</Field>
				<Field label="Expiration">
					<select value={expires} onChange={event => setExpires(event.target.value)}>
						<option value="7">7 days</option>
						<option value="30">30 days</option>
						<option value="90">90 days</option>
						<option value="365">1 year</option>
						<option value="">Never</option>
					</select>
				</Field>
				{error && <ErrorBox error={error} />}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || !name.trim()}>{busy ? 'Creating…' : 'Create token'}</Button>
				</div>
			</form>
		</Modal>
	);
}
