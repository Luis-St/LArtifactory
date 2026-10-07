import { useState, type ReactNode } from 'react';
import { href } from '../router';
import { useSession } from '../session';
import { REPOSITORY_TYPES } from '../types';
import { TYPE_INFO } from './ui';

interface NavItem {
	id: string;
	label: string;
	admin?: boolean;
}

const NAV: NavItem[] = [
	{ id: '', label: 'Overview' },
	{ id: 'artifacts', label: 'Artifacts' },
	{ id: 'repositories', label: 'Repositories' },
	{ id: 'users', label: 'Users', admin: true },
	{ id: 'permissions', label: 'Permissions', admin: true },
	{ id: 'tokens', label: 'Access tokens' },
	{ id: 'account', label: 'Account' },
];

export function Layout({ section, type, children }: { section: string; type?: string; children: ReactNode }) {
	const { principal, logout } = useSession();
	const [open, setOpen] = useState(false);

	return (
		<div className={`layout${open ? ' nav-open' : ''}`}>
			<aside className="sidebar" onClick={() => setOpen(false)}>
				<a className="brand" href={href()}>
					<img src="./favicon.svg" alt="" width={28} height={28} />
					<span>LArtifactory</span>
				</a>
				<nav>
					{NAV.filter(item => !item.admin || principal?.admin).map(item => (
						<div key={item.id}>
							<a className={`nav-link${section === item.id ? ' active' : ''}`} href={href(...(item.id ? [item.id] : []))}>{item.label}</a>
							{item.id === 'artifacts' && (
								<div className="nav-sub">
									{REPOSITORY_TYPES.map(repositoryType => (
										<a key={repositoryType} className={`nav-link nav-sub-link${section === 'artifacts' && type === repositoryType ? ' active' : ''}`} href={href('artifacts', repositoryType)}>
											<span className="dot" style={{ background: TYPE_INFO[repositoryType].color }} />
											{TYPE_INFO[repositoryType].label}
										</a>
									))}
								</div>
							)}
						</div>
					))}
				</nav>
				<div className="sidebar-footer">
					<a className="nav-link" href="/swagger" target="_blank" rel="noreferrer">API docs ↗</a>
				</div>
			</aside>
			<div className="main">
				<div className="topbar">
					<button type="button" className="icon-btn menu-btn" onClick={() => setOpen(value => !value)} aria-label="Toggle navigation">☰</button>
					<div className="topbar-spacer" />
					{principal && (
						<div className="user-chip">
							<span className="avatar" aria-hidden>{principal.username.substring(0, 1).toUpperCase()}</span>
							<span className="user-name">{principal.username}</span>
							{principal.admin && <span className="badge badge-accent">admin</span>}
							{principal.tokenLevel && <span className="badge badge-neutral">token · {principal.tokenLevel}</span>}
							<button type="button" className="btn btn-ghost btn-small" onClick={logout}>Sign out</button>
						</div>
					)}
				</div>
				<main className="content">{children}</main>
			</div>
		</div>
	);
}
