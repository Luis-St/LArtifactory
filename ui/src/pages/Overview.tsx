import {api} from '../api';
import {Badge, Card, Empty, ErrorBox, PageHeader, Spinner, Stat, Stats, TYPE_INFO, TypeBadge, TypeIcon} from '../components/ui';
import {formatBytes, formatNumber, formatRelative, shortDigest} from '../format';
import {useAsync} from '../hooks';
import {loadInventory, recentUploads, summarizeTypes} from '../inventory';
import {href} from '../router';
import {useSession} from '../session';

export function Overview() {
	const {principal} = useSession();
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
		{repositories: 0, packages: 0, versions: 0, files: 0, size: 0},
	);
	const failed = inventory.data?.filter(entry => entry.error) ?? [];
	
	return (
		<>
			<PageHeader
				title="Overview"
				subtitle={`Welcome back, ${principal?.username}. Here is what is stored in this instance.`}
				actions={
					<div className="health">
						<span className={`health-dot health-${health.data?.status === 'UP' ? 'up' : health.data ? 'degraded' : 'unknown'}`}/>
						{health.data ? `Server ${health.data.status.toLowerCase()} · database ${health.data.database.toLowerCase()}` : health.error ? 'Health unavailable' : 'Checking health…'}
					</div>
				}
			/>
			
			{inventory.loading && !inventory.data && <Spinner label="Loading repositories"/>}
			{inventory.error && <ErrorBox error={inventory.error} onRetry={inventory.reload}/>}
			{failed.length > 0 && <ErrorBox error={`Could not load the content of ${failed.map(entry => entry.repository.name).join(', ')}`}/>}
			
			{inventory.data && (
				<>
					<Stats>
						<Stat label="Repositories" value={formatNumber(totals.repositories)}/>
						<Stat label="Packages" value={formatNumber(totals.packages)}/>
						<Stat label="Versions" value={formatNumber(totals.versions)}/>
						<Stat label="Files" value={formatNumber(totals.files)}/>
						<Stat label="Storage" value={formatBytes(totals.size)} hint="Before deduplication"/>
					</Stats>
					
					<h2 className="section-title">Artifacts by type</h2>
					<div className="type-grid">
						{types.map(type => (
							<a key={type.type} className={`type-card${type.repositories === 0 ? ' type-card-empty' : ''}`} href={href('artifacts', type.type)} style={{'--badge': TYPE_INFO[type.type].color} as React.CSSProperties}>
								<div className="type-card-head">
									<TypeIcon type={type.type}/>
									<div>
										<strong>{TYPE_INFO[type.type].label}</strong>
										<div className="muted small">{TYPE_INFO[type.type].ecosystem}</div>
									</div>
									<span className="type-card-repos">{type.repositories} {type.repositories === 1 ? 'repo' : 'repos'}</span>
								</div>
								<dl className="type-card-stats">
									<div>
										<dt>Packages</dt>
										<dd>{type.type === 'generic' ? '—' : formatNumber(type.packages)}</dd>
									</div>
									<div>
										<dt>Versions</dt>
										<dd>{formatNumber(type.versions)}</dd>
									</div>
									<div>
										<dt>Files</dt>
										<dd>{formatNumber(type.files)}</dd>
									</div>
								</dl>
								<div className="type-card-usage">
									<div className="type-card-usage-label small">
										<span><strong>{formatBytes(type.size)}</strong> <span className="muted">· {share(type.size, totals.size)} of all storage</span></span>
									</div>
									<div className="meter" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={totals.size > 0 ? Math.round((type.size / totals.size) * 100) : 0}
										 aria-label={`${TYPE_INFO[type.type].label} share of all storage`} title={`${formatBytes(type.size)} of ${formatBytes(totals.size)}`}>
										<span style={{width: `${totals.size > 0 ? (type.size / totals.size) * 100 : 0}%`, background: TYPE_INFO[type.type].color}}/>
									</div>
								</div>
								<div className="type-card-foot muted small">
									{type.lastUpload ? `Last upload ${formatRelative(type.lastUpload)}` : 'No uploads yet'}
								</div>
							</a>
						))}
					</div>
					
					<div className="two-columns">
						<Card title="Recent publications" actions={<a className="link small" href={href('artifacts')}>Browse all</a>}>
							<RecentList inventory={inventory.data}/>
						</Card>
						<Card title="Repositories" actions={<a className="link small" href={href('repositories')}>Manage</a>}>
							{inventory.data.length === 0 ? (
								<Empty title="No repositories yet">{principal?.admin ? <a className="link" href={href('repositories')}>Create the first repository</a> : 'Ask an administrator for access.'}</Empty>
							) : (
								<ul className="list list-grid list-repositories">
									{inventory.data.map(entry => (
										<li key={entry.repository.name}>
											<a className="list-row" href={href('repositories', entry.repository.name)}>
												<span className="list-type"><TypeBadge type={entry.repository.type}/></span>
												<span className="list-main">
													<span className="list-title">{entry.repository.name}</span>
													{entry.repository.publicRead && <Badge tone="success">public</Badge>}
												</span>
												<span className="list-meta">{entry.repository.type === 'generic' ? plural(entry.files.length, 'file') : plural(entry.packages.length, 'package')}</span>
												<span className="list-meta list-num">{formatBytes(entry.files.reduce((sum, file) => sum + file.size, 0))}</span>
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

function share(part: number, total: number): string {
	if (total <= 0 || part <= 0) {
		return '0%';
	}
	const percent = (part / total) * 100;
	return percent < 1 ? '<1%' : `${Math.round(percent)}%`;
}

function plural(count: number, word: string): string {
	return `${formatNumber(count)} ${word}${count === 1 ? '' : 's'}`;
}

function RecentList({inventory}: { inventory: Parameters<typeof recentUploads>[0] }) {
	const recent = recentUploads(inventory, 10);
	if (recent.length === 0) {
		return <Empty title="Nothing published yet">Publish a package with your package manager to see it here.</Empty>;
	}
	return (
		<ul className="list list-grid list-recent">
			{recent.map(entry => (
				<li key={`${entry.repository.name}/${entry.packageName}/${entry.version}`}>
					<a className="list-row" href={`${href('repositories', entry.repository.name)}?package=${encodeURIComponent(entry.packageName)}`} title={`${entry.packageName} in ${entry.repository.name}`}>
						<span className="list-type"><TypeBadge type={entry.repository.type}/></span>
						<span className="list-main">
							<span className="list-title">{entry.packageName}</span>
							<span className="list-version">{entry.tags.length > 0 ? entry.tags.join(', ') : shortDigest(entry.version)}</span>
						</span>
						<span className="list-meta">{entry.createdBy ?? ''}</span>
						<span className="list-meta list-num">{formatRelative(entry.createdAt)}</span>
					</a>
				</li>
			))}
		</ul>
	);
}
