import type {IconName} from './components/icons';

export interface NavItem {
	id: string;
	label: string;
	icon: IconName;
	admin?: boolean;
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
			{id: 'tokens', label: 'Access tokens', icon: 'tokens'},
			{id: 'account', label: 'Account', icon: 'account'},
		],
	},
];
