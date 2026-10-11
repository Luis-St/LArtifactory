import { useState, type ReactNode } from 'react';
import { href } from '../router';
import { useSession } from '../session';
import { REPOSITORY_TYPES } from '../types';
import { Icon, type IconName } from './icons';
import { TYPE_INFO } from './ui';

interface NavItem {
	id: string;
	label: string;
	icon: IconName;
	admin?: boolean;
}

const NAV: NavItem[] = [
	{ id: '', label: 'Overview', icon: 'overview' },
	{ id: 'artifacts', label: 'Artifacts', icon: 'artifacts' },
	{ id: 'repositories', label: 'Repositories', icon: 'repositories' },
	{ id: 'users', label: 'Users', icon: 'users', admin: true },
	{ id: 'permissions', label: 'Permissions', icon: 'permissions', admin: true },
	{ id: 'tokens', label: 'Access tokens', icon: 'tokens' },
	{ id: 'account', label: 'Account', icon: 'account' },
];

const COLLAPSED_KEY = 'lartifactory.sidebarCollapsed';

function readCollapsed(): boolean {
	try {
		return localStorage.getItem(COLLAPSED_KEY) === 'true';
	} catch {
		return false;
	}
}

export function Layout({ section, type, children }: { section: string; type?: string; children: ReactNode }) {
	const { principal, logout } = useSession();
	const [open, setOpen] = useState(false);
	const [collapsed, setCollapsed] = useState(readCollapsed);

	const toggleCollapsed = () => {
		setCollapsed(value => {
			try {
				localStorage.setItem(COLLAPSED_KEY, String(!value));
			} catch {
				// Storage unavailable, the state is kept for this page only
			}
			return !value;
		});
	};

	return (
		<div className={`layout${open ? ' nav-open' : ''}${collapsed ? ' collapsed' : ''}`}>
			<aside className="sidebar">
				<div className="sidebar-inner" onClick={() => setOpen(false)}>
					<a className="brand" href={href()} title="LArtifactory">
						<img src="./favicon.svg" alt="" width={28} height={28} />
						<span className="nav-label">LArtifactory</span>
					</a>
					<nav>
						{NAV.filter(item => !item.admin || principal?.admin).map(item => (
							<div key={item.id}>
								<a className={`nav-link${section === item.id ? ' active' : ''}`} href={href(...(item.id ? [item.id] : []))} title={collapsed ? item.label : undefined}>
									<Icon name={item.icon} />
									<span className="nav-label">{item.label}</span>
								</a>
								{item.id === 'artifacts' && (
									<div className="nav-sub">
										{REPOSITORY_TYPES.map(repositoryType => (
											<a
												key={repositoryType}
												className={`nav-link nav-sub-link${section === 'artifacts' && type === repositoryType ? ' active' : ''}`}
												href={href('artifacts', repositoryType)}
												title={collapsed ? `${TYPE_INFO[repositoryType].label} artifacts` : undefined}
											>
												<span className="dot" style={{ background: TYPE_INFO[repositoryType].color }} />
												<span className="nav-label">{TYPE_INFO[repositoryType].label}</span>
											</a>
										))}
									</div>
								)}
							</div>
						))}
					</nav>
					<div className="sidebar-footer">
						<a className="nav-link" href="/swagger" target="_blank" rel="noreferrer" title={collapsed ? 'API docs' : undefined}>
							<Icon name="docs" />
							<span className="nav-label">API docs ↗</span>
						</a>
						<button type="button" className="nav-link collapse-btn" onClick={toggleCollapsed} title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
							<Icon name={collapsed ? 'expand' : 'collapse'} />
							<span className="nav-label">Collapse</span>
						</button>
					</div>
				</div>
			</aside>
			<div className="nav-backdrop" onClick={() => setOpen(false)} />
			<div className="main">
				<div className="topbar">
					<button type="button" className="icon-btn menu-btn" onClick={() => setOpen(value => !value)} aria-label="Toggle navigation"><Icon name="menu" size={20} /></button>
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
