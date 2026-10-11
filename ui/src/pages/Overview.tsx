import { api } from '../api';
import { Badge, Card, Empty, ErrorBox, PageHeader, Spinner, Stat, TYPE_INFO, TypeBadge, TypeIcon } from '../components/ui';
import { formatBytes, formatNumber, formatRelative } from '../format';
import { useAsync } from '../hooks';
import { loadInventory, recentUploads, summarizeTypes } from '../inventory';
import { href } from '../router';
import { useSession } from '../session';

export function Overview() {
	const { principal } = useSession();
	const health = useAsync(() => api.health(), []);
	const inventory = useAsync(loadInventory, []);

	const types = inventory.data ? summarizeTypes(inventory.data) : [];
	const totals = types.reduce(
		(sum, type) => ({
			repositories: sum.repositories + type.repositories,
			packages: sum.packages + type.packages,
			versions: sum.versions + type.versions,
			files: sum.files + type.files,
			size: sum.size + type.size,
		}),
		{ repositories: 0, packages: 0, versions: 0, files: 0, size: 0 },
	);
	const maxSize = Math.max(1, ...types.map(type => type.size));
	const failed = inventory.data?.filter(entry => entry.error) ?? [];

	return (
		<>
			<PageHeader
				title="Overview"
				subtitle={`Welcome back, ${principal?.username}. Here is what is stored in this instance.`}
				actions={
					<div className="health">
						<span className={`health-dot health-${health.data?.status === 'UP' ? 'up' : health.data ? 'degraded' : 'unknown'}`} />
						{health.data ? `Server ${health.data.status.toLowerCase()} · database ${health.data.database.toLowerCase()}` : health.error ? 'Health unavailable' : 'Checking health…'}
					</div>
				}
			/>

			{inventory.loading && !inventory.data && <Spinner label="Loading repositories" />}
			{inventory.error && <ErrorBox error={inventory.error} onRetry={inventory.reload} />}
			{failed.length > 0 && <ErrorBox error={`Could not load the content of ${failed.map(entry => entry.repository.name).join(', ')}`} />}

			{inventory.data && (
				<>
					<div className="stats-row">
						<Stat label="Repositories" value={formatNumber(totals.repositories)} />
						<Stat label="Packages" value={formatNumber(totals.packages)} />
						<Stat label="Versions" value={formatNumber(totals.versions)} />
						<Stat label="Files" value={formatNumber(totals.files)} />
						<Stat label="Storage" value={formatBytes(totals.size)} hint="Before deduplication" />
					</div>

					<h2 className="section-title">Artifacts by type</h2>
					<div className="type-grid">
						{types.map(type => (
							<a key={type.type} className="type-card" href={href('artifacts', type.type)}>
								<div className="type-card-head">
									<TypeIcon type={type.type} />
									<div>
										<strong>{TYPE_INFO[type.type].label}</strong>
										<div className="muted small">{TYPE_INFO[type.type].ecosystem}</div>
									</div>
									<span className="type-card-repos">{type.repositories} {type.repositories === 1 ? 'repo' : 'repos'}</span>
								</div>
								<dl className="type-card-stats">
									<div><dt>Packages</dt><dd>{type.type === 'generic' ? '—' : formatNumber(type.packages)}</dd></div>
									<div><dt>Versions</dt><dd>{formatNumber(type.versions)}</dd></div>
									<div><dt>Files</dt><dd>{formatNumber(type.files)}</dd></div>
								</dl>
								<div className="meter" title={formatBytes(type.size)}>
									<span style={{ width: `${(type.size / maxSize) * 100}%`, background: TYPE_INFO[type.type].color }} />
								</div>
								<div className="type-card-foot muted small">
									<span>{formatBytes(type.size)}</span>
									<span>{type.lastUpload ? `Last upload ${formatRelative(type.lastUpload)}` : 'No uploads yet'}</span>
								</div>
							</a>
						))}
					</div>

					<div className="two-columns">
						<Card title="Recent publications" actions={<a className="link small" href={href('artifacts')}>Browse all</a>}>
							<RecentList inventory={inventory.data} />
						</Card>
						<Card title="Repositories" actions={<a className="link small" href={href('repositories')}>Manage</a>}>
							{inventory.data.length === 0 ? (
								<Empty title="No repositories yet">{principal?.admin ? <a className="link" href={href('repositories')}>Create the first repository</a> : 'Ask an administrator for access.'}</Empty>
							) : (
								<ul className="list">
									{inventory.data.map(entry => (
										<li key={entry.repository.name}>
											<a className="list-row" href={href('repositories', entry.repository.name)}>
												<TypeBadge type={entry.repository.type} />
												<span className="list-main">{entry.repository.name}</span>
												{entry.repository.publicRead && <Badge tone="success">public</Badge>}
												<span className="muted small">{entry.packages.length} pkg · {formatBytes(entry.files.reduce((sum, file) => sum + file.size, 0))}</span>
											</a>
										</li>
									))}
								</ul>
							)}
						</Card>
					</div>
				</>
			)}
		</>
	);
}

function RecentList({ inventory }: { inventory: Parameters<typeof recentUploads>[0] }) {
	const recent = recentUploads(inventory, 10);
	if (recent.length === 0) {
		return <Empty title="Nothing published yet">Publish a package with your package manager to see it here.</Empty>;
	}
	return (
		<ul className="list">
			{recent.map(entry => (
				<li key={`${entry.repository.name}/${entry.packageName}/${entry.version}`}>
					<a className="list-row" href={`${href('repositories', entry.repository.name)}?package=${encodeURIComponent(entry.packageName)}`}>
						<TypeBadge type={entry.repository.type} />
						<span className="list-main">
							<span className="mono">{entry.packageName}</span>
							<span className="muted"> {entry.version}</span>
						</span>
						<span className="muted small">{entry.createdBy ? `${entry.createdBy} · ` : ''}{formatRelative(entry.createdAt)}</span>
					</a>
				</li>
			))}
		</ul>
	);
}
