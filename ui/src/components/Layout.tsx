import {type ReactNode, useEffect, useState} from 'react';
import {href, navigate} from '../router';
import {useSession} from '../session';
import {useTheme} from '../theme';
import {REPOSITORY_TYPES} from '../types';
import {CommandPalette} from './CommandPalette';
import {NAV_GROUPS} from '../nav';
import {Icon} from './icons';
import {Menu, TYPE_INFO} from './ui';

const COLLAPSED_KEY = 'lartifactory.sidebarCollapsed';

function readCollapsed(): boolean {
	try {
		return localStorage.getItem(COLLAPSED_KEY) === 'true';
	} catch {
		return false;
	}
}

let apiDocsProbe: Promise<boolean> | null = null;

/**
 * Whether the server serves the Swagger ui, it is disabled unless ARTIFACTORY_ENABLE_API_DOCS is set.
 */
function useApiDocs(): boolean {
	const [available, setAvailable] = useState(false);
	useEffect(() => {
		apiDocsProbe ??= fetch('/openapi', {method: 'GET', headers: {Accept: 'application/json'}}).then(response => response.ok, () => false);
		let active = true;
		apiDocsProbe.then(value => active && setAvailable(value));
		return () => {
			active = false;
		};
	}, []);
	return available;
}

export function ThemeToggle() {
	const {theme, setPreference} = useTheme();
	const next = theme === 'dark' ? 'light' : 'dark';
	return (
		<button type="button" className="icon-btn" onClick={() => setPreference(next)} title={`Switch to ${next} mode`} aria-label={`Switch to ${next} mode`}>
			<Icon name={theme === 'dark' ? 'sun' : 'moon'}/>
		</button>
	);
}

export function Layout({section, type, children}: { section: string; type?: string; children: ReactNode }) {
	const {principal, logout} = useSession();
	const [open, setOpen] = useState(false);
	const [collapsed, setCollapsed] = useState(readCollapsed);
	const [searching, setSearching] = useState(false);
	const apiDocs = useApiDocs();

	useEffect(() => {
		const listener = (event: KeyboardEvent) => {
			const target = event.target as HTMLElement;
			const typing = target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
			if ((event.key === 'k' && (event.ctrlKey || event.metaKey)) || (event.key === '/' && !typing)) {
				event.preventDefault();
				setSearching(true);
			}
		};
		document.addEventListener('keydown', listener);
		return () => document.removeEventListener('keydown', listener);
	}, []);

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

	const groups = NAV_GROUPS.map(group => ({...group, items: group.items.filter(item => !item.admin || principal?.admin)})).filter(group => group.items.length > 0);

	return (
		<div className={`layout${open ? ' nav-open' : ''}${collapsed ? ' collapsed' : ''}`}>
			<aside className="sidebar">
				<div className="sidebar-inner" onClick={() => setOpen(false)}>
					<a className="brand" href={href()} title="LArtifactory">
						<img src="./favicon.svg" alt="" width={28} height={28}/>
						<span className="nav-label">LArtifactory</span>
					</a>
					<nav>
						{groups.map((group, index) => (
							<div key={index} className="nav-group">
								{group.label && <div className="nav-group-label"><span className="nav-label">{group.label}</span></div>}
								{group.items.map(item => (
									<div key={item.id}>
										<a className={`nav-link${section === item.id ? ' active' : ''}`} href={href(...(item.id ? [item.id] : []))} title={collapsed ? item.label : undefined}
										   aria-current={section === item.id ? 'page' : undefined}>
											<Icon name={item.icon}/>
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
														<span className="dot" style={{background: TYPE_INFO[repositoryType].color}}/>
														<span className="nav-label">{TYPE_INFO[repositoryType].label}</span>
													</a>
												))}
											</div>
										)}
									</div>
								))}
							</div>
						))}
					</nav>
					<div className="sidebar-footer">
						{apiDocs && (
							<a className="nav-link" href="/swagger" target="_blank" rel="noreferrer" title={collapsed ? 'API docs' : undefined}>
								<Icon name="docs"/>
								<span className="nav-label">API docs</span>
								<span className="nav-label nav-hint"><Icon name="external" size={13}/></span>
							</a>
						)}
						<button type="button" className="nav-link collapse-btn" onClick={toggleCollapsed} title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
							<Icon name={collapsed ? 'expand' : 'collapse'}/>
							<span className="nav-label">Collapse sidebar</span>
						</button>
					</div>
				</div>
			</aside>
			<div className="nav-backdrop" onClick={() => setOpen(false)}/>
			<div className="main">
				<div className="topbar">
					<button type="button" className="icon-btn menu-btn" onClick={() => setOpen(value => !value)} aria-label="Toggle navigation"><Icon name="menu" size={20}/></button>
					<button type="button" className="search-trigger" onClick={() => setSearching(true)}>
						<Icon name="search" size={16}/>
						<span>Search repositories, packages and pages…</span>
						<kbd>Ctrl K</kbd>
					</button>
					<div className="topbar-spacer"/>
					<ThemeToggle/>
					{principal && (
						<Menu
							label="Account menu"
							trigger={
								<span className="user-chip">
									<span className="avatar" aria-hidden>{principal.username.substring(0, 1).toUpperCase()}</span>
									<span className="user-name">{principal.username}</span>
									<Icon name="chevron-down" size={14}/>
								</span>
							}
							header={
								<>
									<strong>{principal.username}</strong>
									<span className="muted small">
										{principal.admin ? 'Administrator' : 'User'}
										{principal.tokenLevel ? ` · signed in with a ${principal.tokenLevel} token` : ''}
									</span>
								</>
							}
							items={[
								{label: 'Account', icon: 'account', onSelect: () => navigate(href('account'))},
								{label: 'Access tokens', icon: 'tokens', onSelect: () => navigate(href('tokens'))},
								{label: 'API docs', icon: 'docs', onSelect: () => window.open('/swagger', '_blank', 'noreferrer'), hidden: !apiDocs},
								{label: 'Sign out', icon: 'logout', onSelect: logout, danger: true},
							]}
						/>
					)}
				</div>
				<main className="content">{children}</main>
			</div>
			{searching && <CommandPalette onClose={() => setSearching(false)}/>}
		</div>
	);
}
