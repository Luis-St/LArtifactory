import type {ReactNode} from 'react';

export type IconName = 'overview' | 'artifacts' | 'repositories' | 'users' | 'permissions' | 'tokens' | 'account' | 'audit' | 'webhooks' | 'docs' | 'collapse' | 'expand' | 'menu'
	| 'sun' | 'moon' | 'monitor' | 'search' | 'chevron-down' | 'chevron-right' | 'more' | 'folder' | 'file' | 'logout' | 'external' | 'trash' | 'copy' | 'check' | 'plus' | 'close' | 'download' | 'arrow-left'
	| 'arrow-up' | 'arrow-down' | 'sort' | 'package' | 'tag' | 'clock' | 'user';

const PATHS: Record<IconName, ReactNode> = {
	overview: <>
		<rect x="3" y="3" width="7" height="9" rx="1.5"/>
		<rect x="14" y="3" width="7" height="5" rx="1.5"/>
		<rect x="14" y="12" width="7" height="9" rx="1.5"/>
		<rect x="3" y="16" width="7" height="5" rx="1.5"/>
	</>,
	artifacts: <>
		<path d="M21 8 12 3 3 8v8l9 5 9-5z"/>
		<path d="m3 8 9 5 9-5"/>
		<path d="M12 13v8"/>
	</>,
	repositories: <>
		<ellipse cx="12" cy="5.5" rx="8" ry="2.5"/>
		<path d="M4 5.5v6c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-6"/>
		<path d="M4 11.5v6c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-6"/>
	</>,
	users: <>
		<circle cx="9" cy="8" r="3.5"/>
		<path d="M2.5 20c.8-3.4 3.4-5.5 6.5-5.5s5.7 2.1 6.5 5.5"/>
		<path d="M16 4.6a3.5 3.5 0 0 1 0 6.8"/>
		<path d="M18 14.8c1.8.8 3 2.5 3.5 5.2"/>
	</>,
	permissions: <>
		<path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.3 7.5 9.5 4.3-1.2 7.5-4.9 7.5-9.5V6z"/>
		<path d="m9 12 2 2 4-4"/>
	</>,
	tokens: <>
		<circle cx="8" cy="15" r="4"/>
		<path d="m10.8 12.2 8.7-8.7"/>
		<path d="m16.5 6.5 2.5 2.5"/>
		<path d="m14 9 2 2"/>
	</>,
	account: <>
		<circle cx="12" cy="12" r="9"/>
		<circle cx="12" cy="10" r="3"/>
		<path d="M6.2 18.4c1.3-2 3.4-3.2 5.8-3.2s4.5 1.2 5.8 3.2"/>
	</>,
	audit: <>
		<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
		<path d="M14 3v5h5"/>
		<path d="M9 12h6M9 16h6M9 8h2"/>
	</>,
	webhooks: <>
		<path d="M9 7a3 3 0 1 1 4 2.8l2.2 3.8"/>
		<path d="M15.5 13a3 3 0 1 1-2.9 3.8l-4.4 0"/>
		<path d="M8.5 11.2 6.3 15A3 3 0 1 0 8 16.9"/>
	</>,
	docs: <>
		<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
		<path d="M14 3v5h5"/>
		<path d="M9 13h6M9 17h4"/>
	</>,
	collapse: <>
		<rect x="3" y="4" width="18" height="16" rx="2"/>
		<path d="M9 4v16"/>
		<path d="m16 10-2 2 2 2"/>
	</>,
	expand: <>
		<rect x="3" y="4" width="18" height="16" rx="2"/>
		<path d="M9 4v16"/>
		<path d="m14 10 2 2-2 2"/>
	</>,
	menu: <path d="M4 7h16M4 12h16M4 17h16"/>,
	sun: <>
		<circle cx="12" cy="12" r="4"/>
		<path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>
	</>,
	moon: <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>,
	monitor: <>
		<rect x="3" y="4" width="18" height="12" rx="2"/>
		<path d="M8 20h8M12 16v4"/>
	</>,
	search: <>
		<circle cx="11" cy="11" r="7"/>
		<path d="m20 20-3.5-3.5"/>
	</>,
	'chevron-down': <path d="m6 9 6 6 6-6"/>,
	'chevron-right': <path d="m9 6 6 6-6 6"/>,
	more: <>
		<circle cx="5" cy="12" r="1.2"/>
		<circle cx="12" cy="12" r="1.2"/>
		<circle cx="19" cy="12" r="1.2"/>
	</>,
	folder: <>
		<path d="M3 7.5A2.5 2.5 0 0 1 5.5 5h3.4a1.5 1.5 0 0 1 1.1.5L11.5 7h7A2.5 2.5 0 0 1 21 9.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5z" fill="currentColor" fillOpacity={0.18}/>
		<path d="M3 10h18"/>
	</>,
	file: <>
		<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
		<path d="M14 3v5h5"/>
	</>,
	logout: <>
		<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"/>
		<path d="M10 17l-5-5 5-5M5 12h11"/>
	</>,
	external: <>
		<path d="M14 4h6v6M20 4l-9 9"/>
		<path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4"/>
	</>,
	trash: <>
		<path d="M4 7h16M10 11v6M14 11v6"/>
		<path d="M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/>
	</>,
	copy: <>
		<rect x="8" y="8" width="12" height="12" rx="2"/>
		<path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>
	</>,
	check: <path d="m5 12 5 5 9-10"/>,
	plus: <path d="M12 5v14M5 12h14"/>,
	close: <path d="M6 6l12 12M18 6 6 18"/>,
	download: <>
		<path d="M12 4v11M7 10l5 5 5-5"/>
		<path d="M4 19h16"/>
	</>,
	'arrow-left': <path d="M19 12H5M11 6l-6 6 6 6"/>,
	'arrow-up': <path d="M12 19V5M6 11l6-6 6 6"/>,
	'arrow-down': <path d="M12 5v14M6 13l6 6 6-6"/>,
	sort: <path d="m8 9 4-4 4 4M8 15l4 4 4-4"/>,
	package: <>
		<path d="M21 8 12 3 3 8v8l9 5 9-5z"/>
		<path d="m3 8 9 5 9-5M12 13v8M7.5 5.5l9 5"/>
	</>,
	tag: <>
		<path d="M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z"/>
		<circle cx="8" cy="8" r="1.5"/>
	</>,
	clock: <>
		<circle cx="12" cy="12" r="9"/>
		<path d="M12 7v5l3 2"/>
	</>,
	user: <>
		<circle cx="12" cy="8" r="4"/>
		<path d="M4 21c1-4 4-6 8-6s7 2 8 6"/>
	</>,
};

export function Icon({name, size = 18}: { name: IconName; size?: number }) {
	return (
		<svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
			{PATHS[name]}
		</svg>
	);
}
