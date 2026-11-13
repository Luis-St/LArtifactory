import type {IconName} from './components/icons';
import type {Principal} from './types';

export interface NavItem {
	id: string;
	label: string;
	icon: IconName;
	admin?: boolean;
	/** Only shown when signed in, hidden while browsing as a guest. */
	user?: boolean;
}

export function visibleTo(item: NavItem, principal: Principal | null): boolean {
	return (!item.admin || principal?.admin === true) && (!item.user || principal !== null);
}

export const NAV_GROUPS: { label?: string; items: NavItem[] }[] = [
	{
		items: [
			{id: '', label: 'Overview', icon: 'overview'},
			{id: 'artifacts', label: 'Artifacts', icon: 'artifacts'},
			{id: 'repositories', label: 'Repositories', icon: 'repositories'},
		],
	},
	{
		label: 'Administration',
		items: [
			{id: 'users', label: 'Users', icon: 'users', admin: true},
			{id: 'permissions', label: 'Permissions', icon: 'permissions', admin: true},
			{id: 'webhooks', label: 'Webhooks', icon: 'webhooks', admin: true},
			{id: 'audit', label: 'Audit log', icon: 'audit', admin: true},
		],
	},
	{
		label: 'Personal',
		items: [
			{id: 'tokens', label: 'Access tokens', icon: 'tokens', user: true},
			{id: 'account', label: 'Account', icon: 'account', user: true},
		],
	},
];
