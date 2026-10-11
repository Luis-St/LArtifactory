import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { Badge, Button, ConfirmDialog, Empty, ErrorBox, Field, Modal, PageHeader, Spinner, errorText } from '../components/ui';
import { formatDate, formatRelative } from '../format';
import { useAsync } from '../hooks';
import { useSession } from '../session';
import { WEBHOOK_EVENTS, type Webhook } from '../types';

export function Webhooks() {
	const { notify } = useSession();
	const webhooks = useAsync(() => api.webhooks(), []);
	const [creating, setCreating] = useState(false);
	const [deleting, setDeleting] = useState<Webhook | null>(null);
	const [history, setHistory] = useState<Webhook | null>(null);
	const [testing, setTesting] = useState<string | null>(null);

	const test = async (webhook: Webhook) => {
		setTesting(webhook.id);
		try {
			const delivery = await api.testWebhook(webhook.id);
			notify(delivery.success ? `Ping delivered (HTTP ${delivery.statusCode})` : `Ping failed: ${delivery.error ?? 'no response'}`);
		} catch (e) {
			notify(errorText(e));
		} finally {
			setTesting(null);
		}
	};

	return (
		<>
			<PageHeader
				title="Webhooks"
				subtitle="Notify external services when artifacts are published or deleted and when repositories change. Payloads are signed with HMAC-SHA256 when a secret is set."
				actions={<Button variant="primary" onClick={() => setCreating(true)}>New webhook</Button>}
			/>
			{webhooks.loading && !webhooks.data && <Spinner />}
			{webhooks.error && <ErrorBox error={webhooks.error} onRetry={webhooks.reload} />}
			{webhooks.data && (webhooks.data.length === 0 ? <Empty title="No webhooks">Create a webhook to receive event notifications.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead><tr><th>URL</th><th>Scope</th><th>Events</th><th>Signed</th><th>Status</th><th>Created</th><th /></tr></thead>
						<tbody>
							{webhooks.data.map(webhook => (
								<tr key={webhook.id}>
									<td className="mono small">{webhook.url}</td>
									<td>{webhook.repository ?? <span className="muted">All repositories</span>}</td>
									<td className="small">{webhook.events.length === 0 ? <span className="muted">All events</span> : webhook.events.join(', ')}</td>
									<td>{webhook.hasSecret ? <Badge tone="success">signed</Badge> : <span className="muted">no</span>}</td>
									<td>{webhook.active ? <Badge tone="success">active</Badge> : <Badge tone="neutral">disabled</Badge>}</td>
									<td className="muted" title={formatDate(webhook.createdAt)}>{formatRelative(webhook.createdAt)}</td>
									<td className="actions">
										<Button small variant="ghost" disabled={testing === webhook.id} onClick={() => test(webhook)}>{testing === webhook.id ? 'Testing…' : 'Test'}</Button>
										<Button small variant="ghost" onClick={() => setHistory(webhook)}>History</Button>
										<Button small variant="ghost" onClick={async () => { await api.updateWebhook(webhook.id, { active: !webhook.active }); notify(webhook.active ? 'Webhook disabled' : 'Webhook enabled'); webhooks.reload(); }}>{webhook.active ? 'Disable' : 'Enable'}</Button>
										<Button small variant="ghost" className="danger-text" onClick={() => setDeleting(webhook)}>Delete</Button>
									</td>
								</tr>
							))}
						</tbody>
					</table>
				</div>
			))}

			{creating && <CreateWebhookDialog onClose={() => setCreating(false)} onCreated={() => { setCreating(false); webhooks.reload(); notify('Webhook created'); }} />}
			{history && <HistoryDialog webhook={history} onClose={() => setHistory(null)} />}
			{deleting && (
				<ConfirmDialog
					title="Delete webhook"
					confirmLabel="Delete"
					message={<>Delete the webhook to <strong>{deleting.url}</strong>?</>}
					onConfirm={async () => { await api.deleteWebhook(deleting.id); notify('Webhook deleted'); webhooks.reload(); }}
					onClose={() => setDeleting(null)}
				/>
			)}
		</>
	);
}

function CreateWebhookDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
	const repositories = useAsync(() => api.repositories(), []);
	const [url, setUrl] = useState('');
	const [repository, setRepository] = useState('');
	const [secret, setSecret] = useState('');
	const [events, setEvents] = useState<string[]>([]);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();

	const toggleEvent = (event: string) => setEvents(current => current.includes(event) ? current.filter(entry => entry !== event) : [...current, event]);

	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			await api.createWebhook({ url: url.trim(), repository: repository || undefined, secret: secret.trim() || undefined, events, active: true });
			onCreated();
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};

	return (
		<Modal title="New webhook" onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<Field label="Payload URL" hint="An http(s) endpoint that receives a POST for each event">
					<input value={url} onChange={e => setUrl(e.target.value)} required autoFocus placeholder="https://ci.example.com/hooks/lartifactory" maxLength={1024} />
				</Field>
				<Field label="Repository" hint="Leave empty to receive events from all repositories">
					<select value={repository} onChange={e => setRepository(e.target.value)}>
						<option value="">All repositories</option>
						{(repositories.data ?? []).map(repo => <option key={repo.name} value={repo.name}>{repo.name}</option>)}
					</select>
				</Field>
				<Field label="Secret" hint="Optional, used to sign payloads with HMAC-SHA256 (X-LArtifactory-Signature)">
					<input value={secret} onChange={e => setSecret(e.target.value)} placeholder="Unsigned" maxLength={256} />
				</Field>
				<Field label="Events" hint="Select none to receive all events">
					<div className="checkbox-list">
						{WEBHOOK_EVENTS.map(event => (
							<label key={event} className="cell-inline">
								<input type="checkbox" checked={events.includes(event)} onChange={() => toggleEvent(event)} />
								<span className="mono small">{event}</span>
							</label>
						))}
					</div>
				</Field>
				{error && <ErrorBox error={error} />}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || !url.trim()}>{busy ? 'Creating…' : 'Create webhook'}</Button>
				</div>
			</form>
		</Modal>
	);
}

function HistoryDialog({ webhook, onClose }: { webhook: Webhook; onClose: () => void }) {
	const deliveries = useAsync(() => api.webhookDeliveries(webhook.id), [webhook.id]);
	return (
		<Modal title="Recent deliveries" onClose={onClose} wide footer={<Button variant="primary" onClick={onClose}>Close</Button>}>
			{deliveries.loading && !deliveries.data && <Spinner />}
			{deliveries.error && <ErrorBox error={deliveries.error} onRetry={deliveries.reload} />}
			{deliveries.data && (deliveries.data.length === 0 ? <Empty title="No deliveries yet">No events have been delivered to this webhook.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead><tr><th>When</th><th>Event</th><th>Result</th></tr></thead>
						<tbody>
							{deliveries.data.map(delivery => (
								<tr key={delivery.id}>
									<td className="muted small" title={formatDate(delivery.createdAt)}>{formatRelative(delivery.createdAt)}</td>
									<td className="mono small">{delivery.event}</td>
									<td>{delivery.success
										? <Badge tone="success">HTTP {delivery.statusCode}</Badge>
										: <Badge tone="danger">{delivery.statusCode > 0 ? `HTTP ${delivery.statusCode}` : (delivery.error ?? 'failed')}</Badge>}</td>
								</tr>
							))}
						</tbody>
					</table>
				</div>
			))}
		</Modal>
	);
}
