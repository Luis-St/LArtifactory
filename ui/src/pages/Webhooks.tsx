import {type FormEvent, useState} from 'react';
import {api} from '../api';
import {Icon} from '../components/icons';
import {Badge, Button, ConfirmDialog, Empty, ErrorBox, errorText, Field, Menu, Modal, PageHeader, Spinner, TYPE_INFO} from '../components/ui';
import {formatDate, formatRelative} from '../format';
import {useAsync} from '../hooks';
import {href} from '../router';
import {useSession} from '../session';
import {sortRepositories, type Webhook, WEBHOOK_EVENTS} from '../types';
import {Select} from '../components/Select';
import {SortHeader, useSort} from '../components/sort';

export function Webhooks() {
	const {notify} = useSession();
	const webhooks = useAsync(() => api.webhooks(), []);
	const [creating, setCreating] = useState(false);
	const [editing, setEditing] = useState<Webhook | null>(null);
	const [deleting, setDeleting] = useState<Webhook | null>(null);
	const [history, setHistory] = useState<Webhook | null>(null);
	const [testing, setTesting] = useState<string | null>(null);
	
	const test = async (webhook: Webhook) => {
		setTesting(webhook.id);
		try {
			const delivery = await api.testWebhook(webhook.id);
			notify(delivery.success ? `Ping delivered (HTTP ${delivery.statusCode})` : `Ping failed: ${delivery.error ?? `HTTP ${delivery.statusCode}`}`, delivery.success ? 'success' : 'error');
		} catch (e) {
			notify(errorText(e), 'error');
		} finally {
			setTesting(null);
		}
	};
	
	const toggle = async (webhook: Webhook) => {
		try {
			await api.updateWebhook(webhook.id, {active: !webhook.active});
			notify(webhook.active ? 'Webhook disabled' : 'Webhook enabled');
			webhooks.reload();
		} catch (e) {
			notify(errorText(e), 'error');
		}
	};
	
	const {sorted, sort, toggle: toggleSort} = useSort<Webhook, 'url' | 'scope' | 'events' | 'signed' | 'status' | 'created'>(webhooks.data ?? [], {
		url: webhook => webhook.url,
		scope: webhook => webhook.repository ?? '',
		events: webhook => (webhook.events.length === 0 ? Infinity : webhook.events.length),
		signed: webhook => (webhook.hasSecret ? 0 : 1),
		status: webhook => (webhook.active ? 0 : 1),
		created: webhook => webhook.createdAt,
	});
	
	return (
		<>
			<PageHeader
				title="Webhooks"
				subtitle="Notify external services when artifacts are published or deleted and when repositories change. Payloads are signed with HMAC-SHA256 when a secret is set."
				actions={<Button variant="primary" onClick={() => setCreating(true)}><Icon name="plus" size={16}/>New webhook</Button>}
			/>
			{webhooks.loading && !webhooks.data && <Spinner/>}
			{webhooks.error && <ErrorBox error={webhooks.error} onRetry={webhooks.reload}/>}
			{webhooks.data && (webhooks.data.length === 0 ? <Empty title="No webhooks">Create a webhook to receive event notifications.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<SortHeader id="url" sort={sort} onSort={toggleSort}>URL</SortHeader>
							<SortHeader id="scope" sort={sort} onSort={toggleSort}>Scope</SortHeader>
							<SortHeader id="events" sort={sort} onSort={toggleSort}>Events</SortHeader>
							<SortHeader id="signed" sort={sort} onSort={toggleSort}>Signed</SortHeader>
							<SortHeader id="status" sort={sort} onSort={toggleSort}>Status</SortHeader>
							<SortHeader id="created" sort={sort} onSort={toggleSort}>Created</SortHeader>
							<th/>
						</tr>
						</thead>
						<tbody>
						{sorted.map(webhook => (
							<tr key={webhook.id}>
								<td className="break"><span className="url-text">{webhook.url}</span></td>
								<td className="nowrap">{webhook.repository ? <a className="link" href={href('repositories', webhook.repository)}>{webhook.repository}</a> : <span className="muted">All repositories</span>}</td>
								<td>
									{webhook.events.length === 0 ? <span className="muted">All events</span> : (
										<span className="cell-inline">{webhook.events.map(event => <Badge key={event} tone={eventTone(event)} title={event}>{eventLabel(event)}</Badge>)}</span>
									)}
								</td>
								<td className="shrink">{webhook.hasSecret ? <Badge tone="success">signed</Badge> : <span className="faint">unsigned</span>}</td>
								<td className="shrink">{webhook.active ? <Badge tone="success">active</Badge> : <Badge tone="neutral">disabled</Badge>}</td>
								<td className="muted nowrap" title={formatDate(webhook.createdAt)}>{formatRelative(webhook.createdAt)}</td>
								<td className="actions">
										<span className="row-actions">
											<Button small variant="ghost" disabled={testing === webhook.id} onClick={() => test(webhook)}>{testing === webhook.id ? 'Sending…' : 'Send test'}</Button>
											<Menu label="Webhook actions" items={[
												{label: 'Edit webhook', icon: 'webhooks', onSelect: () => setEditing(webhook)},
												{label: 'Delivery history', icon: 'audit', onSelect: () => setHistory(webhook)},
												{label: webhook.active ? 'Disable' : 'Enable', icon: webhook.active ? 'close' : 'check', onSelect: () => toggle(webhook)},
												{label: 'Delete webhook', icon: 'trash', onSelect: () => setDeleting(webhook), danger: true},
											]}/>
										</span>
								</td>
							</tr>
						))}
						</tbody>
					</table>
				</div>
			))}
			
			{creating && <WebhookDialog onClose={() => setCreating(false)} onSaved={() => {
				setCreating(false);
				webhooks.reload();
				notify('Webhook created');
			}}/>}
			{editing && <WebhookDialog webhook={editing} onClose={() => setEditing(null)} onSaved={() => {
				setEditing(null);
				webhooks.reload();
				notify('Webhook saved');
			}}/>}
			{history && <HistoryDialog webhook={history} onClose={() => setHistory(null)}/>}
			{deleting && (
				<ConfirmDialog
					title="Delete webhook"
					confirmLabel="Delete"
					message={<>Delete the webhook to <strong>{deleting.url}</strong>?</>}
					onConfirm={async () => {
						await api.deleteWebhook(deleting.id);
						notify('Webhook deleted');
						webhooks.reload();
					}}
					onClose={() => setDeleting(null)}
				/>
			)}
		</>
	);
}

const EVENT_LABELS: Record<string, string> = {
	'artifact.published': 'Artifact published',
	'artifact.deleted': 'Artifact deleted',
	'package.deleted': 'Package deleted',
	'repository.created': 'Repository created',
	'repository.deleted': 'Repository deleted',
	'ping': 'Test delivery',
};

function eventLabel(event: string): string {
	return EVENT_LABELS[event] ?? event;
}

function eventTone(event: string): 'success' | 'danger' | 'neutral' {
	return event.endsWith('.deleted') ? 'danger' : event.endsWith('.published') || event.endsWith('.created') ? 'success' : 'neutral';
}

/**
 * Creates a webhook, or edits one when it is given. A stored secret is never shown, it is kept unless a new one is entered or it is removed.
 */
function WebhookDialog({webhook, onClose, onSaved}: { webhook?: Webhook; onClose: () => void; onSaved: () => void }) {
	const repositories = useAsync(() => api.repositories(), []);
	const [url, setUrl] = useState(webhook?.url ?? '');
	const [repository, setRepository] = useState(webhook?.repository ?? '');
	const [secret, setSecret] = useState('');
	const [removeSecret, setRemoveSecret] = useState(false);
	const [events, setEvents] = useState<string[]>(webhook?.events ?? []);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string>();
	
	const toggleEvent = (event: string) => setEvents(current => current.includes(event) ? current.filter(entry => entry !== event) : [...current, event]);
	
	const submit = async (event: FormEvent) => {
		event.preventDefault();
		setBusy(true);
		setError(undefined);
		try {
			if (webhook) {
				await api.updateWebhook(webhook.id, {url: url.trim(), repository, events, secret: removeSecret ? '' : secret.trim() || undefined});
			} else {
				await api.createWebhook({url: url.trim(), repository: repository || undefined, secret: secret.trim() || undefined, events, active: true});
			}
			onSaved();
		} catch (e) {
			setError(errorText(e));
			setBusy(false);
		}
	};
	
	return (
		<Modal title={webhook ? 'Edit webhook' : 'New webhook'} onClose={onClose}>
			<form className="form" onSubmit={submit}>
				<Field label="Payload URL" hint="An http(s) endpoint that receives a POST for each event">
					<input value={url} onChange={e => setUrl(e.target.value)} required autoFocus placeholder="https://ci.example.com/hooks/lartifactory" maxLength={1024}/>
				</Field>
				<Field label="Repository" hint="Leave empty to receive events from all repositories">
					<Select label="Repository" value={repository} onChange={setRepository} options={[
						{value: '', label: 'All repositories'},
						...sortRepositories(repositories.data ?? []).map(repo => ({value: repo.name, label: repo.name, hint: TYPE_INFO[repo.type].label})),
					]}/>
				</Field>
				<Field label="Secret" hint={webhook?.hasSecret ? 'A secret is set, enter a new one to replace it' : 'Optional, used to sign payloads with HMAC-SHA256 (X-LArtifactory-Signature)'}>
					<input value={secret} onChange={e => setSecret(e.target.value)} placeholder={webhook?.hasSecret ? 'Keep the current secret' : 'Unsigned'} maxLength={256} disabled={removeSecret}/>
				</Field>
				{webhook?.hasSecret && (
					<label className="checkbox-inline">
						<input type="checkbox" checked={removeSecret} onChange={e => setRemoveSecret(e.target.checked)}/>
						<span>Remove the secret, payloads are sent unsigned</span>
					</label>
				)}
				<Field group label="Events" hint="Select none to receive all events">
					<div className="checkbox-list">
						{WEBHOOK_EVENTS.map(event => (
							<label key={event}>
								<input type="checkbox" checked={events.includes(event)} onChange={() => toggleEvent(event)}/>
								<span className="event-option"><span>{eventLabel(event)}</span><code className="faint">{event}</code></span>
							</label>
						))}
					</div>
				</Field>
				{error && <ErrorBox error={error}/>}
				<div className="modal-footer">
					<Button onClick={onClose} disabled={busy}>Cancel</Button>
					<Button variant="primary" type="submit" disabled={busy || !url.trim()}>{busy ? 'Saving…' : webhook ? 'Save webhook' : 'Create webhook'}</Button>
				</div>
			</form>
		</Modal>
	);
}

function HistoryDialog({webhook, onClose}: { webhook: Webhook; onClose: () => void }) {
	const deliveries = useAsync(() => api.webhookDeliveries(webhook.id), [webhook.id]);
	return (
		<Modal title="Recent deliveries" onClose={onClose} wide footer={<Button variant="primary" onClick={onClose}>Close</Button>}>
			{deliveries.loading && !deliveries.data && <Spinner/>}
			{deliveries.error && <ErrorBox error={deliveries.error} onRetry={deliveries.reload}/>}
			{deliveries.data && (deliveries.data.length === 0 ? <Empty title="No deliveries yet">No events have been delivered to this webhook.</Empty> : (
				<div className="table-wrap">
					<table className="table">
						<thead>
						<tr>
							<th>When</th>
							<th>Event</th>
							<th>Result</th>
						</tr>
						</thead>
						<tbody>
						{deliveries.data.map(delivery => (
							<tr key={delivery.id}>
								<td className="muted small nowrap" title={formatDate(delivery.createdAt)}>{formatRelative(delivery.createdAt)}</td>
								<td title={delivery.event}>{eventLabel(delivery.event)}</td>
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
