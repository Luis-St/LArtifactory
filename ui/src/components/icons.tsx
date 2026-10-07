import type { ReactNode } from 'react';

export type IconName = 'overview' | 'artifacts' | 'repositories' | 'users' | 'permissions' | 'tokens' | 'account' | 'docs' | 'collapse' | 'expand' | 'menu';

const PATHS: Record<IconName, ReactNode> = {
	overview: <><rect x="3" y="3" width="7" height="9" rx="1.5" /><rect x="14" y="3" width="7" height="5" rx="1.5" /><rect x="14" y="12" width="7" height="9" rx="1.5" /><rect x="3" y="16" width="7" height="5" rx="1.5" /></>,
	artifacts: <><path d="M21 8 12 3 3 8v8l9 5 9-5z" /><path d="m3 8 9 5 9-5" /><path d="M12 13v8" /></>,
	repositories: <><ellipse cx="12" cy="5.5" rx="8" ry="2.5" /><path d="M4 5.5v6c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-6" /><path d="M4 11.5v6c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-6" /></>,
	users: <><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20c.8-3.4 3.4-5.5 6.5-5.5s5.7 2.1 6.5 5.5" /><path d="M16 4.6a3.5 3.5 0 0 1 0 6.8" /><path d="M18 14.8c1.8.8 3 2.5 3.5 5.2" /></>,
	permissions: <><path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.3 7.5 9.5 4.3-1.2 7.5-4.9 7.5-9.5V6z" /><path d="m9 12 2 2 4-4" /></>,
	tokens: <><circle cx="8" cy="15" r="4" /><path d="m10.8 12.2 8.7-8.7" /><path d="m16.5 6.5 2.5 2.5" /><path d="m14 9 2 2" /></>,
	account: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="10" r="3" /><path d="M6.2 18.4c1.3-2 3.4-3.2 5.8-3.2s4.5 1.2 5.8 3.2" /></>,
	docs: <><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /><path d="M9 13h6M9 17h4" /></>,
	collapse: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /><path d="m16 10-2 2 2 2" /></>,
	expand: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /><path d="m14 10 2 2-2 2" /></>,
	menu: <path d="M4 7h16M4 12h16M4 17h16" />,
};

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
	return (
		<svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
			{PATHS[name]}
		</svg>
	);
}
